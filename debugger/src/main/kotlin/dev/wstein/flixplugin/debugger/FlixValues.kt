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
/** How a walk over a list ended: at `Nil`, at the renderer's limit, or at something unexpected. */
internal enum class FlixListEnd { NIL, TRUNCATED, BROKEN }

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
     * @param ordinal the value's `ordinal` field, used only when nothing names the tag
     * @param recordedTag the value's [TAG_NAME_FIELD], which a `--Xdebug` build writes
     */
    fun formatTagged(className: String, payload: List<String>, ordinal: Int?, recordedTag: String?): String {
        val tag = displayTagOf(className, recordedTag) ?: ordinal?.let { "#$it" } ?: return simpleNameOf(className)
        return if (payload.isEmpty()) tag else "$tag(${payload.joinToString(", ")})"
    }

    /**
     * The field a `--Xdebug` build writes the case name into.
     *
     * A case with terms is compiled to a class shared by every case of its erased shape, so its
     * name is nowhere in the class -- `Tag$Obj` is `Some`, `Ok` and `Cons` at once, and only an
     * ordinal separates them. The compiler therefore records the name in the value, under
     * `--Xdebug` and only there (`BackendObjType.Tagged.NameField`). Absent in an optimized build,
     * which is why every reader of it falls back.
     */
    const val TAG_NAME_FIELD: String = "tag"

    /**
     * The case a value is, qualified by its enum where that is known.
     *
     * The recorded name first -- `List.Cons` -- because it is what the compiler wrote: unmangled,
     * and carrying the enum, which the erased representation does not. The class name second, which
     * is enough for a case without terms because that one has a class of its own, and is all there
     * is in a build without `--Xdebug`; that answer is unqualified. `null` when neither answers, and
     * then the caller has only the ordinal.
     *
     * Callers that display it want [[displayTagOf]]; callers deciding *what a value is* want this.
     */
    fun tagOf(className: String, recordedTag: String?): String? =
        recordedTag?.takeIf { it.isNotBlank() }?.let(::withoutSpecialization) ?: tagNameOf(className)

    /**
     * The source enum behind a monomorphised one: `List$Vv4NSpVAmjE.Cons` is `List.Cons`.
     *
     * Monomorphisation gives each instantiation of a generic enum a symbol of its own, named by
     * `Symbol.specializedEnumSym` as the source name plus a stable hash -- so `List[Int32]` and
     * `List[String]` are different enums by the time a value exists, and neither is spelled `List`.
     * The extra precision is real and is kept in what the compiler records; a reader asking "is this
     * a list" wants it removed.
     */
    private fun withoutSpecialization(tag: String): String {
        val separator = tag.lastIndexOf('.')
        if (separator < 0) return tag
        return withoutStableHash(tag.substring(0, separator)) + tag.substring(separator)
    }

    /**
     * A name with the compiler's stable-hash suffix removed, if it carries one.
     *
     * `StableHash.xxh3_64Base58` is eleven characters of an alphabet without `0`, `O`, `I` or `l`,
     * and the compiler appends it after a `$` wherever it needs a name to be unique without being
     * a source name: a monomorphised enum ([[withoutSpecialization]]) and a lifted lambda
     * ([[FlixFrames]]) both carry one.
     */
    fun withoutStableHash(name: String): String {
        val separator = name.lastIndexOf('$')
        if (separator <= 0) return name
        val suffix = name.substring(separator + 1)
        val isHash = suffix.length == HASH_LENGTH && suffix.all { it in BASE58 }
        return if (isHash) name.substring(0, separator) else name
    }

    /** `StableHash.HashLength`. */
    private const val HASH_LENGTH: Int = 11

    /** `StableHash.Base58Alphabet` -- no `0`, `O`, `I` or `l`. */
    private const val BASE58: String = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /**
     * The case a value is, as it is written where it is used.
     *
     * `List.Cons` is how the compiler records it and `Cons` is how it reads in the source, which is
     * what a reader is scanning for. The enum earns its place in a decision, not in a label.
     */
    fun displayTagOf(className: String, recordedTag: String?): String? =
        tagOf(className, recordedTag)?.substringAfterLast('.')

    /**
     * The class every Flix tuple is compiled to, before its component types.
     *
     * `(1, 2)` is `dev.flix.gen.Tuple$Int32$Int32` with fields `field0` and `field1`
     * (`BackendObjType.Tuple`), so both the recognition and the arity come from the class itself --
     * there is no shared representation here and nothing to guess.
     */
    const val TUPLE_PREFIX: String = "Tuple\$"

    /** A tuple's component fields, in order: `field0`, `field1`, … */
    val TUPLE_FIELD: Regex = Regex("""field(\d+)""")

    /** A tuple rendered as it is written: `(1, 2)`. */
    fun formatTuple(components: List<String>): String = components.joinToString(", ", "(", ")")

    /** Whether `className` is a Flix tuple. */
    fun isTuple(className: String): Boolean = simpleNameOf(className).startsWith(TUPLE_PREFIX)

    /**
     * The class every Flix struct is compiled to, before its field types.
     *
     * Shared by every struct of the same erased shape, with fields named `field0`, `field1` by
     * position -- so unlike a tuple, the class says how many fields there are and nothing else.
     */
    const val STRUCT_PREFIX: String = "Struct\$"

    /** The field a `--Xdebug` build writes the struct's identity into (`BackendObjType.Struct`). */
    const val STRUCT_NAME_FIELD: String = "struct"

    /** Whether `className` is a Flix struct. */
    fun isStruct(className: String): Boolean = simpleNameOf(className).startsWith(STRUCT_PREFIX)

    /**
     * The struct's name and its field names, as recorded: `Counter{count,label}`.
     *
     * `null` when nothing was recorded, which is every build without `--Xdebug`. The caller then has
     * positions and no names, and says so by keeping them.
     */
    fun structNameOf(recorded: String?): Pair<String, List<String>>? {
        val text = recorded?.takeIf { it.endsWith("}") && it.contains('{') } ?: return null
        val name = text.substringBefore('{')
        val fields = text.substringAfter('{').dropLast(1).split(',').filter { it.isNotBlank() }
        return name to fields
    }

    /** A struct rendered as it is written: `Counter { count = 3, label = "hits" }`. */
    fun formatStruct(name: String, fields: List<Pair<String, String>>, truncated: Boolean): String {
        val body = (fields.map { (field, value) -> "$field = $value" } +
            if (truncated) listOf("…") else emptyList()).joinToString(", ")
        return if (body.isEmpty()) "$name {}" else "$name { $body }"
    }

    /**
     * The class a lifted lambda is compiled to, before the definition it came out of.
     *
     * `y -> x * y` inside `curriedMultiply` becomes `dev.flix.gen.Clo$curriedMultiply$Xb8Kaq73gD3`,
     * holding what it captured in `clo0`, `clo1`, … (`JvmOps.getClosureClassName`).
     */
    const val CLOSURE_PREFIX: String = "Clo\$"

    /** A closure's captured values, in order: `clo0`, `clo1`, … */
    val CAPTURE_FIELD: Regex = Regex("""clo(\d+)""")

    /**
     * The class an anonymous object is compiled to, before its id: `Anon$220078`.
     *
     * One field, `clo0`, `clo1`, per method it implements, holding the closure that implements it
     * (`GenAnonymousClasses`), and the Java type it implements as its interface or superclass.
     */
    const val ANON_PREFIX: String = "Anon\$"

    /** Whether `className` is an anonymous object. */
    fun isAnonymous(className: String): Boolean = simpleNameOf(className).startsWith(ANON_PREFIX)

    /**
     * An anonymous object as the language writes one: `new Comparator { compare }`.
     *
     * The type is what identifies it -- `Anon$220078` is an id and nothing else -- and the method
     * names say what it implements. What each method *does* is the closure behind it, which is one
     * node down and renders as itself.
     */
    fun formatAnonymous(type: String, methods: List<String>): String {
        val body = methods.joinToString(", ")
        return if (body.isEmpty()) "new $type" else "new $type { $body }"
    }

    /** The class a `lazy` expression is compiled to, before its type: `Lazy$Int32`. */
    const val LAZY_PREFIX: String = "Lazy\$"

    /** Whether `className` is a lifted lambda. */
    fun isClosure(className: String): Boolean = simpleNameOf(className).startsWith(CLOSURE_PREFIX)

    /** Whether `className` is a lazy value. */
    fun isLazy(className: String): Boolean = simpleNameOf(className).startsWith(LAZY_PREFIX)

    /**
     * The constant a `--Xdebug` build writes a closure's capture names into.
     *
     * On the class rather than in the value, unlike a tag or a struct name: a closure class belongs
     * to one lifted lambda, so its captures have one set of names
     * (`GenFunAndClosureClasses.captureNames`).
     */
    const val CAPTURE_NAMES_FIELD: String = "cloNames"

    /** A capture the source never named, recorded so the list still lines up with `clo0`, `clo1`. */
    const val UNNAMED_CAPTURE: String = "_"

    /**
     * A function value rendered as what it is: `fn curriedMultiply(6)`.
     *
     * The name is the definition the lambda was lifted out of, which is what a reader has to go on:
     * a lambda has no name of its own, and the one place it appears in the source is inside that
     * definition. What follows is what it captured, since that is the difference between one
     * closure of `curriedMultiply` and another.
     */
    fun formatClosure(definition: String, captures: List<String>): String =
        "fn $definition(" + captures.joinToString(", ") + ")"

    /**
     * A lazy value: `lazy 4` once it has been forced, `lazy <unforced>` before.
     *
     * Which it is, is the thing worth knowing -- and reading it costs nothing, where forcing it in
     * the debugger would run the program's own code to answer a question the reader only asked by
     * looking.
     */
    fun formatLazy(value: String?): String = "lazy " + (value ?: "<unforced>")

    /** `List.Cons` and `List.Nil`, the two cases every Flix list is built from. */
    const val LIST_CONS: String = "List.Cons"
    const val LIST_NIL: String = "List.Nil"

    /**
     * A list rendered the way it is written: `1 :: 2 :: 3 :: Nil`.
     *
     * The terminator is always shown, because a Flix list always has one -- it is `Nil` or it is not
     * a list. A label that stopped early says so with an ellipsis *before* the `Nil`, which is the
     * difference between "there is more here" and "the list ends here".
     *
     * [FlixListEnd.BROKEN] is the one case with no `Nil` to show: the walk found a tail that is
     * neither a cell nor `Nil`, so what it was looking at is not a list all the way down and saying
     * otherwise would be a claim rather than a summary.
     */
    fun formatList(elements: List<String>, end: FlixListEnd): String {
        val tail = when (end) {
            FlixListEnd.NIL -> listOf("Nil")
            FlixListEnd.TRUNCATED -> listOf("…", "Nil")
            FlixListEnd.BROKEN -> listOf("…")
        }
        return (elements + tail).joinToString(" :: ")
    }

    /**
     * A map rendered as it is written: `Map#{1 => "one", 2 => "two"}`.
     *
     * `Map#{}` for an empty one, and an ellipsis where the label stopped short of the end. Unlike a
     * list there is no terminator to keep: a map is written as the entries it has.
     */
    fun formatMap(entries: List<Pair<String, String>>, truncated: Boolean): String =
        formatCollection("Map#", entries.map { (key, value) -> "$key => $value" }, truncated)

    /** A set rendered as it is written: `Set#{10, 20, 30}`. */
    fun formatSet(elements: List<String>, truncated: Boolean): String =
        formatCollection("Set#", elements, truncated)

    private fun formatCollection(prefix: String, parts: List<String>, truncated: Boolean): String {
        val body = (parts + if (truncated) listOf("…") else emptyList()).joinToString(", ")
        return if (body.isEmpty()) "$prefix{}" else "$prefix{$body}"
    }

    /**
     * How many list elements to render in a label.
     *
     * The same trade as [[MAX_RECORD_FIELDS]]: the label summarises and the tree below it holds
     * everything, so walking a long -- or circular -- list to build a string nobody reads is the
     * wrong cost to pay on the debugger thread.
     */
    const val MAX_LIST_ELEMENTS: Int = 8

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
