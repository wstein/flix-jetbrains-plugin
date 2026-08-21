package dev.wstein.flixplugin.debugger

import com.sun.jdi.ArrayReference
import com.sun.jdi.ObjectReference
import com.sun.jdi.StringReference
import com.sun.jdi.Value

/**
 * A Datalog program, read back into the syntax it was written in.
 *
 * `#{ Edge(1, 2). Path(x, y) :- Edge(x, y). }` is a *value* in Flix, and the variables view showed
 * it as `Datalog([], [])` with `Constraint(HeadAtom, [])` under it — the shape of the AST, with the
 * program itself nowhere in sight. Everything needed to write it back out is in that AST and is
 * plain data: predicate names, variable names and literals all survive into the runtime value.
 *
 * ```
 * Datalog          facts: Constraint[], rules: Constraint[]
 * Constraint       head: HeadPredicate, body: BodyPredicate[]
 * HeadAtom         PredSym, Denotation, HeadTerm[]
 * BodyAtom         PredSym, Denotation, Polarity, Fixity, BodyTerm[]
 * PredSym          name: String, id
 * VarSym           name: String
 * HeadTerm.Lit     Boxed value
 * ```
 *
 * ## What this depends on, and what happens when it changes
 *
 * These are the standard library's own types — `Fixpoint3.Ast.Datalog.…` — not the compiler's
 * calling convention, and the *3* in that name is itself evidence that the solver gets replaced.
 * Every step below checks the case name it expects and gives up by returning `null` when it does not
 * find it, so a reorganised AST degrades to the tag rendering that came before this rather than to a
 * wrong program. The case names come from `--Xdebug`; without it there is nothing to recognise and
 * nothing is claimed.
 */
internal object FlixDatalog {

    private const val PROGRAM = "Datalog.Datalog"
    private const val MODEL = "Datalog.Model"
    private const val REL_SYM = "RelSym.Symbol"
    private const val CONSTRAINT = "Constraint.Constraint"
    private const val HEAD_ATOM = "HeadPredicate.HeadAtom"
    private const val BODY_ATOM = "BodyPredicate.BodyAtom"
    private const val PRED_SYM = "PredSym.PredSym"
    private const val VAR_SYM = "VarSym.VarSym"
    private const val NEGATIVE = "Polarity.Negative"
    private const val FIXED = "Fixity.Fixed"
    private const val LATTICE = "Denotation.Latticenal"

    /** Whether `value` is a Datalog program. */
    fun isProgram(value: ObjectReference): Boolean = caseOf(value)?.endsWith(PROGRAM) == true

    /** Whether `value` is a solved model -- what `solve` returns. */
    fun isModel(value: ObjectReference): Boolean = caseOf(value)?.endsWith(MODEL) == true

    /**
     * The relations a model holds, as `Edge/2`, with the value of each.
     *
     * A model is a map from relation symbol to the facts derived for it, and the *symbol* is exact:
     * a predicate name, an arity, and whether the relation is a lattice. The facts are not read --
     * see [renderModel].
     */
    fun relations(model: ObjectReference, limit: Int): Pair<List<Pair<String, Value?>>, Boolean> {
        val map = model.readField("v0") as? ObjectReference
            ?: return emptyList<Pair<String, Value?>>() to false
        val (entries, truncated) = FlixCollections.entries(map, limit)
        return entries.map { (key, value) -> (renderRelSym(key) ?: "?") to value } to truncated
    }

    /**
     * A model as the facts it holds: `Model#{ Edge(1, 2). Path(1, 2). }`.
     *
     * The facts of each relation live in a `BPlusTree` whose nodes are structs, so this was the
     * relations alone until `--Xdebug` began recording struct field names -- before that, walking
     * the tree would have meant deciding from arity and types which array held the keys, which is a
     * structural guess. It is now read by name.
     *
     * A relation whose facts cannot be read falls back to naming itself, `Edge/2`, rather than
     * claiming it is empty.
     */
    fun renderModel(model: ObjectReference, limit: Int): String {
        val (relations, truncated) = relations(model, limit)
        val rendered = relations.flatMap { (name, tree) -> renderRelation(name, tree, limit) }
        val body = (rendered + if (truncated) listOf("…") else emptyList()).joinToString(" ")
        return if (body.isEmpty()) "Model#{}" else "Model#{ $body }"
    }

