package dev.wstein.flixplugin.debugger

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType

/**
 * Maps between JDI locations and Flix source coordinates.
 *
 * Kept free of IntelliJ types so the mapping rules -- the part that is easy to get subtly wrong and
 * expensive to diagnose in a live debug session -- can be tested directly against JDI stubs.
 *
 * ## Why this is dual-mode
 *
 * The Flix fork emits a JSR-45/SMAP `SourceDebugExtension` with stratum `"Flix"`, but only for
 * classes that draw on more than one `.flix` file. `Smap.build()` returns nothing when:
 *
 *  - the class has a single source, which is the common case -- SMAP appears only once inlining
 *    pulls in code from elsewhere;
 *  - synthetic line numbers would exceed the `u2` ceiling on `LineNumberTable`;
 *  - the class is built through `ClassMaker`, which never emits SMAP.
 *
 * In all three the class still reports `SourceFile = "Main.flix"` and real `.flix` line numbers,
 * because `Smap.register` maps the primary source with the identity function. A position manager
 * keyed on `availableStrata().contains("Flix")` would therefore decline most Flix frames, which is
 * why ownership is decided by source *name* and the stratum only selects which JDI overload to ask.
 */
internal object FlixSourceLocations {

    /** The SMAP stratum the Flix fork emits; see `Smap.Stratum`. */
    const val FLIX_STRATUM: String = "Flix"

    const val FLIX_EXTENSION: String = "flix"

    /**
     * The stratum to query for [type], or `null` when it has no Flix sources at all.
     *
     * Returning the default stratum is not a fallback in the sense of "best effort" -- for a
     * single-source Flix class it is the *correct* answer, and the only one that carries line
     * numbers.
     */
    fun stratumFor(type: ReferenceType): String? {
        val flixStratum = runCatching { type.availableStrata().contains(FLIX_STRATUM) }.getOrDefault(false)
        if (flixStratum) return FLIX_STRATUM
        return if (declaresFlixSource(type)) type.defaultStratum() else null
    }

    /**
     * Whether [location] belongs to Flix source.
     *
     * Deciding on the source file name rather than on the presence of the `"Flix"` stratum is what
     * keeps `.java`, `.kt`, `.scala` and `.groovy` frames with their own position managers: their
     * classes never report a `.flix` source name, whether or not inlining happened to produce SMAP.
     */
    fun isFlixLocation(location: Location): Boolean = sourceNameOf(location) != null

    /**
     * The stratum for [location]'s declaring type, or `null` if it is not Flix code.
     *
     * Callers that need more than one attribute of a location should resolve the stratum once with
     * this and use the stratum-taking overloads below. [stratumFor] costs an `availableStrata()`
     * round-trip and often a `sourceNames()` one as well, and `getSourcePosition` runs for every
     * frame of every stack -- deriving it separately per attribute multiplies JDWP traffic on the
     * hot path for no benefit, and lets two derivations of the same value drift apart.
     */
    fun stratumOf(location: Location): String? {
        val type = runCatching { location.declaringType() }.getOrNull() ?: return null
        return stratumFor(type)
    }

    /** The `.flix` source name for [location], or `null` if it is not Flix code. */
    fun sourceNameOf(location: Location): String? =
        stratumOf(location)?.let { sourceNameOf(location, it) }

    /** As [sourceNameOf], for a caller that already resolved the stratum. */
    fun sourceNameOf(location: Location, stratum: String): String? {
        val name = runCatching { location.sourceName(stratum) }.getOrNull()
            ?: runCatching { location.sourceName() }.getOrNull()
            ?: return null
        return name.takeIf { it.isFlixSourceName() }
    }

    /**
     * The one-based `.flix` line for [location], or `null` when the line table is absent.
     *
     * JDI reports 0 or -1 for "unknown"; both are filtered here rather than by callers, so a
     * missing line can never be mistaken for line 1 after the usual zero-based conversion.
     */
    fun lineNumberOf(location: Location): Int? =
        stratumOf(location)?.let { lineNumberOf(location, it) }

    /** As [lineNumberOf], for a caller that already resolved the stratum. */
    fun lineNumberOf(location: Location, stratum: String): Int? {
        val line = runCatching { location.lineNumber(stratum) }.getOrNull()
            ?: runCatching { location.lineNumber() }.getOrNull()
            ?: return null
        return line.takeIf { it > 0 }
    }

