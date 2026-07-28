package dev.wstein.flixplugin.debugger

import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.MethodFilter
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.impl.JvmSteppingCommandProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key

/**
 * Records which Flix definition a Step Over began in, so [FlixSteppingFilter] can tell "the next
 * line of this function" from "the next line of something it called".
 *
 * ## Why this is needed at all
 *
 * Flix's CPS output invokes every continuation from one trampoline loop in `Thunk$.run()`, so two
 * successive Flix lines sit at the *same* JVM depth whether they are consecutive statements in one
 * function or a call into another. JDI defines Step Over and Step Into purely on frame nesting, so
 * by the time a step event arrives the difference the user asked for is no longer visible in the
 * stack -- it has to be carried there.
 *
 * A stepping-command provider is where it can be captured: `DebuggerSession` consults one method
 * per action, passing the suspend context the step is starting *from*, so which method is called
 * says what the user chose and its argument says where.
 *
 * Every method here returns `null`, meaning "I am not supplying a command":
 *
 * ```java
 * cmd = computeSafeIfAny(JvmSteppingCommandProvider.EP_NAME, h -> h.getStepOverCommand(ctx, ...));
 * if (cmd == null) cmd = myDebugProcess.createStepOverCommand(ctx, ...);   // the platform's own
 * ```
 *
 * Observing through a hook whose purpose is to *supply* something is a mild abuse, and worth being
 * honest about -- but a benign one, because `null` leaves behaviour exactly as it was. That is not
 * true of every such hook: `HotSwapVetoableListener` was rejected as an invalidation signal for the
 * source cache precisely because its answer decides whether a hot swap happens.
 *
 * This replaced `SteppingListener`, which reads better but is annotated `@ApiStatus.Internal` --
 * caught by the JetBrains Plugin Verifier on its first run against configured IDE targets, and a
 * blocking finding rather than a warning.
 *
 * ## Scope of the recorded state
 *
 * Stored on the [DebugProcess], not in a field: one IDE can run several debug sessions, and a
 * filter shared between them would let one session's step decide another's. It is cleared on any
 * action other than Step Over and on resume, so a stale scope can never outlive the step that set
 * it -- the failure that would cause is a step that runs away looking for a function it will never
 * re-enter.
 */
class FlixSteppingCommands : JvmSteppingCommandProvider() {

    override fun getStepOverCommand(
        context: SuspendContextImpl?,
        ignoreBreakpoints: Boolean,
        stepSize: Int,
    ): DebugProcessImpl.ResumeCommand? {
        val process = context?.debugProcess ?: return null

        val position = runCatching {
            context.location?.let { process.positionManager.getSourcePosition(it) }
        }.getOrNull()

        val key = FlixDefinitionScope.keyOf(position)
        process.putUserData(STEP_OVER_SCOPE, key?.let(::StepOverScope))
        LOG.debug(if (key == null) "step over from outside a Flix def" else "step over within $key")
        return null
    }

    /**
     * Step Into records no scope, so the filter stops at the first Flix line it reaches.
     *
     * Clearing matters as much as recording: a scope left over from an earlier Step Over would
     * confine this step to a function the user is no longer asking about.
     */
    override fun getStepIntoCommand(
        context: SuspendContextImpl?,
        ignoreFilters: Boolean,
        smartStepFilter: MethodFilter?,
        stepSize: Int,
    ): DebugProcessImpl.ResumeCommand? = clearAndDecline(context)

    override fun getStepOutCommand(
        context: SuspendContextImpl?,
        stepSize: Int,
    ): DebugProcessImpl.ResumeCommand? = clearAndDecline(context)

    // Run to Cursor is deliberately not overridden. It is not a step, so it never consults the
    // filter, and clearing there would only add a path with nothing to clear.

    private fun clearAndDecline(context: SuspendContextImpl?): DebugProcessImpl.ResumeCommand? {
        context?.debugProcess?.let { it.putUserData(STEP_OVER_SCOPE, null) }
        return null
    }

    /**
     * The definition a Step Over is confined to, and how much further it may look for it.
     *
     * The budget exists because re-entry is not guaranteed: the stepped line may be the last one
     * that executes, and without a bound the step would single-step to process exit rather than
     * stopping. Exhausting it stops where the step happens to be, which is the same outcome as
     * having no policy at all -- a worse stop, never a hang.
     *
     * Mutable without synchronization on purpose: every access happens on the debugger manager
     * thread, which is single-threaded, and the platform asserts as much.
     */
    internal class StepOverScope(val key: String) {
        private var remaining = MAX_INTERMEDIATE_STOPS

        /** Spends one unit of budget; `false` once it is gone. */
        fun consume(): Boolean {
            if (remaining <= 0) return false
            remaining--
            return true
        }
    }

    internal companion object {
        /**
         * How many locations outside the stepped definition may be passed through.
         *
         * Generous rather than tuned: adjacent Flix lines are a handful of frames apart, and a step
         * over a call that does real work is still far below this. It is a runaway guard, not a
         * policy knob.
         */
        private const val MAX_INTERMEDIATE_STOPS = 5_000

        /** Internal rather than private so a test can set up the state the platform would. */
        internal val STEP_OVER_SCOPE = Key.create<StepOverScope?>("flix.stepOverScope")

        /** The definition a Step Over is currently confined to, or `null` if none is in progress. */
        fun scopeOf(process: DebugProcess?): StepOverScope? = process?.getUserData(STEP_OVER_SCOPE)

        /** Abandons the current Step Over scope, leaving stepping to the platform's own rules. */
        fun clearScope(process: DebugProcess) = process.putUserData(STEP_OVER_SCOPE, null)

        private val LOG = Logger.getInstance(FlixSteppingCommands::class.java)
    }
}
