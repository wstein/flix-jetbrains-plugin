package de.wstein.flixplugin.debugger

import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.sun.jdi.ArrayReference
import com.sun.jdi.ArrayType
import com.sun.jdi.BooleanValue
import com.sun.jdi.ByteValue
import com.sun.jdi.CharValue
import com.sun.jdi.ClassType
import com.sun.jdi.DoubleValue
import com.sun.jdi.FloatValue
import com.sun.jdi.IntegerValue
import com.sun.jdi.LongValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.PrimitiveValue
import com.sun.jdi.ShortValue
import com.sun.jdi.StackFrame
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import org.flixlang.intellij.eval.FlixDebugEvalArtifact

/**
 * Runs a compiled expression inside the paused debuggee, and brings the value back.
 *
 * ## What crosses, and why it is shaped this way
 *
 * Every argument of a remote call has to *exist inside the debuggee*, and the only way to make one
 * is to ask the VM for it. That is why the artifact travels as a single string rather than as a map
 * of byte arrays: one `mirrorOf` builds one string, while a few kilobytes of class file built a byte
 * at a time is thousands of round trips.
 *
 * The same constraint explains the boxing below. The host takes an `Object[]`, and an array element
 * must be an object — but a frame holds `Int32` as a JVM `int`, which is a primitive and cannot be
 * stored in one. Each primitive is therefore boxed by invoking `Integer.valueOf` and its siblings in
 * the debuggee. Java reflection unboxes them again on the other side, so the entry point still
 * receives the `int` its signature declares.
 *
 * ## Why the host does the rest
 *
 * Defining classes, running the trampoline and reading the result field all happen in the debuggee —
 * see `DebugEvalHost` in the compiler. Each is a loop or a lookup that would otherwise be one round
 * trip per step, and a recursive expression bounces once per call.
 *
 * ## Why the platform makes the call, and not this class
 *
 * `DebugProcessImpl.invokeMethod` rather than `ClassType.invokeMethod`. The difference is not
 * convenience: an invocation that loads a class fires a class-prepare event, and a debug session has
 * such requests armed — this plugin's own breakpoints create them. If one carries a thread-suspend
 * policy, the thread running the invocation is suspended inside it and the call never returns.
 *
 * Measured, not feared: the first version called JDI directly and the live session test hung for its
 * full fifteen-minute timeout inside `JDWP$ClassType$InvokeMethod.waitForReply`, in a debuggee where
 * a class-prepare request was still enabled. The platform disables event requests around an
 * invocation, tracks that one is in progress, and restores everything afterwards — which is
 * bookkeeping no caller should be reimplementing.
 *
 * It also picks the suspend policy. Single-threaded is what an evaluation wants, because resuming
 * every thread would move the program the user is looking at, and the platform already knows that.
 */
internal object FlixRemoteEval {

    /** The class the `--Xdebug` build delivers into the program's own output. */
    private const val HOST = "dev.flix.runtime.DebugEvalHost"

    private const val ENTRY = "evaluate"

    /**
     * Runs [artifact] against the values [frame] holds, and returns what it produced.
     *
     * @throws FlixRemoteEvalException if the host is not there, an argument cannot be read, or the
     *   expression threw. The message is the one a user sees.
     */
    fun evaluate(artifact: FlixDebugEvalArtifact, context: EvaluationContext): Value? {
        val frame = context.frameProxy?.stackFrame
            ?: throw FlixRemoteEvalException("No frame is selected, so there is nothing to run the expression against.")
        val vm = frame.virtualMachine()
        val host = hostIn(vm)
        val method = host.methodsByName(ENTRY).singleOrNull()
            ?: throw FlixRemoteEvalException(
                "the debuggee's $HOST has no single `$ENTRY`, so this plugin and the compiler that " +
                    "built the program do not agree on the evaluation protocol",
            )

        val arguments = listOf(
            vm.mirrorOf(artifact.classes),
            vm.mirrorOf(artifact.entryClass),
            vm.mirrorOf(artifact.entryMethod),
            vm.mirrorOf(artifact.valueField),
            argumentArray(artifact.parameters, context, frame),
        )

        return try {
            context.debugProcess.invokeMethod(context, host, method, arguments)
        } catch (thrown: EvaluateException) {
            val exception = thrown.exceptionFromTargetVM
            if (exception != null) {
                throw FlixRemoteEvalException(describe(exception, context), thrown)
            }
            throw FlixRemoteEvalException(
                "the expression could not be run in the debuggee: ${thrown.message}",
                thrown,
            )
        }
    }

