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
 * ## Keeping Step Over distinct from Step Into
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
 * carried the difference. Asking the VM for a shallower step does not mean "stay in this Flix
 * function"; it means "leave the trampoline", abandoning every continuation still to run.
 *
 * Recovering the function from the generated class name does not work either: the name encodes the
 * *entry point*, not the definition. In `flix-lab`, `Clo$main$399824` is compiled from `Nec.flix`
 * and `Clo$main$399833` from `Sys/Env.flix` -- library code called from `main` is named
 * `Clo$main$…` just like `main`'s own code.
 *
 * So the distinction is carried rather than recovered. [FlixSteppingCommands] records which Flix
 * definition a Step Over began in, [FlixDefinitionScope] answers which one any position sits in --
 * from the PSI, which needs no compiler support -- and this resumes the step whenever it surfaces
 * in a different definition. Step Into records no scope and therefore stops at the first Flix line
 * it reaches, unchanged.
 *
 * A recursive call re-enters the same definition and so is stepped *into*; separating those needs a
 * per-activation identity, which the source cannot supply.
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

        // No Flix line here at all -- a generated bridge or the trampoline. Nothing to show, so
        // carry on regardless of which stepping action is in progress.
        if (FlixSourceLocations.isMachineryWithoutFlixLine(location)) {
            if (LOG.isDebugEnabled) {
                LOG.debug("stepping through ${location.method()?.name()} in ${location.declaringType()?.name()}: no Flix line")
            }
            return true
        }

        return isOutsideStepOverScope(context, location)
    }

    /**
     * Whether the step has not yet arrived back in the definition the Step Over began in.
     *
     * Only consulted while a Step Over is in progress -- [FlixSteppingCommands] records the scope
     * and clears it for every other action -- so Step Into keeps stopping at the first Flix line it
     * reaches, which is what it should do.
     *
     * The destination is stated positively: **stop in the stepped definition, or in the caller once
     * the stepped function has returned.** Anywhere else is somewhere the step is passing through,
     * and that includes frames with no Flix definition at all. Reading "no Flix definition here" as
     * "stop" was the first version of this, and it halted a Step Over inside `Greeter.java` the
     * moment the stepped line called into Java -- the one thing Step Over most obviously must not
     * do.
     *
     * The caller half is not symmetry for its own sake. Stepping over the *last* line of a function
     * has nowhere to go inside that function, so the only destination left is the line that called
     * it -- and that is a different definition, which the scope rule alone passed through. The step
     * then ran on until the budget expired or a breakpoint caught it, which is what a user sees as
     * "F8 ignored my function and jumped to the next breakpoint".
     *
     * A return is only accepted when the stack is genuinely shallower than where the step began;
     * see [FlixSteppingCommands.StepOverScope.hasReturnedTo] for why both halves are needed.
     *
     * Frames belonging to other languages are only skipped *while a Flix Step Over is running*,
     * which the user started from a Flix line. Their breakpoints are untouched: this decides where
     * a step stops, not where the debugger may suspend.
     */
    private fun isOutsideStepOverScope(context: SuspendContext?, location: Location): Boolean {
        val process = context?.debugProcess ?: return false
        val scope = FlixSteppingCommands.scopeOf(process) ?: return false

        val position = runCatching { process.positionManager.getSourcePosition(location) }.getOrNull()
        val here = FlixDefinitionScope.keyOf(position)
        if (here == scope.key) {
            if (LOG.isDebugEnabled) LOG.debug("arrived back in ${scope.key}, stopping")
            return false
        }

        // Checked only once the definition already matches the caller, because reading the stack
        // depth costs a JDWP round trip and this runs at every intermediate stop.
        if (here == scope.caller && scope.hasReturnedTo(here, depthOf(context))) {
            if (LOG.isDebugEnabled) LOG.debug("${scope.key} returned to $here, stopping")
            FlixSteppingCommands.clearScope(process)
            return false
        }

        // Bounded so a step can never run away. The stepped definition is normally re-entered within
        // a few frames, but it is not guaranteed to be re-entered at all -- the stepped line may be
        // the last one to execute. Without a budget the step would then single-step to process exit.
        if (!scope.consume()) {
            LOG.debug("step-over budget exhausted before returning to ${scope.key}; stopping here")
            FlixSteppingCommands.clearScope(process)
            return false
        }

        if (LOG.isDebugEnabled) {
            LOG.debug("passing through ${here ?: location.declaringType()?.name()}, want ${scope.key}")
        }
        return true
    }

    override fun getStepRequestDepth(context: SuspendContext?): Int = StepRequest.STEP_INTO

    private fun locationOf(context: SuspendContext?): Location? =
        runCatching { context?.frameProxy?.location() }.getOrNull()

    /** The current stack depth, or [FlixSteppingCommands.UNKNOWN_DEPTH] if the VM will not say. */
    private fun depthOf(context: SuspendContext?): Int =
        runCatching { context?.thread?.frameCount() }.getOrNull()
            ?: FlixSteppingCommands.UNKNOWN_DEPTH

    private companion object {
        /** See [FlixPositionManager]; the same `#dev.wstein.flixplugin.debugger` category. */
        private val LOG = Logger.getInstance(FlixSteppingFilter::class.java)
    }
}
