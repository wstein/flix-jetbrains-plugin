package dev.wstein.flixplugin.debugger

import com.sun.jdi.ClassType
import com.sun.jdi.Field
import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StackFrame
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The Flix call chain, rebuilt from the continuation objects on the heap.
 *
 * The shapes stubbed here were read off a live session of `flix-proc-invaders` stopped at
 * `Tuning.flix:180`: 36 JVM frames, one of them Flix, and `main -> readTuning -> path -> location`
 * held as `Frames$` cons lists in the arguments of three nested `Handler$.installHandler` frames.
 * Those lists were nested suffixes of one another, which is the property `callChain` relies on to
 * pick one -- so it is asserted here rather than left as a note.
 */
class FlixContinuationsTest {

    @Test
    fun `the chain that begins at the current definition is the one used`() {
        // What the live stop looked like: each handler level holds the frames captured at that
        // level, and they are nested suffixes. Stopped in `location`, the list that begins there is
        // the one describing the present -- and its head is dropped, because the frames view is
        // already showing that frame as the live one.
        val thread = thread(
            handlerFrame(frames("Def\$readTuning", "Clo\$main")),
            handlerFrame(frames("Tuning\$Def\$path", "Def\$readTuning", "Clo\$main")),
            handlerFrame(frames("Scores\$Def\$location", "Tuning\$Def\$path", "Def\$readTuning", "Clo\$main")),
        )

        assertEquals(
            listOf("Tuning\$Def\$path", "Def\$readTuning", "Clo\$main"),
            names(FlixContinuations.callChain(thread, type("Scores\$Def\$location"))),
        )
    }

    @Test
    fun `a longer chain whose calls have returned is not the present`() {
        // Measured at a stop in `both`, after its call to `one` returned and before its call to
        // `two`: the longest list on the thread still begins at `one`, which is finished. Taking
        // the longest would report a call that is not happening.
        val thread = thread(
            handlerFrame(frames("Clo\$main")),
            handlerFrame(frames("Def\$both", "Clo\$main")),
            handlerFrame(frames("Def\$one", "Def\$both", "Clo\$main")),
        )

        assertEquals(listOf("Clo\$main"), names(FlixContinuations.callChain(thread, type("Def\$both"))))
    }

    @Test
    fun `a thread whose chains all describe the past shows none of them`() {
        // Between suspensions the JVM stack still holds the Flix frames directly, so the frames view
        // is already showing the chain and what is on the heap is history. Showing it would be a
        // stack of calls that have returned, presented as if they had not.
        val thread = thread(handlerFrame(frames("Def\$one", "Def\$both", "Clo\$main")))

        assertEquals(emptyList<String>(), names(FlixContinuations.callChain(thread, type("Def\$two"))))
    }

    @Test
    fun `the chain a resumption captured is found when nothing on the stack holds it`() {
        // Stopped inside a handler rather than inside the computation: the suspended chain is no
        // longer an argument of anything on the JVM stack, and only the resumption still holds it.
        val resumption = resumption(frames("Tuning\$Def\$path", "Def\$readTuning", "Clo\$main"))
        val thread = thread(handlerFrame(resumption))

        assertEquals(
            listOf("Def\$readTuning", "Clo\$main"),
            names(FlixContinuations.callChain(thread, type("Tuning\$Def\$path"))),
        )
    }

    @Test
    fun `a suspension in flight is followed through to its resumption`() {
        val resumption = resumption(frames("Def\$readTuning", "Clo\$main"))
        val suspension = obj("dev.flix.runtime.Suspension\$", mapOf("resumption" to resumption))

        assertEquals(
            listOf("Clo\$main"),
            names(FlixContinuations.callChain(thread(handlerFrame(suspension)), type("Def\$readTuning"))),
        )
    }

    @Test
    fun `a suspension's own prefix counts too, and can be the longer chain`() {
        // `prefix` holds the frames unwound so far at the point the operation suspended, and the
        // resumption holds what was captured at each handler level below it. Either can be the
        // longer, so both are candidates.
        val suspension = obj(
            "dev.flix.runtime.Suspension\$",
            mapOf(
                "prefix" to frames("Scores\$Def\$location", "Tuning\$Def\$path", "Clo\$main"),
                "resumption" to resumption(frames("Clo\$main")),
            ),
        )

        assertEquals(
            listOf("Tuning\$Def\$path", "Clo\$main"),
            names(FlixContinuations.callChain(thread(handlerFrame(suspension)), type("Scores\$Def\$location"))),
        )
    }

