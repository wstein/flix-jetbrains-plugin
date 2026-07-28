package dev.wstein.flixplugin.debugger

import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.SteppingAction
import com.intellij.debugger.engine.SteppingListener
import com.intellij.debugger.engine.SuspendContextImpl
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
 * `beforeSteppingStarted` is where it can be captured: it is the one point that knows both the
 * action the user chose and the position they chose it from.
 *
 * ## Scope of the recorded state
 *
 * Stored on the [DebugProcess], not in a field: one IDE can run several debug sessions, and a
 * filter shared between them would let one session's step decide another's. It is cleared on any
 * action other than Step Over and on resume, so a stale scope can never outlive the step that set
 * it -- the failure that would cause is a step that runs away looking for a function it will never
 * re-enter.
 */
class FlixSteppingListener : SteppingListener {

    override fun beforeSteppingStarted(context: SuspendContextImpl, action: SteppingAction) {
        val process = context.debugProcess ?: return
        if (action != SteppingAction.STEP_OVER) {
            clear(process)
            return
        }

        val position = runCatching {
            context.location?.let { process.positionManager.getSourcePosition(it) }
        }.getOrNull()

        val scope = FlixDefinitionScope.keyOf(position)
        process.putUserData(STEP_OVER_SCOPE, scope)
        if (LOG.isDebugEnabled) {
            LOG.debug(if (scope == null) "step over from outside a Flix def" else "step over within $scope")
        }
    }

    override fun beforeResume(context: SuspendContextImpl) {
        context.debugProcess?.let(::clear)
    }

    private fun clear(process: DebugProcess) = process.putUserData(STEP_OVER_SCOPE, null)

    internal companion object {
        private val STEP_OVER_SCOPE = Key.create<String?>("flix.stepOverScope")

        /** The definition a Step Over is currently confined to, or `null` if none is in progress. */
        fun scopeOf(process: DebugProcess?): String? = process?.getUserData(STEP_OVER_SCOPE)

        private val LOG = Logger.getInstance(FlixSteppingListener::class.java)
    }
}