    /** The package holding Flix's runtime support classes -- `Frame$`, `Result$`, `Thunk$`. */
    const val RUNTIME_PACKAGE: String = "dev.flix.runtime."

    /**
     * Whether [location] is inside Flix's own execution machinery with no Flix line to show.
     *
     * This is the predicate that decides where Flix stepping may **not** stop, and both halves are
     * load-bearing:
     *
     *  - *no Flix line.* A location with one is a place the user can see, so stepping stops there.
     *  - *Flix machinery.* Either a class compiled from a `.flix` file -- the generated `invoke()`
     *    bridges, which carry no `LineNumberTable` at all -- or Flix's runtime package, which holds
     *    the trampoline that drives one continuation into the next.
     *
     * The conjunction is what keeps other languages untouched. A `.java`, `.kt` or `.scala` frame
     * fails the second half whether or not it has line information, so this can never suppress a
     * stop the Java, Kotlin or Scala debugger intended -- including a Java frame compiled without
     * `-g`, which has no line numbers either.
     */
    fun isMachineryWithoutFlixLine(location: Location): Boolean {
        if (lineNumberOf(location) != null) return false
        return isFlixLocation(location) || isFlixRuntime(location)
    }

    /** Whether [location] is in Flix's runtime support package rather than in compiled Flix code. */
    fun isFlixRuntime(location: Location): Boolean {
        val declaringType = runCatching { location.declaringType().name() }.getOrNull() ?: return false
        return declaringType.startsWith(RUNTIME_PACKAGE)
    }

    /** The recorded source path for [location] in [stratum], or `null` if absent. */
    fun sourcePathOf(location: Location, stratum: String): String? =
        runCatching { location.sourcePath(stratum) }.getOrNull()
            ?: runCatching { location.sourcePath() }.getOrNull()

    /** Whether [type] declares at least one `.flix` source in [stratum]. */
    fun declaresFlixSourceIn(type: ReferenceType, stratum: String): Boolean = try {
        type.sourceNames(stratum).any { it.isFlixSourceName() }
    } catch (_: AbsentInformationException) {
        false
    } catch (_: Exception) {
        false
    }

    /**
     * The `.flix` sources [type] was compiled from, as (name, path) pairs.
     *
     * JDI reports names and paths as two parallel lists, so they are zipped back together here:
     * a caller needs both halves of one source to identify it, and either half alone is ambiguous
     * -- the name lacks directories, and the path may be absent.
     */
    fun flixSourcesOf(type: ReferenceType, stratum: String): List<Pair<String, String?>> {
        val names = runCatching { type.sourceNames(stratum) }.getOrNull().orEmpty()
        val paths = runCatching { type.sourcePaths(stratum) }.getOrNull().orEmpty()
        return names.indices
            .filter { names[it].isFlixSourceName() }
            .map { names[it] to paths.getOrNull(it) }
    }

    /**
     * Whether [name] could refer to a file called [baseName], ignoring directories.
     *
     * A cheap pre-filter only. It is deliberately permissive, because deciding *which* file a
     * source refers to requires the project, and doing that for every loaded class would be far too
     * expensive. Callers must follow it with an authoritative, project-aware check.
     */
    fun couldReferToBaseName(name: String, baseName: String): Boolean =
        name.replace('\\', '/').substringAfterLast('/').equals(baseName, ignoreCase = false)

    private fun declaresFlixSource(type: ReferenceType): Boolean =
        declaresFlixSourceIn(type, runCatching { type.defaultStratum() }.getOrNull() ?: return false)

    /**
     * Whether two source paths refer to the same file.
     *
     * Compared on the full normalized path rather than the base name: Flix projects routinely have
     * several `Main.flix` files in different modules, and matching on base name alone would resolve
     * a breakpoint against whichever one the debugger happened to see first.
     */
    fun sameSourcePath(a: String, b: String): Boolean {
        val left = normalizePath(a)
        val right = normalizePath(b)
        if (left == right) return true
        // A class's recorded source path may be relative to a source root while the IDE knows the
        // absolute path, so a suffix match on a path *boundary* is still a match. Requiring the
        // boundary stops `Main.flix` from matching `NotMain.flix`.
        return left.endsWith("/$right") || right.endsWith("/$left")
    }

    private fun normalizePath(path: String): String =
        path.replace('\\', '/').trim('/')

    private fun String.isFlixSourceName(): Boolean =
        substringAfterLast('.', "").equals(FLIX_EXTENSION, ignoreCase = true)
}