    @Test
    fun `every level of a resumption chain is considered, not only the first`() {
        // ResumptionCons$ nests by `tail`, and the level holding the longest chain is not
        // necessarily the one reached first.
        val outer = resumption(frames("Tuning\$Def\$path", "Def\$readTuning", "Clo\$main"))
        val inner = obj(
            "dev.flix.runtime.ResumptionCons\$",
            mapOf("frames" to frames("Clo\$main"), "tail" to outer),
        )

        assertEquals(2, FlixContinuations.callChain(thread(handlerFrame(inner)), type("Tuning\$Def\$path")).size)
    }

    @Test
    fun `a thread holding no continuations yields no chain`() {
        // The ordinary case for a program with no effects: there is no trampoline, the JVM stack
        // *is* the call chain, and the frames view already shows it.
        val thread = thread(handlerFrame(obj("java.lang.Object", emptyMap())))

        assertEquals(emptyList<String>(), names(FlixContinuations.callChain(thread, type("Def\$main"))))
    }

    @Test
    fun `the walk stops at FramesNil rather than treating it as a frame`() {
        val chain = FlixContinuations.walk(frames("Clo\$main"))

        assertEquals(listOf("Clo\$main"), names(chain))
    }

    @Test
    fun `a cyclic frame list terminates instead of hanging the frames view`() {
        // A corrupt or mid-construction list must not take the frames view with it: this runs on the
        // debugger thread while the UI waits for it.
        val fields = mutableMapOf<String, Value?>("head" to continuation("Clo\$main"))
        val cell = obj("dev.flix.runtime.FramesCons\$", fields)
        fields["tail"] = cell

        assertEquals(listOf("Clo\$main"), names(FlixContinuations.walk(cell)))
    }

    @Test
    fun `a cyclic resumption chain terminates`() {
        val fields = mutableMapOf<String, Value?>("frames" to frames("Clo\$main"))
        val cell = obj("dev.flix.runtime.ResumptionCons\$", fields)
        fields["tail"] = cell

        assertEquals(1, FlixContinuations.framesOfResumption(cell).size)
    }

    @Test
    fun `a frame that refuses its arguments does not lose the chain another frame holds`() {
        // JDWP answers INVALID_SLOT for `thisObject` on a static or native frame, and the effect
        // trampoline is full of both. Before this was guarded, one such frame ended the search.
        val thread = thread(
            refusingFrame(),
            handlerFrame(frames("Def\$readTuning", "Clo\$main")),
        )

        assertEquals(listOf("Clo\$main"), names(FlixContinuations.callChain(thread, type("Def\$readTuning"))))
    }

    // --- what an entry points at ------------------------------------------------------------------

    @Test
    fun `a continuation resolves to the first line of its applyFrame`() {
        val provider = FlixAsyncStackTraceProvider()
        val continuation = continuation("Tuning\$Def\$path", "applyFrame" to listOf(180, 176, 178))

        assertEquals(176, provider.definitionLocation(continuation)?.lineNumber())
    }

    @Test
    fun `a definition applied directly resolves through staticApply`() {
        val provider = FlixAsyncStackTraceProvider()
        val continuation = continuation("Tuning\$Def\$path", "staticApply" to listOf(42, 40))

        assertEquals(40, provider.definitionLocation(continuation)?.lineNumber())
    }

    @Test
    fun `a class with no line information contributes no entry`() {
        // Rather than an entry that navigates nowhere. A build without `--Xdebug` produces exactly
        // this, and a chain of unclickable rows would read as a broken feature instead of a missing
        // compiler flag.
        val provider = FlixAsyncStackTraceProvider()
        val continuation = continuation("Tuning\$Def\$path")

        assertNull(provider.definitionLocation(continuation))
        assertNull(provider.chainOf(thread(handlerFrame(frames("Tuning\$Def\$path", "Clo\$main"))), type("Tuning\$Def\$path")))
    }

    @Test
    fun `the chain is presented innermost first, as a stack is read`() {
        val provider = FlixAsyncStackTraceProvider()
        val thread = thread(
            handlerFrame(
                frames(
                    "Scores\$Def\$location" withLines listOf(12),
                    "Def\$readTuning" withLines listOf(64),
                    "Clo\$main" withLines listOf(9),
                ),
            ),
        )

        assertEquals(listOf(64, 9), provider.chainOf(thread, type("Scores\$Def\$location"))?.map { it.line() })
    }

    @Test
    fun `a thread with no chain yields null rather than an empty async stack`() {
        // An empty list still draws the "Async stack trace" separator, promising a reconstruction
        // and showing nothing under it.
        assertNull(
            FlixAsyncStackTraceProvider().chainOf(
                thread(handlerFrame(obj("java.lang.Object", emptyMap()))),
                type("Def\$main"),
            ),
        )
    }

    @Test
    fun `a frame that is not Flix is declined`() {
        // The platform asks every provider about every frame of every JVM language.
        val provider = FlixAsyncStackTraceProvider()

        assertEquals(true, provider.isFlixSource(location(180, "Tuning.flix")))
        assertEquals(false, provider.isFlixSource(location(180, "Greeter.kt")))
        assertEquals(false, provider.isFlixSource(null))
    }

