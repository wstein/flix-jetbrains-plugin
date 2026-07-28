package dev.wstein.flixplugin.debugger

import com.intellij.debugger.engine.ExtraSteppingFilter
import com.intellij.debugger.engine.SuspendContext
import com.intellij.openapi.diagnostic.Logger
import com.sun.jdi.Location
import com.sun.jdi.request.StepRequest

/**
 * Carries a step past Flix's generated bridges and its trampoline, so stepping stops on Flix lines
 * instead of in decompiled bytecode.
 *
 * ## Why a filter is needed at all
 *
 * JDI defines Step Over as *run until the line number changes **within this frame**, or the frame
 * pops*. That assumes a method holds several lines. Flix's CPS transformation does not produce such
 * methods -- measured over the 325 `Clo$main$*` classes `flix-lab` compiles under `--Xdebug`:
 *
 * | Method | Line-number table |
 * | --- | --- |
 * | `invoke()` | 0 of 325 carry one at all |
 * | `applyFrame(Value$)` | 260 of 325 carry exactly one entry, at bytecode offset 0 |
 *
 * Each continuation frame covers a single source line, so the "line changed" clause can never fire
 * and the step always ends on frame pop -- in `invoke()`, or in `dev.flix.runtime.Frame$`, neither
 * of which has any line information. The IDE has no source to show and falls back to the
 * decompiler.
 *
 * `PositionManager` cannot fix this: it answers *what source is this location*, and the honest
 * answer for those frames is "none". Where a step **stops** is a separate decision, and this is the
 * extension point that makes it.
 *
 * ## The policy
 *
 * Stepping resumes -- with [StepRequest.STEP_INTO] -- whenever it lands inside Flix machinery that
 * has no Flix line, and stops as soon as it reaches one.
 *
 * `STEP_INTO` rather than `STEP_OUT` is deliberate. The trampoline that drives one continuation
 * into the next is a **loop within a single frame**, so stepping out of it would leave the loop
 * entirely and skip every continuation still to run. Stepping in walks forward through the loop and
 * descends into the next `applyFrame`, which is where the next Flix line lives.
 *
 * ## Why this cannot affect other languages
 *
 * [FlixSourceLocations.isMachineryWithoutFlixLine] requires the frame to be Flix's -- compiled from
 * a `.flix` file, or in Flix's runtime package. A `.java`, `.kt` or `.scala` frame never satisfies
 * that, so no stop the Java, Kotlin or Scala debugger intended is ever suppressed. That holds even
 * for a Java class compiled without `-g`, which has no line numbers either: missing line
 * information alone is not enough to trigger this.
 *
 * ## Known limitation: Step Over behaves as Step Into
 *
 * This filter makes stepping land on Flix lines instead of in bytecode. It does **not** restore the
 * distinction between Step Over and Step Into, and no filter can.
 *
 * `Thunk$.run()` is a trampoline **loop inside a single frame**:
 *
 * ```text
 *  1: dup                    <- loop head
 *  2: instanceof Thunk$
 * 11: invokeinterface invoke()
 * 16: goto 1
 * ```
 *
 * Each continuation is invoked from that loop, so two successive Flix lines are *siblings* at the
 * same JVM depth -- whether they are consecutive statements in one function or a call into another.
 * JDI's Step Over and Step Into are defined on frame nesting, and CPS has erased the nesting that
 * carried the difference. Asking for a shallower step does not mean "stay in this Flix function"; it
 * means "leave the trampoline", which abandons every continuation still to run.
 *
 * The obvious repair -- recover the Flix function from the generated class name -- does not work
 * either. The name encodes the *entry point*, not the definition: in `flix-lab`,
 * `Clo$main$399824` is compiled from `Nec.flix` and `Clo$main$399833` from `Sys/Env.flix`. Library
 * code called from `main` is named `Clo$main$…` just like `main`'s own code.
 *
 * A real Flix Step Over therefore needs a Flix-level notion of "same function" carried into the
 * step, not a JVM-level one. Deciding what it should mean is a language question -- see the gate
 * runbook -- and is deliberately not answered here.
 *
 * ## Known cost
 *
 * A stretch of Flix runtime work with no intervening Flix line is single-stepped rather than run.
 * Between two adjacent source lines that is a handful of frames and imperceptible. A long
 * computation that stays inside the runtime -- a Datalog solve is the case to watch -- would be
 * stepped through instruction by instruction, which is slow. It is bounded, not unbounded, because
 * the platform applies the configured stepping filters as class exclusions on the step request, so
 * `java.*` and friends are not entered. Measure before assuming a Datalog fixture is usable under
 * a step; a solver-aware bound belongs with the Datalog work, not here.
 */
class FlixSteppingFilter : ExtraSteppingFilter {

    override fun isApplicable(context: SuspendContext?): Boolean {
        val location = locationOf(context) ?: return false
        val applicable = FlixSourceLocations.isMachineryWithoutFlixLine(location)
        if (applicable && LOG.isDebugEnabled) {
            LOG.debug("stepping through ${location.method()?.name()} in ${location.declaringType()?.name()}: no Flix line")
        }
        return applicable
    }

    override fun getStepRequestDepth(context: SuspendContext?): Int = StepRequest.STEP_INTO

    private fun locationOf(context: SuspendContext?): Location? =
        runCatching { context?.frameProxy?.location() }.getOrNull()

    private companion object {
        /** See [FlixPositionManager]; the same `#dev.wstein.flixplugin.debugger` category. */
        private val LOG = Logger.getInstance(FlixSteppingFilter::class.java)
    }
}
