package de.wstein.flixplugin.debugger

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.sun.jdi.ReferenceType
import java.util.concurrent.ConcurrentHashMap

/**
 * Which `.flix` sources a loaded class was compiled from, resolved once per debug process.
 *
 * ## Why this is worth caching
 *
 * Answering it costs a JDWP round trip for the strata, another for `sourceNames`/`sourcePaths`, and
 * a file-index lookup inside a read action. Uncached, that ran:
 *
 *  - for **every loaded class** on every `getAllClasses` call — thousands once a project pulls in
 *    the Kotlin, Scala, Groovy or JRuby runtimes;
 *  - **twice** for every candidate, because `matchesSource` and `locationsOfLine` each derived it;
 *  - **again for each breakpoint**, since nothing was shared between them.
 *
 * All of it on the debugger manager thread, which is the thread the UI waits on.
 *
 * The negative answers matter most. The overwhelming majority of loaded classes are not Flix at
 * all, and "not Flix" is exactly as expensive to determine as a match — so caching it turns the
 * dominant cost into a map lookup.
 *
 * ## When an entry stops being true
 *
 * On **resume**. A class's recorded sources are otherwise immutable once it is prepared, so the
 * only thing that can change them is redefinition, and redefinition can only happen while the
 * program is running. Clearing on resume is therefore correct without needing a redefinition event
 * — which is just as well, because the platform does not offer one: `DebugProcessListener` has no
 * such callback, and `HotSwapVetoableListener` is a veto predicate consulted *before* a hot swap,
 * not a notification after it. Making a predicate clear a cache as a side effect would be a worse
 * bargain than re-resolving.
 *
 * This is the same rule the platform applies to its own VM caches, and for the same reason: after a
 * resume, anything read from a suspended VM may be stale.
 *
 * The cost of that rule is one resolution per class per stop. The saving is everything above.
 */
internal class FlixSourceCache(private val project: Project) {

    /**
     * Resolved sources per class, or [NOT_FLIX] for classes that declare none.
     *
     * Concurrent because the debugger manager thread is not the only caller — `getSourcePosition`
     * runs while frames are being rendered. A lost update would only cost a recomputation, but a
     * corrupted map would not, and the read path is hot enough to be worth the certainty.
     */
    private val byType = ConcurrentHashMap<ReferenceType, FlixSources>()

    /**
     * The `.flix` sources [type] was compiled from, resolved against the project.
     *
     * Returns [NOT_FLIX] for a class with no Flix source, which is the common case and the one the
     * cache exists for.
     */
    fun sourcesOf(type: ReferenceType): FlixSources =
        byType.getOrPut(type) { resolve(type) }

    /** Forgets everything. Called when the debuggee resumes or the session ends. */
    fun clear() = byType.clear()

    /** How many classes are currently remembered. For tests and the debug log. */
    val size: Int get() = byType.size

    private fun resolve(type: ReferenceType): FlixSources {
        val stratum = FlixSourceLocations.stratumFor(type) ?: return NOT_FLIX
        val declared = FlixSourceLocations.flixSourcesOf(type, stratum)
        if (declared.isEmpty()) return NOT_FLIX

        // One read action for the whole class rather than one per source name: entering a read
        // action is not free, and a class rarely declares more than a handful.
        val resolved = ReadAction.computeBlocking<List<ResolvedSource>, RuntimeException> {
            declared.map { (name, path) ->
                ResolvedSource(name, FlixSourceFiles.find(project, name, path)?.virtualFile)
            }
        }
        return FlixSources(stratum, resolved)
    }

    companion object {
        /** A class that declares no `.flix` source. Shared, because most classes are this. */
        val NOT_FLIX: FlixSources = FlixSources(stratum = null, sources = emptyList())
    }
}

/**
 * What a loaded class says about its Flix origins.
 *
 * @param stratum the stratum to query for line information, or `null` if the class is not Flix
 */
internal data class FlixSources(val stratum: String?, val sources: List<ResolvedSource>) {

    /** The JDI source names that resolve to [file], as JDI reported them. */
    fun namesFor(file: VirtualFile): List<String> =
        sources.filter { it.file == file }.map { it.name }.distinct()

    /** Whether any declared source resolves to [file]. */
    fun declares(file: VirtualFile): Boolean = sources.any { it.file == file }

    /**
     * What this class declared and what each name resolved to, for the debug log.
     *
     * Prints the resolved path rather than just "resolved", because the two ways this fails are
     * different bugs and otherwise indistinguishable: `unresolved` means the project index did not
     * find the file at all, while a path that is printed and still did not match means the index
     * found a *different* [VirtualFile] than the breakpoint's -- the same path resolving to two
     * objects, which identity comparison then rejects.
     */
    fun describeSources(): String =
        sources.joinToString(", ") { "${it.name} -> ${it.file?.path ?: "unresolved"}" }
}

/**
 * One `.flix` source a class was compiled from.
 *
 * @param name exactly as JDI reported it — `locationsOfLine` must be queried with this, not with a
 *   base name derived from it, or a class recording an absolute `SourceFile` yields no locations
 * @param file the project file it resolves to, or `null` when it resolves to none or is ambiguous
 */
internal data class ResolvedSource(val name: String, val file: VirtualFile?)