    // --- stubs ------------------------------------------------------------------------------------

    /** The class of the frame the debugger is asking about. */
    private fun type(definition: String): ReferenceType = proxy(ReferenceType::class.java) { method, _ ->
        when (method.name) {
            "name" -> "dev.flix.gen.$definition"
            else -> null
        }
    }

    private fun names(chain: List<ObjectReference>): List<String> =
        chain.map { FlixValues.simpleNameOf(it.referenceType().name()) }

    /** A `Frames$` cons list over the named definitions, ending at `FramesNil$`. */
    private fun frames(vararg definitions: String): ObjectReference =
        frames(*definitions.map { it withLines emptyList() }.toTypedArray())

    private fun frames(vararg definitions: Continuation): ObjectReference {
        var rest = obj("dev.flix.runtime.FramesNil\$", emptyMap())
        for (definition in definitions.reversed()) {
            rest = obj(
                "dev.flix.runtime.FramesCons\$",
                mapOf("head" to continuation(definition.name, *definition.methods), "tail" to rest),
            )
        }
        return rest
    }

    private fun resumption(frames: ObjectReference): ObjectReference =
        obj("dev.flix.runtime.ResumptionCons\$", mapOf("frames" to frames))

    private class Continuation(val name: String, val methods: Array<Pair<String, List<Int>>>)

    private infix fun String.withLines(lines: List<Int>): Continuation =
        Continuation(this, arrayOf("applyFrame" to lines))

    /** A continuation object, whose class is the definition it belongs to. */
    private fun continuation(definition: String, vararg methods: Pair<String, List<Int>>): ObjectReference =
        obj("dev.flix.gen.$definition", emptyMap(), methods.toMap())

    private var nextId = 0L

    private fun obj(
        className: String,
        fields: Map<String, Value?>,
        methods: Map<String, List<Int>> = emptyMap(),
    ): ObjectReference {
        val id = nextId++
        val sourceName = className.substringAfterLast('.').substringBefore('$') + ".flix"
        val type = proxy(ClassType::class.java) { method, args ->
            when (method.name) {
                "name" -> className
                "fieldByName" -> (args?.get(0) as String).takeIf { fields.containsKey(it) }?.let(::field)
                // `method(...)` would resolve to `java.lang.reflect.Method.invoke` here: Kotlin
                // treats a Java method named `invoke` as the call convention, and the lambda's
                // parameter is one. Hence the name.
                "methodsByName" -> (args?.get(0) as? String)?.let { name ->
                    methods[name]?.let { listOf(jdiMethod(name, it, sourceName)) }
                } ?: emptyList<Method>()
                else -> null
            }
        }
        return proxy(ObjectReference::class.java) { method, args ->
            when (method.name) {
                "referenceType" -> type
                // Distinct per object, as a real one is: the cycle guards key on it.
                "uniqueID" -> id
                "getValue" -> fields[(args?.get(0) as Field).name()]
                else -> null
            }
        }
    }

    private fun jdiMethod(name: String, lines: List<Int>, sourceName: String): Method =
        proxy(Method::class.java) { m, _ ->
            when (m.name) {
                "name" -> name
                "allLineLocations" -> lines.map { location(it, sourceName) }
                else -> null
            }
        }

    private fun location(line: Int, sourceName: String): Location = proxy(Location::class.java) { method, _ ->
        when (method.name) {
            "lineNumber" -> line
            "sourceName" -> sourceName
            "sourcePath" -> sourceName
            "declaringType" -> null
            else -> null
        }
    }

    private fun field(name: String): Field = proxy(Field::class.java) { method, _ ->
        when (method.name) {
            "name" -> name
            else -> null
        }
    }

    /** A JVM frame holding `held` as its only argument, as `installHandler` holds a `Frames$`. */
    private fun handlerFrame(held: ObjectReference): StackFrame = proxy(StackFrame::class.java) { method, _ ->
        when (method.name) {
            "thisObject" -> null
            "getArgumentValues" -> listOf<Value>(held)
            else -> null
        }
    }

    /** A static or native frame, which answers JDWP error 35 to both questions. */
    private fun refusingFrame(): StackFrame = proxy(StackFrame::class.java) { method, _ ->
        when (method.name) {
            "thisObject", "getArgumentValues" -> throw IllegalStateException("Invalid slot")
            else -> null
        }
    }

    private fun thread(vararg frames: StackFrame): ThreadReference = proxy(ThreadReference::class.java) { method, _ ->
        when (method.name) {
            "frameCount" -> frames.size
            "frames" -> frames.toList()
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            handler(method, args)
        } as T
}