    /**
     * The host class in the debuggee.
     *
     * Absent means the program was not built with `--Xdebug`: the host is delivered by that build
     * and by nothing else, so its absence is a statement about the build rather than about the
     * expression, and must be said that way.
     */
    private fun hostIn(vm: VirtualMachine): ClassType =
        vm.classesByName(HOST).filterIsInstance<ClassType>().singleOrNull()
            ?: throw FlixRemoteEvalException(
                "the running program has no $HOST. It was not built with --Xdebug, so there is " +
                    "nothing in it that can define and run an expression.",
            )

    /**
     * The frame's values for [names], in order, as an `Object[]` inside the debuggee.
     *
     * A name the frame does not hold is refused rather than passed as null: the compiler named these
     * from the same build the debuggee is running, so a missing one means the two have diverged, and
     * a null would arrive as a `NullPointerException` from inside generated code.
     */
    private fun argumentArray(names: List<String>, context: EvaluationContext, frame: StackFrame): ArrayReference {
        val vm = frame.virtualMachine()
        val objectArray = vm.classesByName("java.lang.Object[]").filterIsInstance<ArrayType>().singleOrNull()
            ?: throw FlixRemoteEvalException("the debuggee has no java.lang.Object[] type")

        val array = context.debugProcess.newInstance(objectArray, names.size)
        // Kept alive across the calls that follow: each boxing invocation resumes the thread, and a
        // young array with no reference from the debuggee's own stack can be collected in between.
        array.disableCollection()
        names.forEachIndexed { index, name ->
            val variable = frame.visibleVariableByName(name)
                ?: throw FlixRemoteEvalException(
                    "the frame does not hold `$name`, which the compiler named for it. The debuggee " +
                        "is running a different build than the one that produced this expression.",
                )
            array.setValue(index, boxed(frame.getValue(variable), context, vm))
        }
        return array
    }

    /**
     * [value] as something an `Object[]` can hold.
     *
     * A primitive cannot be stored in an object array, and the debuggee is the only place a box can
     * be made — so its own `valueOf` is called for it. Reflection unboxes on the other side, so the
     * entry point still receives the primitive its signature declares.
     */
    private fun boxed(value: Value?, context: EvaluationContext, vm: VirtualMachine): Value? {
        if (value == null || value !is PrimitiveValue) {
            return value
        }
        val (className, descriptor) = when (value) {
            is BooleanValue -> "java.lang.Boolean" to "(Z)Ljava/lang/Boolean;"
            is ByteValue -> "java.lang.Byte" to "(B)Ljava/lang/Byte;"
            is CharValue -> "java.lang.Character" to "(C)Ljava/lang/Character;"
            is ShortValue -> "java.lang.Short" to "(S)Ljava/lang/Short;"
            is IntegerValue -> "java.lang.Integer" to "(I)Ljava/lang/Integer;"
            is LongValue -> "java.lang.Long" to "(J)Ljava/lang/Long;"
            is FloatValue -> "java.lang.Float" to "(F)Ljava/lang/Float;"
            is DoubleValue -> "java.lang.Double" to "(D)Ljava/lang/Double;"
            else -> throw FlixRemoteEvalException("a ${value.type().name()} cannot be passed to the debuggee")
        }
        val box = vm.classesByName(className).filterIsInstance<ClassType>().singleOrNull()
            ?: throw FlixRemoteEvalException("the debuggee has not loaded $className")
        val valueOf = box.methodsByName("valueOf", descriptor).singleOrNull()
            ?: throw FlixRemoteEvalException("$className has no valueOf$descriptor")
        return context.debugProcess.invokeMethod(context, box, valueOf, listOf(value))
    }

    /**
     * What the debuggee threw, as a sentence.
     *
     * The class name and message are all that cross a debug connection, and the distinction that
     * matters is carried in the name: `DebugEvalException` is this machinery reporting a problem,
     * and anything else is the user's own expression throwing.
     */
    private fun describe(thrown: ObjectReference?, context: EvaluationContext): String {
        val type = thrown?.referenceType()?.name() ?: return "the expression failed in the debuggee"
        val message = runCatching {
            val getMessage = (thrown.referenceType() as? ClassType)
                ?.concreteMethodByName("getMessage", "()Ljava/lang/String;")
            getMessage?.let { context.debugProcess.invokeMethod(context, thrown, it, emptyList()) }
                ?.let { (it as? com.sun.jdi.StringReference)?.value() }
        }.getOrNull()

        return if (type == "dev.flix.runtime.DebugEvalException") {
            message ?: "the expression could not be evaluated in the debuggee"
        } else {
            "the expression threw $type" + (message?.let { ": $it" } ?: "")
        }
    }
}

/** Something went wrong reaching into the debuggee. [message] is what a user is shown. */
internal class FlixRemoteEvalException(message: String, cause: Throwable? = null) : Exception(message, cause)
