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
        val scope = key?.let { StepOverScope(it, callerKeyOf(process, context), depthOf(context)) }
        process.putUserData(STEP_OVER_SCOPE, scope)
        LOG.debug(
            when {
                key == null -> "step over from outside a Flix def"
                scope?.caller == null -> "step over within $key, no Flix caller to return to"
                else -> "step over within $key, returning to ${scope.caller} above depth ${scope.depth}"
            },
        )
        return null
    }

    /**
     * The Flix definition of the frame that called the one being stepped, or `null` if there is
     * none.
     *
     * Recorded so the step can stop when the stepped function *returns*. Stepping over the last
     * line of a function has nowhere to go inside that function, and the destination the user
     * expects is the line that called it -- which is a different definition, and so was passed
     * through by the scope rule alone. The step then ran on until a breakpoint caught it.
     *
     * `null` for a continuation reached through the trampoline, whose JVM caller is
     * `dev.flix.runtime`, not Flix source. That is the conservative answer: with no caller recorded
     * the return rule never fires and CPS stepping behaves exactly as before.
     */
    private fun callerKeyOf(process: DebugProcessImpl, context: SuspendContextImpl): String? {
        val caller = runCatching { context.thread?.frame(1) }.getOrNull() ?: return null
        val position = runCatching {
            caller.location()?.let { process.positionManager.getSourcePosition(it) }
        }.getOrNull()
        return FlixDefinitionScope.keyOf(position)
    }

    /**
     * How deep the stack is where the step begins, or [UNKNOWN_DEPTH] if the VM will not say.
     *
     * Paired with the caller so that arriving in the caller's definition is only accepted as a
     * return when the stack is actually shallower. Without it, a call *into* the caller's
     * definition -- mutual recursion -- would read as a return and stop a Step Over inside a nested
     * call, which is the one thing it must not do.
     */
    private fun depthOf(context: SuspendContextImpl): Int =
        runCatching { context.thread?.frameCount() }.getOrNull() ?: UNKNOWN_DEPTH

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
    internal class StepOverScope(
        val key: String,
        val caller: String? = null,
        val depth: Int = UNKNOWN_DEPTH,
    ) {
        private var remaining = MAX_INTERMEDIATE_STOPS

        /** Spends one unit of budget; `false` once it is gone. */
        fun consume(): Boolean {
            if (remaining <= 0) return false
            remaining--
            return true
        }

        /**
         * Whether being at [here] with the stack [currentDepth] frames deep means the stepped
         * function has returned.
         *
         * Both halves are required. The definition alone cannot tell a return from a call into the
         * same definition; the depth alone cannot be trusted, because CPS puts successive Flix
         * lines at the same JVM depth and the trampoline moves it for reasons that have nothing to
         * do with the source. Together they identify a return and nothing else.
         *
         * An unknown depth on either side declines rather than guesses -- the step then behaves as
         * it did before this rule existed.
         */
        fun hasReturnedTo(here: String?, currentDepth: Int): Boolean =
            here != null &&
                here == caller &&
                depth != UNKNOWN_DEPTH &&
                currentDepth != UNKNOWN_DEPTH &&
                currentDepth < depth
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

        /** Stands for a stack depth the VM would not report; see [StepOverScope.hasReturnedTo]. */
        internal const val UNKNOWN_DEPTH = -1

        /** Internal rather than private so a test can set up the state the platform would. */
        internal val STEP_OVER_SCOPE = Key.create<StepOverScope?>("flix.stepOverScope")

        /** The definition a Step Over is currently confined to, or `null` if none is in progress. */
        fun scopeOf(process: DebugProcess?): StepOverScope? = process?.getUserData(STEP_OVER_SCOPE)

        /** Abandons the current Step Over scope, leaving stepping to the platform's own rules. */
        fun clearScope(process: DebugProcess) = process.putUserData(STEP_OVER_SCOPE, null)

        private val LOG = Logger.getInstance(FlixSteppingCommands::class.java)
    }
}