    /**
     * The facts of one relation, as they are written: `Edge(1, 2).`
     *
     * A fact's key is the vector of its terms, boxed; its value is the lattice element, or
     * `Boxed.NoValue` where the relation is a plain one -- which is exactly the distinction the
     * language writes as `P(k, v)` against `P(k; v)`.
     */
    fun renderRelation(name: String, relation: Value?, limit: Int): List<String> {
        val tree = relation as? ObjectReference ?: return listOf(name)
        val (facts, truncated) = FlixCollections.bPlusEntries(tree, limit)
        if (facts.isEmpty()) return listOf(name)
        val predicate = name.substringBefore('/')
        val rendered = facts.map { (key, value) -> renderFact(predicate, key, value) }
        return rendered + if (truncated) listOf("…") else emptyList()
    }

    private fun renderFact(predicate: String, key: Value?, value: Value?): String {
        val terms = elementsOf(key).map { renderScalar(unbox(it)) }
        val lattice = value?.let { element ->
            val case = caseOf(element as? ObjectReference)
            if (case != null && !case.endsWith("Boxed.NoValue")) renderScalar(unbox(element)) else null
        }
        val arguments = when {
            lattice == null -> terms.joinToString(", ", "(", ")")
            terms.isEmpty() -> "($lattice)"
            else -> terms.joinToString(", ", "(", "; ") + lattice + ")"
        }
        return "$predicate$arguments."
    }

    /** A relation symbol as `Edge/2`, or `Cost/2 (lattice)` where the relation is a lattice. */
    fun renderRelSym(value: Value?): String? {
        val sym = value as? ObjectReference ?: return null
        if (caseOf(sym)?.endsWith(REL_SYM) != true) return null
        val name = predicateName(sym.readField("v0")) ?: return null
        val arity = (sym.readField("v1") as? com.sun.jdi.IntegerValue)?.value()
        val lattice = caseOf(sym.readField("v2") as? ObjectReference)?.endsWith(LATTICE) == true
        return name + (arity?.let { "/$it" } ?: "") + (if (lattice) " (lattice)" else "")
    }

    /** Whether `value` is one constraint -- a fact or a rule. */
    fun isConstraint(value: ObjectReference): Boolean = caseOf(value)?.endsWith(CONSTRAINT) == true

    /**
     * The constraints of a program, facts first, and whether the walk was cut short.
     *
     * Facts and rules are separate vectors in the value and one program in the source, so they are
     * presented as one sequence in the order they are written.
     */
    fun constraints(program: ObjectReference, limit: Int): Pair<List<Value?>, Boolean> {
        val facts = elementsOf(program.readField("v0"))
        val rules = elementsOf(program.readField("v1"))
        val all = facts + rules
        return all.take(limit) to (all.size > limit)
    }

    /** A program as it is written: `#{ Edge(1, 2). Path(x, y) :- Edge(x, y). }`. */
    fun renderProgram(program: ObjectReference, limit: Int): String {
        val (constraints, truncated) = constraints(program, limit)
        val rendered = constraints.map { constraint ->
            (constraint as? ObjectReference)?.let { renderConstraint(it) } ?: "?"
        }
        val body = (rendered + if (truncated) listOf("…") else emptyList()).joinToString(" ")
        return if (body.isEmpty()) "#{}" else "#{ $body }"
    }

    /**
     * One constraint: `Edge(1, 2).` for a fact, `Path(x, y) :- Edge(x, y).` for a rule.
     *
     * `null` if it is not a constraint, or if its head cannot be read -- the caller then falls back
     * to the tag rendering rather than showing half a rule.
     */
    fun renderConstraint(constraint: ObjectReference): String? {
        if (!isConstraint(constraint)) return null
        val head = (constraint.readField("v0") as? ObjectReference)?.let(::renderHead) ?: return null
        val body = elementsOf(constraint.readField("v1"))
            .map { atom -> (atom as? ObjectReference)?.let(::renderBody) ?: "?" }
        return if (body.isEmpty()) "$head." else "$head :- ${body.joinToString(", ")}."
    }

