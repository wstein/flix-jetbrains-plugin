package de.wstein.flixplugin.ui

import com.intellij.driver.client.Remote

/**
 * Driver stubs for the parts of XDebugger the Starter SDK does not expose.
 *
 * `driver-sdk` ships exactly one debugger entry point — `XDebuggerUtil.toggleLineBreakpoint` — and
 * nothing at all for sessions or for reading back what a toggle produced. Everything below is
 * declared here for that reason, not because a nicer API was overlooked.
 *
 * The `@Remote` value is the fully-qualified name of the class *inside the IDE*; calls are marshalled
 * over JMX, so only primitives, `String`, other `@Remote` references, and arrays of those may cross.
 * A method whose signature the IDE class does not have fails at call time rather than at compile
 * time — these are names typed against the platform, and nothing checks them until the test runs.
 */
@Remote("com.intellij.xdebugger.XDebuggerManager")
interface XDebuggerManagerRef {
    fun getBreakpointManager(): XBreakpointManagerRef
    fun getDebugSessions(): Array<XDebugSessionRef>
    fun getCurrentSession(): XDebugSessionRef?
}

@Remote("com.intellij.xdebugger.breakpoints.XBreakpointManager")
interface XBreakpointManagerRef {
    fun getAllBreakpoints(): Array<XBreakpointRef>
}

@Remote("com.intellij.xdebugger.breakpoints.XBreakpoint")
interface XBreakpointRef {
    /** The user-visible description, e.g. `Main.flix:8`. Cheaper to assert on than a source position. */
    fun getUserDescription(): String?

    fun isEnabled(): Boolean
}

@Remote("com.intellij.xdebugger.XDebugSession")
interface XDebugSessionRef {
    fun getSessionName(): String

    fun isPaused(): Boolean

    fun isStopped(): Boolean
}

/** Project execution state used to exercise the Run toolwindow's real Stop path. */
@Remote("com.intellij.execution.ExecutionManager")
interface ExecutionManagerRef {
    fun getRunningProcesses(): Array<ProcessHandlerRef>
}

/** Static accessor: ExecutionManager is registered under its abstract API, not its implementation. */
@Remote("com.intellij.execution.impl.ExecutionManagerImpl")
interface ExecutionManagerUtilRef {
    fun getInstance(project: com.intellij.driver.sdk.Project): ExecutionManagerRef
}

@Remote("com.intellij.execution.process.ProcessHandler")
interface ProcessHandlerRef {
    fun destroyProcess()

    fun isProcessTerminated(): Boolean

    fun getExitCode(): Int?
}

@Remote("com.intellij.execution.TestStateStorage")
interface TestStateStorageRef {
    fun getKeys(): Collection<String>
}

@Remote("com.intellij.execution.TestStateStorage")
interface TestStateStorageUtilRef {
    fun getInstance(project: com.intellij.driver.sdk.Project): TestStateStorageRef
}
