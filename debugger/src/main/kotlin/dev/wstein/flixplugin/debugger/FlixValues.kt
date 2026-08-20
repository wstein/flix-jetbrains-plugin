package dev.wstein.flixplugin.debugger

/**
 * How Flix values should read in the debugger's variables view.
 *
 * The compiled forms carry the information but not the shape: a record is a linked list of
 * `label`/`value`/`rest` nodes, and a tagged union is a class whose name ends in the tag. Left
 * alone the debugger shows `{RecordExtend$Obj@1234}` and `{Chain$dotViewLeft$405229$NoneLeft@5678}`,
 * which say nothing about the value.
 *
 * Kept free of JDI so the formatting rules can be tested directly; [FlixValueRenderers] does the
 * reading and hands the pieces here.
 */
internal object FlixValues {

    /**
     * The package the compiler emits generated classes into.
     *
     * Used only where the platform insists on a qualified name. Every *match* in this file is made
     * on the simple name instead, because this package has already moved once: the renderers were
     * written when root-namespace classes were in the unnamed package, and when codegen moved them
     * here both renderers silently stopped applying. JDI reports fully qualified names, and
     * `DebuggerUtils.instanceOf` compares them verbatim, so `"Record$"` could never match
     * `dev.flix.gen.Record$` -- the variables view went back to showing `{RecordExtend$Obj@1234}`
     * and nothing said why.
     */
    const val GEN_PACKAGE: String = "dev.flix.gen."

    /** Interface implemented by every compiled Flix record. Simple name; see [GEN_PACKAGE]. */
    const val RECORD_TYPE: String = "Record\$"

    /** Superclass of every compiled Flix tagged-union value. Simple name; see [GEN_PACKAGE]. */
    const val TAGGED_TYPE: String = "Tagged\$"

    /** The empty record, which terminates the `rest` chain. Simple name; see [GEN_PACKAGE]. */
    const val RECORD_EMPTY_TYPE: String = "RecordEmpty\$"

    /**
     * The class name without its package, which is what every rule here is written against.
     *
     * A package is separated by `.` and a nested class by `$`, so this cannot truncate a name that
     * carries tags: `dev.flix.gen.BlastKind$InvaderBlast` becomes `BlastKind$InvaderBlast`.
     */
    fun simpleNameOf(className: String): String = className.substringAfterLast('.')

    /** Whether any type in `hierarchy` -- a class and its supertypes -- is [simpleName]. */
    fun isA(hierarchy: Sequence<String>, simpleName: String): Boolean =
        hierarchy.any { simpleNameOf(it) == simpleName }

    /**
     * How many record fields to render before giving up.
     *
     * A label is a summary, not the data -- the tree below it holds everything. Walking an
     * unbounded chain on the debugger thread to build a string nobody can read is the wrong trade,
     * and a corrupt or cyclic `rest` chain would otherwise not terminate at all.
     */
    const val MAX_RECORD_FIELDS: Int = 8

    /**
     * A record rendered as `{ label = value, … }`.
     *
     * The ellipsis is only added when fields were actually dropped, so a full record never looks
     * truncated.
     */
    fun formatRecord(fields: List<Pair<String, String>>, truncated: Boolean): String {
        if (fields.isEmpty() && !truncated) return "{}"
        val body = fields.joinToString(", ") { (label, value) -> "$label = $value" }
        return if (truncated) "{ $body, … }" else "{ $body }"
    }

    /**
     * A tagged-union value rendered as its tag, with any payload.
     *
     * @param className the JDI class name of the value
     * @param payload the tag's `v0`, `v1`, … fields, already rendered
     * @param ordinal the value's `ordinal` field, used only when the class name carries no tag
     */
    fun formatTagged(className: String, payload: List<String>, ordinal: Int?): String {
        val tag = tagNameOf(className) ?: ordinal?.let { "#$it" } ?: return simpleNameOf(className)
        return if (payload.isEmpty()) tag else "$tag(${payload.joinToString(", ")})"
    }

    /**
     * The tag a compiled class stands for, or `null` if its name does not carry one.
     *
     * Flix compiles a tag to a class whose name ends in it -- `Chain$dotViewLeft$405229$NoneLeft`
     * is `NoneLeft`. Values shared across tags are compiled to `Tag$Bool`, `Tag$Char$Obj` and the
     * like, where the final segment names the *representation* rather than a tag; those are refused
     * here so the caller falls back to the ordinal rather than reporting `Bool` as a tag name.
     *
     * A tag name starts with an upper-case letter, which is what separates `…$NoneLeft` from a
     * synthetic segment like `…$400074`.
     */
    fun tagNameOf(className: String): String? {
        // On the simple name: JDI hands over `dev.flix.gen.Tag$Obj$Obj`, whose last `$` segment is
        // `Obj` -- a representation, not a tag. Reading the qualified name directly reported `Obj`
        // as the tag of every shared value.
        val simple = simpleNameOf(className)
        if (simple == TAGGED_TYPE || simple.startsWith("Tag\$")) return null
        val last = simple.substringAfterLast('$')
        return last.takeIf { it.isNotEmpty() && it.first().isUpperCase() }
    }
}
