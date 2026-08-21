package org.flixlang.intellij.eval

import com.intellij.openapi.project.Project

/**
 * Asks the Flix compiler what an expression typed in a paused frame would be.
 *
 * ## Why this interface exists at all
 *
 * The two halves of the question live in modules that cannot see each other. The frame comes from
 * the debugger, which needs the Java plugin; the answer comes from the language server, which is
 * reached through LSP4IJ. `backend` and `debugger` are separate content modules with separate
 * classloaders and neither may depend on the other (ADR 0001, and the module table in `CLAUDE.md`).
 *
 * So the *shape* of the question lives here, in `language`, which both of them already depend on and
 * which loads in every IDE. `backend` registers the implementation that speaks LSP; `debugger` asks
 * for it by this interface and gets `null` in an IDE where LSP4IJ is not installed — which is not an
 * error, only an evaluator with nothing to ask.
 *
 * ## What it does not do
 *
 * Nothing is executed. This types an expression and reports its effect; whether it may then be
 * *run* is a decision that needs the effect this reports, and running it needs an artifact and a
 * host in the debuggee, neither of which exists yet.
 */
interface FlixDebugEval {

    /**
     * Types [expression] against the frame [className]`.`[methodName], or explains why it cannot.
     *
     * The frame is named as JDI reports it — a generated class and a method — because no position in
     * any source file identifies it: one `.flix` definition becomes several classes, and a
     * continuation's parameters live in a method the programmer never wrote.
     *
     * Blocking, and deliberately so: the caller is an evaluator that already has a paused debuggee
     * and a user waiting. It must not be called on the UI thread.
     */
    fun compile(expression: String, className: String, methodName: String, policy: Policy): FlixDebugEvalAnswer

    /** What an evaluation is allowed to be, decided by the caller and enforced by the compiler. */
    enum class Policy(val wireName: String) {
        /** Only an expression the compiler proves pure. What a watch may re-evaluate on every step. */
        PURE("pure"),

        /** Anything that type-checks. The caller has taken responsibility for running it. */
        ALLOW_EFFECTS("allowEffects"),
    }

    companion object {
        /**
         * The implementation for [project], or `null` where there is none.
         *
         * `null` in an IDE without LSP4IJ, and while the server is still starting. A caller must
         * treat it as "no answer available", never as "the expression is invalid": the difference
         * is what a user sees when they open a watch before the project has finished loading.
         */
        @JvmStatic
        fun getInstance(project: Project): FlixDebugEval? = project.getService(FlixDebugEval::class.java)
    }
}

/**
 * What the compiler said.
 *
 * Three outcomes, kept apart because a caller acts on each differently and collapsing them sends a
 * user looking in the wrong place:
 *
 * - [Typed] — the expression is well-typed, and [Typed.effect] decides whether it may be run;
 * - [Invalid] — the expression is wrong, and the diagnostics are the compiler's own words about it;
 * - [Unavailable] — nothing was asked or the server declined, and [Unavailable.reason] says what
 *   would have to change. A project that has not been built with `--Xdebug` lands here, and telling
 *   a user their expression is invalid would be false.
 */
sealed interface FlixDebugEvalAnswer {

    data class Typed(val type: String, val effect: String) : FlixDebugEvalAnswer {
        /** Whether the compiler proved it performs no effect, which is the only claim a watch needs. */
        val isPure: Boolean get() = effect == "Pure"
    }

    data class Invalid(val diagnostics: List<String>) : FlixDebugEvalAnswer

    data class Unavailable(val reason: String) : FlixDebugEvalAnswer
}
