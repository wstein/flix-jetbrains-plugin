package de.wstein.flixplugin.debugger

import com.sun.jdi.Location

/**
 * How a Flix frame is labelled, in Flix.
 *
 * A frame stopped in `readTuning` read `applyFrame:88, Def$readTuning (dev.flix.gen)`: the method
 * of the compiled continuation class, the class the lowering produced, and the package the back end
 * puts it in. Every part of that is true and none of it is what the programmer wrote. The name they
 * wrote is recoverable, because the class name is generated from the definition symbol by a rule the
 * compiler states.
 *
 * ## The rule, inverted
 *
 * `JvmName.mkNamespacedClassName(ns, kind, name)` builds `<package>.<nsPrefix><kind>$<name>`, where
 * the package is `dev.flix.gen` for a namespace of at most one segment and the namespace's *first*
 * segment otherwise, and `nsPrefix` is every segment the package did not consume, each followed by
 * `$`. So:
 *
 * ```
 * dev.flix.gen.Def$readTuning      root namespace     readTuning
 * dev.flix.gen.Tuning$Def$path     mod Tuning         Tuning.path
 * Acme.Api$Def$map                 mod Acme.Api       Acme.Api.map
 * dev.flix.gen.Clo$main$626ZYxrpg1N  a lambda in main main
 * ```
 *
 * Two details of the name itself have to be undone as well, and both come from the compiler:
 *
 * - **Mangling.** `JvmName.mangle` replaces each character a JVM name cannot carry with `$` and a
 *   word -- `+` becomes `$plus` -- so `Def$$plus` is the definition `+`. The table here is that
 *   table, inverted.
 * - **The lifted-lambda hash.** `Symbol.generatedDefnSym` names a lifted lambda `<owner>$<hash>`,
 *   eleven Base58 characters of `StableHash.xxh3_64Base58`. The lambda has no name of its own in
 *   the source, so the definition it was lifted out of is the truthful answer -- `main`, not
 *   `main$626ZYxrpg1N`.
 *
 * Demangling runs first and hash-stripping second. That order is defensive rather than load-bearing,
 * and the reason is worth recording so nobody reinstates it as a rule: of the eighteen mangled words
 * only `exclamation` is eleven characters long, and Base58 excludes `l`, so no mangled name can be
 * mistaken for a hash today. The order costs nothing and stops that from depending on the table.
 */
internal object FlixFrames {

    /** The package the back end uses for a namespace that does not supply one. */
    private const val GEN_PACKAGE = "dev.flix.gen"

    /** The class-name segment that separates a namespace prefix from the symbol's own name. */
    private val KINDS = setOf("Def", "Clo", "Eff")

    /** `JvmName.mangleReplacement`, read backwards. */
    private val UNMANGLED = mapOf(
        "plus" to "+",
        "minus" to "-",
        "asterisk" to "*",
        "fslash" to "/",
        "bslash" to "\\",
        "less" to "<",
        "greater" to ">",
        "eq" to "=",
        "ampersand" to "&",
        "bar" to "|",
        "caret" to "^",
        "tilde" to "~",
        "exclamation" to "!",
        "hashtag" to "#",
        "colon" to ":",
        "question" to "?",
        "at" to "@",
        "dot" to ".",
    )

    /**
     * The Flix definition a generated class stands for, or `null` if the name carries none.
     *
     * `null` covers everything the rule does not describe: the runtime's own classes, a Java class
     * in a mixed stack, and any future generated shape whose name does not name a symbol. The
     * caller falls back to the platform's label rather than inventing one.
     */
    fun definitionOf(className: String): String? {
        val packageName = className.substringBeforeLast('.', missingDelimiterValue = "")
        val segments = className.substringAfterLast('.').split('$')
        // The *last* marker that still leaves a name after it. A namespace may itself be called
        // `Def` -- `mod Def` compiles `x` to `Def$Def$x` -- and taking the first marker would read
        // the namespace as the marker and lose it.
        val kind = segments.dropLast(1).indexOfLast { it in KINDS }
        if (kind < 0) return null

        val namespace = namespaceOf(packageName) + segments.take(kind).map(::unmangle)
        val name = withoutLiftedHash(unmangle(segments.drop(kind + 1).joinToString("$")))
        if (name.isEmpty()) return null
        return (namespace + name).joinToString(".")
    }

    /**
     * How a Flix frame at [location] reads: the definition, and the source position under it.
     *
     * `null` when the location is not Flix, or when the class name carries no definition -- both of
     * which mean this is not a frame to relabel.
     */
    fun labelOf(location: Location): FlixFrameLabel? {
        val stratum = FlixSourceLocations.stratumOf(location) ?: return null
        val sourceName = FlixSourceLocations.sourceNameOf(location, stratum) ?: return null
        val line = FlixSourceLocations.lineNumberOf(location, stratum) ?: return null
        val definition = runCatching { definitionOf(location.declaringType().name()) }.getOrNull()
            ?: return null
        return FlixFrameLabel(definition, sourceName.substringAfterLast('/'), line)
    }

    /**
     * The namespace segments the *package* carries.
     *
     * At most one, because `JvmName.packageOfNamespace` takes at most one segment -- and none at
     * all for `dev.flix.gen`, which is where a namespace with no parent of its own is put.
     */
    private fun namespaceOf(packageName: String): List<String> =
        if (packageName.isEmpty() || packageName == GEN_PACKAGE) emptyList() else listOf(packageName)

    /** [JvmName.mangle] undone: `$plus` back to `+`, and anything unrecognised left alone. */
    private fun unmangle(name: String): String {
        if (!name.contains('$')) return name
        val parts = name.split('$')
        return buildString {
            append(parts.first())
            for (part in parts.drop(1)) {
                val replacement = UNMANGLED[part]
                if (replacement != null) append(replacement) else append('$').append(part)
            }
        }
    }

    /**
     * `main$626ZYxrpg1N` back to `main`, the definition the lambda was lifted out of.
     *
     * The same suffix a monomorphised enum carries, and the same rule removes it -- see
     * [FlixValues.withoutStableHash], which is where it lives so that both readers share one.
     */
    private fun withoutLiftedHash(name: String): String = FlixValues.withoutStableHash(name)
}

/**
 * A Flix frame's label: what was called, and where it is.
 *
 * Rendered as `readTuning(), Main.flix:88` -- the definition first, because that is what a reader
 * is scanning for, and the position after it in the same order the platform uses for Java.
 */
internal data class FlixFrameLabel(val definition: String, val sourceName: String, val line: Int) {

    /** What the definition contributes: a call, so it is written as one. */
    val call: String get() = "$definition()"

    /** What the position contributes, rendered greyed beside the call. */
    val position: String get() = ", $sourceName:$line"

    override fun toString(): String = call + position
}