    /** `Path(x, y)`, or `Path(x; l)` where the last term is a lattice element. */
    private fun renderHead(head: ObjectReference): String? {
        if (caseOf(head)?.endsWith(HEAD_ATOM) != true) return null
        val name = predicateName(head.readField("v0")) ?: return null
        val lattice = caseOf(head.readField("v1") as? ObjectReference)?.endsWith(LATTICE) == true
        return name + arguments(elementsOf(head.readField("v2")), lattice)
    }

    /** `Edge(x, y)`, with `not` and `fix` where the atom carries them. */
    private fun renderBody(atom: ObjectReference): String? {
        if (caseOf(atom)?.endsWith(BODY_ATOM) != true) {
            // A guard, a functional, or anything else a body may hold. Named rather than dropped:
            // a rule missing one of its conditions would read as a different rule.
            return caseOf(atom)?.substringAfterLast('.')?.let { "<$it>" }
        }
        // Measured: (PredSym, Denotation, Polarity, Fixity, terms). A head atom carries no polarity
        // or fixity and so has three fields, which is why the two are read separately rather than
        // through one shared reader.
        val name = predicateName(atom.readField("v0")) ?: return null
        val lattice = caseOf(atom.readField("v1") as? ObjectReference)?.endsWith(LATTICE) == true
        val negated = caseOf(atom.readField("v2") as? ObjectReference)?.endsWith(NEGATIVE) == true
        val fixed = caseOf(atom.readField("v3") as? ObjectReference)?.endsWith(FIXED) == true
        val prefix = (if (negated) "not " else "") + (if (fixed) "fix " else "")
        return prefix + name + arguments(elementsOf(atom.readField("v4")), lattice)
    }

    /**
     * `(x, y)`, or `(x; l)` for a lattice atom.
     *
     * A lattice atom's last term is its lattice element, and the language separates it with a
     * semicolon -- the difference between `P(k, v)` and `P(k; v)` is the difference between a
     * relation and a map with a join, so it is not decoration.
     */
    private fun arguments(terms: List<Value?>, lattice: Boolean): String {
        val rendered = terms.map { term -> (term as? ObjectReference)?.let(::renderTerm) ?: "?" }
        if (rendered.isEmpty()) return "()"
        if (!lattice || rendered.size < 2) return rendered.joinToString(", ", "(", ")")
        return rendered.dropLast(1).joinToString(", ", "(", "; ") + rendered.last() + ")"
    }

    /** A term: a literal as its value, a variable as its name, anything else as its case. */
    private fun renderTerm(term: ObjectReference): String {
        val case = caseOf(term)?.substringAfterLast('.') ?: return "?"
        val inner = term.readField("v0")
        return when (case) {
            "Lit" -> renderScalar(unbox(inner))
            "Var" -> variableName(inner) ?: "?"
            "Wild" -> "_"
            else -> "<${case.lowercase()}>"
        }
    }

    /**
     * A boxed literal's value.
     *
     * The solver stores every literal in a `Boxed` wrapper so that one relation can hold any type;
     * the wrapper is the solver's business and not something to show.
     */
    private fun unbox(value: Value?): Value? {
        val boxed = value as? ObjectReference ?: return value
        val case = caseOf(boxed) ?: return value
        // The enum is `Fixpoint3.Boxed`, so the qualifier is matched at its end rather than at its
        // start: a case name carries its whole path, and the path has a version in it.
        return if (case.substringBeforeLast('.').endsWith("Boxed")) boxed.readField("v0") else value
    }

    private fun predicateName(value: Value?): String? {
        val sym = value as? ObjectReference ?: return null
        if (caseOf(sym)?.endsWith(PRED_SYM) != true) return null
        return (sym.readField("v0") as? StringReference)?.value()
    }

    private fun variableName(value: Value?): String? {
        val sym = value as? ObjectReference ?: return null
        if (caseOf(sym)?.endsWith(VAR_SYM) != true) return null
        return (sym.readField("v0") as? StringReference)?.value()
    }

    private fun elementsOf(value: Value?): List<Value?> =
        (value as? ArrayReference)?.let { array -> runCatching { array.values }.getOrDefault(emptyList()) }
            ?: emptyList()

    /** The case `value` is, qualified by its enum and with the specialisation hash removed. */
    private fun caseOf(value: ObjectReference?): String? {
        val target = value ?: return null
        return FlixValues.tagOf(target.referenceType().name(), recordedTagOf(target))
    }
}
