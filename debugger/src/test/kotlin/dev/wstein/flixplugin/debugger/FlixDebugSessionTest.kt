package dev.wstein.flixplugin.debugger

import com.sun.jdi.Bootstrap
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.event.VMStartEvent
import com.sun.jdi.request.EventRequest
import dev.wstein.flixplugin.FlixBuildSpec
import dev.wstein.flixplugin.FlixLaunchCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * One real debug session, end to end, with no IDE in it.
 *
 * Everything else in this module reasons about JDI values this plugin never fetched: stubs shaped
 * like what a debuggee produces. That is the right trade for the rules -- they are pure functions of
 * a shape -- but it leaves the shapes themselves unasserted, and the shapes are where this plugin
 * has been wrong before. The line a class holds, the stratum a `.flix` class declares, the class
 * name the back end chose: each was measured by hand, written into a stub, and thereafter believed.
 *
 * So this compiles a Flix program with the real compiler, launches it exactly as a debug session
 * does, attaches over JDWP, and asks the plugin's own rules about what it finds. It is the *binding*
 * half of a debug session, which is the half that is not a gesture: which class holds the line,
 * whether a request can be made for it, whether the hit maps back to the source, and what the frame
 * reads as. Pressing the gutter arrow stays out of reach here and is covered -- when it is enabled
 * -- by `FlixUiSmokeTest`.
 *
 * ## What it does not cover, measured rather than assumed
 *
 * **The SMAP path.** `FlixSourceLocations` resolves through a `Flix` stratum when a class declares
 * `SourceDebugExtension` and through the default stratum when it does not. Nothing this compiler
 * emits declares one -- checked with `javap -v` across a whole build, project classes and library
 * classes alike -- so this session exercises the second path only. The proof is a mutation: renaming
 * the `Flix` stratum constant leaves this test green. The SMAP branch stays covered by stubs, which
 * is what stubs are for when the shape cannot be produced.
 *
 * **Effects.** The fixture has none, deliberately, so that what is asserted here is line binding
 * rather than the trampoline. The continuation reconstruction is asserted to stay silent, which is
 * the only claim a program without effects can make about it.
 *
 * **The gesture.** Pressing Debug, and which evaluator the IDE picks per frame, are above JDI.
 *
 * ## Why it skips rather than fails
 *
 * It needs a compiler, and a compiler is not something the build can produce: `FLIX_JAR`, or
 * `flix.jar` in the repository root. Without one the test is skipped, and a skip is honest here in
 * a way it is not for the corpus gate -- that one fails under CI because CI *does* clone the corpus,
 * while nothing in this repository builds a Flix compiler. Anyone with a jar runs it by having one.
 */
class FlixDebugSessionTest {

    /** The line the breakpoint goes on. Located by its marker so editing the fixture cannot rot it. */
    private val breakpointMarker = "println"

    /**
     * A program with no effects, so the JVM stack is the call chain and nothing here depends on the
     * trampoline. Four statements, each on its own line, so a wrong line is a wrong assertion rather
     * than a coincidence.
     */
    private val fixture = """
        def main(): Unit \ IO =
            let a = 1;
            let b = a + 2;
            let at = Some("/home/x");
            let xs = -96.0f32 :: -64.0f32 :: -36.0f32 :: Nil;
            println("c = " + "${'$'}{a + b}" + "${'$'}{at}" + "${'$'}{xs}")
    """.trimIndent() + "\n"

    @Test(timeout = SESSION_TIMEOUT_MS)
    fun `a breakpoint on a Flix line binds, hits, and maps back to the source`() {
        session(fixture, breakpointMarker) { vm, stop, line ->
            val hit = stop.location()

            // Counted after the hit rather than before it. A debuggee suspended at start has loaded
            // no class of the program yet -- `allClasses` is JDK classes and nothing else -- which
            // is exactly why the position manager watches class prepares instead of enumerating. By
            // the time the line runs, every class that could hold it has prepared.
            val classes = flixClassesHolding(vm, "Main.flix", line)
            assertEquals(
                "the line should be held by exactly one class; more than one is a compiler defect " +
                    "(gate row 17), and none means the build carried no line numbers: " +
                    classes.map { it.first.name() },
                1,
                classes.size,
            )
            assertEquals("one statement, one location in it", 1, classes.single().second.size)

            // The rules this plugin resolves positions with, asked about a location it did not
            // construct.
            assertTrue("the hit location is not recognised as Flix", FlixSourceLocations.isFlixLocation(hit))
            assertEquals(line, FlixSourceLocations.lineNumberOf(hit))
            // Ends with the file name rather than equalling it, and that is the measurement, not a
            // hedge: with no SMAP the class exposes `SourceFile` verbatim, and the compiler puts an
            // absolute path there -- `/…/flix-debug-session…/src/Main.flix`. `locationsOfLine` is
            // queried with the name JDI reported for exactly this reason, and the label trims it.
            assertTrue(
                "the source name should name the fixture: ${FlixSourceLocations.sourceNameOf(hit)}",
                FlixSourceLocations.sourceNameOf(hit)?.endsWith("Main.flix") == true,
            )

            // And what the frames view will say about it. `Def$main` is a name no one wrote.
            assertEquals("main(), Main.flix:$line", FlixFrames.labelOf(hit).toString())

            // No effects in this program, so there is no trampoline and no continuation list: the
            // JVM stack *is* the call chain. Asserted because the reconstruction runs on every stop,
            // and must return nothing here rather than something.
            assertEquals(emptyList<Any>(), FlixContinuations.callChain(stop.thread(), stop.location().declaringType()))

            // And a value, read out of the frame the way the variables view reads it. `Some` is
            // compiled to a class shared by every one-object case, so this is the assertion that the
            // compiler's `--Xdebug` tag name reaches a reader: without it the best available answer
            // is `#1("/home/x")`.
            assertEquals("Some(\"/home/x\")", labelOf(stop, "at"))

            // And a list, which is a chain of `Cons` cells in the debuggee and read as one until
            // the compiler started recording which enum a case belongs to.
            assertEquals("-96.0 :: -64.0 :: -36.0 :: Nil", labelOf(stop, "xs"))
        }
    }

    /**
     * A program whose calls are effectful, so the chain lives in continuations rather than on the
     * JVM stack: `main` runs `both`, which calls `one` and then `two`, each of which performs `Ask`.
     */
    private val effectfulFixture = """
        eff Ask {
            def ask(): String
        }

        def one(): String \ Ask = Ask.ask()

        def two(): String \ Ask = Ask.ask()

        def both(): String \ Ask =
            let a = one();
            let b = two();
            a + b

        def main(): Unit \ IO =
            run {
                println(both())
            } with handler Ask {
                def ask(resume) = resume("!")
            }
    """.trimIndent() + "\n"

    @Test(timeout = SESSION_TIMEOUT_MS)
    fun `the reconstructed chain names the call each frame is waiting on`() {
        // Stopped in `both`, between its two effectful calls. `main` is on the chain above it, and
        // what it should say is where `main` *is* -- at `println(both())` on line 16, the call it is
        // waiting on -- not at `def main` on line 14, which is where every entry used to point.
        //
        // That position is the continuation's `pc`, and a `pc` is a tableswitch key: only the
        // `pcLines` constant a `--Xdebug` build records makes it readable without disassembling the
        // method.
        session(effectfulFixture, "let b = two()") { _, stop, _ ->
            val provider = FlixAsyncStackTraceProvider()
            val chain = FlixContinuations.callChain(stop.thread(), stop.location().declaringType())
            val entries = chain.mapNotNull { provider.definitionLocation(it) }
                .map { FlixFrames.labelOf(it).toString() }

            assertEquals(listOf("main(), Main.flix:16"), entries)
        }
    }

    @Test(timeout = SESSION_TIMEOUT_MS)
    fun `a chain whose calls have returned is not shown as the present`() {
        // The same stop, and the reason the chain is chosen by its head rather than by its length.
        // Three lists are reachable there -- [main], [both, main] and [one, both, main] -- and the
        // longest begins at `one`, which returned before this line was reached.
        session(effectfulFixture, "let b = two()") { _, stop, _ ->
            val reachable = FlixContinuations.callChain(stop.thread(), stop.location().declaringType())
            val stale = FlixContinuations.callChain(stop.thread(), staleType(stop))

            assertEquals(1, reachable.size)
            assertTrue(
                "a list beginning at `one` should still be reachable, or this proves nothing",
                stale.isNotEmpty(),
            )
        }
    }

    /** The class of `one`, whose captured chain is still on the heap and no longer describes the run. */
    private fun staleType(stop: BreakpointEvent): ReferenceType? =
        stop.virtualMachine().allClasses().firstOrNull { it.name() == "dev.flix.gen.Def\$one" }

    /**
     * A program whose `println` pulls code in from another file of the standard library, which is
     * what makes a class draw on two sources and therefore carry SMAP.
     *
     * It spins afterwards so that it is still running while its classes are inspected. Without that
     * the program is a `println` and a JVM exit, and the scan raced it: the same test passed and
     * then reported that the program had exited before a class with SMAP loaded. The process is
     * killed in `finally` either way.
     */
    private val inliningFixture = """
        def main(): Unit \ IO =
            println(21 + 21);
            spin(2000000000)

        def spin(i: Int32): Unit = if (i <= 0) () else spin(i - 1)
    """.trimIndent() + "\n"

    @Test(timeout = SESSION_TIMEOUT_MS)
    fun `a class built with the optimizer on resolves through the Flix stratum`() {
        // The SMAP branch, against a class that actually carries one.
        //
        // A class has SMAP only when it holds code from more than one file, which only inlining
        // produces -- and `--Xdebug` turns the optimizer off, so a build the plugin launches never
        // has any. Measured: one class in an optimized two-file build declares
        // `SourceDebugExtension`, and **zero** do in the same build with `--Xdebug`. The branch is
        // therefore reachable only when attaching to a program built normally, and this is that.
        val compiler = compilerJar()
        assumeTrue("No compiler jar: set FLIX_JAR, or put flix.jar in the repository root.", compiler != null)

        val project = Files.createTempDirectory("flix-smap-session")
        val source = project.resolve("src/Main.flix")
        source.parent.createDirectories()
        source.writeText(inliningFixture)
        buildOptimized(compiler!!, project)

        val spec = FlixBuildSpec.read(project)
        val port = FlixLaunchCommand.findFreePort()
        val command = FlixLaunchCommand.debugProgram(
            spec.java(), spec.classpath(), requireNotNull(spec.mainClass()), emptyList(), emptyList(), port, true,
        )
        val debuggee = ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start()
        try {
            val vm = attach(port)
            try {
                val inlined = awaitInlinedLocation(vm)

                // What the SMAP is for. The `LineNumberTable` holds a *synthetic* line, allocated
                // in a flat output space above the primary file's last line -- a line that exists
                // in no file -- and the class's `SourceFile` names the file it was declared in, not
                // the file the inlined code came from. The Java stratum is that raw space.
                val rawLine = inlined.lineNumber(RAW_STRATUM)
                val rawSource = inlined.sourceName(RAW_STRATUM)
                val flixLine = FlixSourceLocations.lineNumberOf(inlined)
                val flixSource = FlixSourceLocations.sourceNameOf(inlined)

                assertEquals(FlixSourceLocations.FLIX_STRATUM, FlixSourceLocations.stratumOf(inlined))
                assertTrue("the location should be Flix", FlixSourceLocations.isFlixLocation(inlined))
                assertTrue("no line resolved for $inlined", flixLine != null)
                assertTrue("no source resolved for $inlined", flixSource != null)

                // The raw line is not a line of any file, and the raw source is the wrong file.
                assertTrue(
                    "the mapped line should differ from the raw one, but both are $rawLine",
                    flixLine != rawLine,
                )
                assertEquals("the raw source should be the class's own file", rawSource, inlined.declaringType().sourceName())
                assertTrue(
                    "the inlined code should name its own file, got $flixSource for a class whose " +
                        "SourceFile is $rawSource",
                    flixSource != rawSource,
                )

                // Worth recording: JDI would have resolved this one without being asked, because an
                // SMAP names its own default stratum and the compiler writes `Flix` there -- so
                // `lineNumber()` already answers 69. The stratum handling in the plugin is therefore
                // a *dispatcher*: a class without SMAP has no `Flix` stratum at all, and asking for
                // one throws rather than falling back.
                assertEquals(flixLine, inlined.lineNumber())
            } finally {
                runCatching { vm.dispose() }
            }
        } finally {
            debuggee.destroyForcibly()
            debuggee.waitFor(10, TimeUnit.SECONDS)
        }
    }

    /**
     * A Datalog program, solved and queried -- the shape a query result comes back in.
     *
     * The result of `query … select (x, y)` is a vector of tuples, which is why this is the fixture
     * for tuple rendering: it is where a reader meets one without having written it themselves.
     */
    private val datalogFixture = """
        def main(): Unit \ IO =
            let p = #{
                Edge(1, 2).
                Edge(2, 3).
                Edge(3, 4).
                Path(x, y) :- Edge(x, y).
                Path(x, z) :- Path(x, y), Edge(y, z).
            };
            let resolved = solve p;
            let paths = query resolved select (x, y) from Path(x, y);
            println(paths);
            spin(2000000000)

        def spin(i: Int32): Unit = if (i <= 0) () else spin(i - 1)
    """.trimIndent() + "\n"

    @Test(timeout = SESSION_TIMEOUT_MS)
    fun `a Datalog program reads as the program it is, live`() {
        // `Datalog([], [])`, with `Constraint(HeadAtom, [])` under it: the shape of the AST, with
        // the program nowhere in sight. Everything needed to write it back out survives into the
        // value -- predicate names, variable names, literals -- so it is read back rather than
        // approximated.
        session(datalogFixture, "println(paths)") { _, stop, _ ->
            val program = tagLabel(stop, "p")

            assertTrue("facts are missing from $program", program.contains("Edge(1, 2)."))
            assertTrue("rules are missing from $program", program.contains("Path(x, y) :- Edge(x, y)."))
            assertTrue(
                "a rule with two body atoms is missing from $program",
                program.contains("Path(x, z) :- Path(x, y), Edge(y, z)."),
            )
            assertTrue("the program should read as one: $program", program.startsWith("#{ "))
        }
    }

    @Test(timeout = SESSION_TIMEOUT_MS)
    fun `a solved model reads as the facts it holds, live`() {
        // `Model(Map)` over a red-black tree of B+ trees, whose nodes are structs -- unreadable
        // until `--Xdebug` began recording struct field names, and read by those names now: `keys`,
        // `values`, `size`, `isLeaf`, `next`.
        session(datalogFixture, "println(paths)") { _, stop, _ ->
            val model = tagLabel(stop, "resolved")

            // Asserted whole rather than by `contains`: a walk that reads past a node's `size`
            // picks up the unused slots of a 64-wide array, and every `contains` still passes.
            assertEquals(
                "Model#{ Edge(1, 2). Edge(2, 3). Edge(3, 4). Path(1, 2). Path(1, 3). Path(1, 4). " +
                    "Path(2, 3). Path(2, 4). Path(3, 4). }",
                model,
            )
        }
    }

    /** Temporary hook for shape dumps. */
    fun dumpSession(fixture: String, marker: String, body: (BreakpointEvent) -> Unit) {
        session(fixture, marker) { _, stop, _ -> body(stop) }
    }

    /** A struct, whose class names its fields by position and says nothing else about them. */
    private val structFixture = """
        mod Counter {
            pub struct Counter[r] {
                mut count: Int32,
                label: String
            }

            pub def make(rc: Region[r], n: Int32): Counter[r] \ r =
                new Counter @ rc { count = n, label = "hits" }

            pub def total(c: Counter[r]): Int32 \ r = c->count + String.length(c->label)
        }

        def main(): Unit \ IO =
            region rc {
                let c = Counter.make(rc, 3);
                println(Counter.total(c));
                spin(2000000000)
            }

        def spin(i: Int32): Unit = if (i <= 0) () else spin(i - 1)
    """.trimIndent() + "\n"

    /**
     * A chain of forty nodes, whose closure is 780 facts.
     *
     * A B+ tree node here holds 64 entries, so a relation this size spans several leaves -- which is
     * what makes the leaf chain load-bearing. The three-fact fixture above fits in one leaf, and
     * with it three separate mistakes in the walk (reading fields by position, never following the
     * chain, ignoring a node's `size`) all still passed.
     */
    private val largeDatalogFixture = """
        def main(): Unit \ IO =
            let edges = List.map(i -> (i, i + 1), List.range(1, 40));
            let p = inject edges into Edge/2;
            let rules = #{
                Path(x, y) :- Edge(x, y).
                Path(x, z) :- Path(x, y), Edge(y, z).
            };
            let resolved = solve (p <+> rules);
            let paths = query resolved select (x, y) from Path(x, y);
            println(Vector.length(paths));
            spin(2000000000)

        def spin(i: Int32): Unit = if (i <= 0) () else spin(i - 1)
    """.trimIndent() + "\n"

    @Test(timeout = SESSION_TIMEOUT_MS)
    fun `a relation larger than one tree node is read whole, live`() {
        session(largeDatalogFixture, "println(Vector.length(paths))") { _, stop, _ ->
            val model = local(stop, "resolved") as com.sun.jdi.ObjectReference
            val relations = FlixDatalog.relations(model, Int.MAX_VALUE).first
                .associate { (name, tree) -> name.substringBefore('/') to FlixDatalog.renderRelation(name, tree, 10_000) }

            // 39 edges in a chain of 40, and every pair (i, j) with i < j reachable: 780.
            assertEquals("edges", 39, relations["Edge"]?.size)
            assertEquals("paths", 780, relations["Path"]?.size)
            assertTrue("a fact should read as one: ${relations["Path"]?.first()}",
                relations["Path"]?.all { it.matches(Regex("""Path\(\d+, \d+\)\.""")) } == true)
        }
    }

    /** A closure with a capture, one without, and a lazy value on each side of being forced. */
    private val closureFixture = """
        def curriedMultiply(x: Int32): Int32 -> Int32 = y -> x * y

        def main(): Unit \ IO =
            let multFn = curriedMultiply(6);
            let unforced = lazy (1 + 1);
            let forced = lazy (2 + 2);
            let f = force forced;
            let anon = (y -> y + 1);
            println(multFn(7) + f + anon(1));
            println(force unforced);
            spin(2000000000)

        def spin(i: Int32): Unit = if (i <= 0) () else spin(i - 1)
    """.trimIndent() + "\n"

    @Test(timeout = SESSION_TIMEOUT_MS)
    fun `a function value and a lazy one read as what they are, live`() {
        session(closureFixture, "println(multFn(7)") { _, stop, _ ->
            // `{Clo$curriedMultiply$Xb8Kaq73gD3@3454}` over `clo0`, `pc`, `arg0`.
            assertEquals("fn curriedMultiply(6)", closureLabel(stop, "multFn"))
            // A lambda that captures nothing is still given one capture, of Unit, so that every
            // closure has the same shape. It is not a capture the reader wrote, and dropping it is
            // visible: a kept one reads `fn main(())`, since unit renders as the language writes it.
            assertEquals("fn main()", closureLabel(stop, "anon"))
            assertEquals(emptyList<String>(), captureNames(stop, "anon"))
            assertEquals(listOf("[0]"), captureNames(stop, "multFn"))

            // Which side of `force` a lazy value is on, read without forcing it.
            assertEquals("lazy <unforced>", lazyLabel(stop, "unforced"))
            assertEquals("lazy 4", lazyLabel(stop, "forced"))
        }
    }

    private fun closureLabel(stop: BreakpointEvent, name: String): String =
        (FlixClosureRenderer().valueLabelRenderer as FlixLabelRenderer).label(local(stop, name))

    private fun captureNames(stop: BreakpointEvent, name: String): List<String> =
        (FlixClosureRenderer().childrenRenderer as FlixChildrenRenderer).childrenOf(local(stop, name)).map { it.first }

    private fun lazyLabel(stop: BreakpointEvent, name: String): String =
        (FlixLazyRenderer().valueLabelRenderer as FlixLabelRenderer).label(local(stop, name))

    @Test(timeout = SESSION_TIMEOUT_MS)
    fun `a struct reads with the names its fields were given, live`() {
        // `{Struct$Int32$Obj@3596}` over `field0` and `field1`: the class is shared by every struct
        // of the same erased shape, so it says how many fields there are and nothing else. The
        // names come from the value, recorded there by `--Xdebug`.
        session(structFixture, "println(Counter.total(c))") { _, stop, _ ->
            val struct = local(stop, "c")
            val renderer = FlixStructRenderer()

            assertEquals("""Counter { count = 3, label = "hits" }""", (renderer.valueLabelRenderer as FlixLabelRenderer).label(struct))
            assertEquals(
                listOf("count", "label"),
                (renderer.childrenRenderer as FlixChildrenRenderer).childrenOf(struct).map { it.first },
            )
        }
    }

    /** The value of the local `name` in the frame that stopped. */
    private fun local(stop: BreakpointEvent, name: String): Value? {
        val frame = stop.thread().frame(0)
        val variable = frame.visibleVariableByName(name)
        if (variable == null) {
            fail("no local named `$name`; locals: " + frame.visibleVariables().map { it.name() })
        }
        return frame.getValue(variable)
    }

    @Test(timeout = SESSION_TIMEOUT_MS)
    fun `a tuple reads as a tuple, live`() {
        session(datalogFixture, "println(paths)") { _, stop, _ ->
            // `paths` is a vector, so what is asserted is one of its elements: the vector node
            // itself is the platform's array rendering, which is already legible.
            val element = firstElement(stop, "paths")

            assertEquals("(1, 2)", label(element))
        }
    }

    /** The first element of the array-valued local `name`, as the variables view would show it. */
    private fun firstElement(stop: BreakpointEvent, name: String): Value? {
        val frame = stop.thread().frame(0)
        val variable = frame.visibleVariableByName(name)
        if (variable == null) {
            fail("no local named `$name`; locals: " + frame.visibleVariables().map { it.name() })
        }
        val array = frame.getValue(variable) as? com.sun.jdi.ArrayReference
            ?: throw AssertionError("`$name` is not an array: ${frame.getValue(variable)}")
        assertTrue("`$name` is empty, so there is nothing to render", array.length() > 0)
        return array.getValue(0)
    }

    /** What the variables view would label `value`, through the renderer that claims it. */
    private fun label(value: Value?): String {
        val renderer = FlixTupleRenderer().valueLabelRenderer as FlixLabelRenderer
        return renderer.label(value)
    }

    /** Phase one without `--Xdebug`, so the optimizer runs and inlining happens. */
    private fun buildOptimized(jar: Path, project: Path) {
        val command = FlixLaunchCommand.task(javaExecutable(), jar, "build", emptyList(), listOf("--yes"))
        val process = ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("the build did not finish", process.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES))
        assertEquals("the fixture did not compile:\n$output", 0, process.exitValue())
    }

    /**
     * Runs the program until a class carrying SMAP prepares, and returns a location of the code
     * that was inlined into it.
     *
     * The inlined lines are the ones the SMAP allocated above the primary file's line count, so
     * they are exactly the locations whose Flix source name differs from the class's own.
     */
    private fun awaitInlinedLocation(vm: VirtualMachine): Location {
        val prepare = vm.eventRequestManager().createClassPrepareRequest()
        prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
        prepare.enable()
        vm.resume()

        val deadline = System.currentTimeMillis() + HIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val events = vm.eventQueue().remove(POLL_MS) ?: continue
            for (event in events) {
                if (event is VMDisconnectEvent) throw AssertionError("the program exited before a class with SMAP loaded")
                val type = (event as? ClassPrepareEvent)?.referenceType() ?: continue
                if (runCatching { type.availableStrata().contains(FlixSourceLocations.FLIX_STRATUM) }.getOrDefault(false)) {
                    val inlined = runCatching { type.allLineLocations() }.getOrDefault(emptyList())
                        .firstOrNull { FlixSourceLocations.sourceNameOf(it) != type.sourceName() }
                    if (inlined != null) return inlined
                }
            }
            events.resume()
        }
        throw AssertionError("no class carrying SMAP prepared")
    }

    /** A map, a set and an empty map, which are red-black trees in the debuggee. */
    private val collectionFixture = """
        def main(): Unit \ IO =
            let m = Map#{1 => "one", 2 => "two", 3 => "three"};
            let s = Set#{10, 20, 30};
            let empty = (Map#{}: Map[Int32, String]);
            println(Map.size(m) + Set.size(s) + Map.size(empty));
            spin(2000000000)

        def spin(i: Int32): Unit = if (i <= 0) () else spin(i - 1)
    """.trimIndent() + "\n"

    @Test(timeout = SESSION_TIMEOUT_MS)
    fun `a map and a set read as they are written, live`() {
        // Against real trees rather than a stubbed one: the walk depends on the standard library's
        // own field order -- `Node(colour, left, key, value, right)` -- which is measured, not
        // specified, and a stub would only repeat the measurement back.
        session(collectionFixture, "spin(2000000000)") { _, stop, _ ->
            assertEquals("""Map#{1 => "one", 2 => "two", 3 => "three"}""", tagLabel(stop, "m"))
            assertEquals("Set#{10, 20, 30}", tagLabel(stop, "s"))
            assertEquals("Map#{}", tagLabel(stop, "empty"))
        }
    }

    /** What the variables view would label the local `name`, through the tagged renderer. */
    private fun tagLabel(stop: BreakpointEvent, name: String): String {
        val frame = stop.thread().frame(0)
        val variable = frame.visibleVariableByName(name)
        if (variable == null) {
            fail("no local named `$name`; locals: " + frame.visibleVariables().map { it.name() })
        }
        val renderer = FlixTaggedRenderer().valueLabelRenderer as FlixLabelRenderer
        return renderer.label(frame.getValue(variable))
    }

    /**
     * Compiles `fixture`, launches it under a debugger, stops at the line carrying `marker`, and
     * runs `assertions` there.
     */
    private fun session(
        fixture: String,
        marker: String,
        assertions: (VirtualMachine, BreakpointEvent, Int) -> Unit,
    ) {
        val compiler = compilerJar()
        assumeTrue("No compiler jar: set FLIX_JAR, or put flix.jar in the repository root.", compiler != null)

        val project = Files.createTempDirectory("flix-debug-session")
        val source = project.resolve("src/Main.flix")
        source.parent.createDirectories()
        source.writeText(fixture)
        val line = lineOf(source, marker)

        build(compiler!!, project)
        val spec = FlixBuildSpec.read(project)
        val mainClass = requireNotNull(spec.mainClass()) { "the build manifest names no main class" }

        val port = FlixLaunchCommand.findFreePort()
        // suspend=y, exactly as a session does: a breakpoint on the first line has to be armed
        // before the program runs any of its own code.
        val command = FlixLaunchCommand.debugProgram(
            spec.java(), spec.classpath(), mainClass, emptyList(), emptyList(), port, true,
        )
        val debuggee = ProcessBuilder(command)
            .directory(project.toFile())
            .redirectErrorStream(true)
            .start()

        try {
            val vm = attach(port)
            try {
                assertions(vm, stopAt(vm, "Main.flix", line), line)
            } finally {
                runCatching { vm.dispose() }
            }
        } finally {
            debuggee.destroyForcibly()
            debuggee.waitFor(10, TimeUnit.SECONDS)
        }
    }

    /** Phase one: `flix build --Xdebug --yes`, which is the JVM the *compiler* runs in. */
    private fun build(jar: Path, project: Path) {
        val command = FlixLaunchCommand.buildForDebug(javaExecutable(), jar, null)
        val process = ProcessBuilder(command)
            .directory(project.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("the build did not finish within $BUILD_TIMEOUT_MINUTES minutes", process.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES))
        assertEquals("the fixture did not compile:\n$output", 0, process.exitValue())
        assertTrue(
            "the build produced no manifest, so there is nothing to launch:\n$output",
            FlixBuildSpec.manifestIn(project).exists(),
        )
    }

    /**
     * The classes compiled from [sourceName] that hold [line], with the locations in them.
     *
     * The same two questions `FlixPositionManager.getAllClasses` asks, in the same order: the cheap
     * source test first, and the line query only for what survives it.
     */
    private fun flixClassesHolding(
        vm: VirtualMachine,
        sourceName: String,
        line: Int,
    ): List<Pair<ReferenceType, List<Location>>> =
        vm.allClasses().mapNotNull { type ->
            locationsIn(type, sourceName, line).takeIf { it.isNotEmpty() }?.let { type to it }
        }

    /**
     * Arms a breakpoint on [sourceName]:[line] and returns where the program stopped.
     *
     * Through a class-prepare watch, because that is the only way a breakpoint set before the run
     * can bind: nothing of the program is loaded while the debuggee waits at `suspend=y`, and no
     * class-name pattern could narrow the watch either -- the back end chooses names like
     * `Clo$main$626ZYxrpg1N`. `FlixPositionManager.createPrepareRequests` says the same at length,
     * and this is that arrangement carried out for real.
     */
    private fun stopAt(vm: VirtualMachine, sourceName: String, line: Int): BreakpointEvent {
        val prepare = vm.eventRequestManager().createClassPrepareRequest()
        prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
        prepare.enable()
        vm.resume()

        var armed = false
        val deadline = System.currentTimeMillis() + HIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val events = vm.eventQueue().remove(POLL_MS) ?: continue
            for (event in events) {
                when (event) {
                    is BreakpointEvent -> return event
                    is VMDisconnectEvent ->
                        throw AssertionError("the program exited without hitting the breakpoint")
                    is ClassPrepareEvent -> {
                        val locations = locationsIn(event.referenceType(), sourceName, line)
                        if (locations.isNotEmpty()) {
                            val request = vm.eventRequestManager().createBreakpointRequest(locations.first())
                            request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                            request.enable()
                            armed = true
                        }
                    }
                    else -> Unit
                }
            }
            events.resume()
        }
        throw AssertionError(
            if (armed) {
                "a breakpoint was armed on $sourceName:$line and never hit"
            } else {
                "no class holding $sourceName:$line ever prepared, so nothing could be armed"
            },
        )
    }

    /**
     * How the variables view would label the local named [name] in the frame that stopped.
     *
     * Through the renderer's own label path, so what is asserted is what a reader sees rather than
     * a rule the renderer happens to call. The local is visible at all because `--Xdebug` emits a
     * `LocalVariableTable`; without one there is no name here to ask for.
     */
    private fun labelOf(stop: BreakpointEvent, name: String): String {
        val frame = stop.thread().frame(0)
        val variable = frame.visibleVariableByName(name)
        if (variable == null) {
            fail(
                "no local named `$name` is visible in ${stop.location()}; locals: " +
                    frame.visibleVariables().map { it.name() },
            )
        }
        val renderer = FlixTaggedRenderer().valueLabelRenderer as FlixLabelRenderer
        return renderer.label(frame.getValue(variable))
    }

    /** The locations in [type] that implement [sourceName]:[line], or none if it holds no such line. */
    private fun locationsIn(type: ReferenceType, sourceName: String, line: Int): List<Location> {
        val stratum = FlixSourceLocations.stratumFor(type) ?: return emptyList()
        val names = FlixSourceLocations.flixSourcesOf(type, stratum)
            .map { it.first }
            .filter { FlixSourceLocations.couldReferToBaseName(it, sourceName) }
        return names.flatMap { name ->
            runCatching { type.locationsOfLine(stratum, name, line) }.getOrElse { emptyList() }
        }.distinct()
    }

    /**
     * Attaches to the suspended debuggee.
     *
     * Retried rather than attempted once: the JVM binds its socket a moment after the process
     * starts, and a single attempt is a race the test would lose intermittently.
     */
    private fun attach(port: Int): VirtualMachine {
        val connector = Bootstrap.virtualMachineManager().attachingConnectors()
            .first { it.name() == "com.sun.jdi.SocketAttach" }
        val arguments = connector.defaultArguments()
        arguments["hostname"]!!.setValue("localhost")
        arguments["port"]!!.setValue(port.toString())

        val deadline = System.currentTimeMillis() + ATTACH_TIMEOUT_MS
        var failure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            runCatching { connector.attach(arguments) }
                .onSuccess { vm ->
                    // Drained so the queue starts empty; a suspended VM has a VMStart waiting, and
                    // leaving it there would be read as the breakpoint's event set later.
                    vm.eventQueue().remove(POLL_MS)?.let { set ->
                        if (set.none { it is VMStartEvent }) set.resume()
                    }
                    return vm
                }
                .onFailure { failure = it }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("could not attach to the debuggee on port $port", failure)
    }

    /** `FLIX_JAR`, or `flix.jar` in the repository root -- the same two the plugin itself accepts. */
    private fun compilerJar(): Path? {
        val configured = System.getenv("FLIX_JAR")?.takeIf { it.isNotBlank() }?.let(Path::of)
        if (configured != null && configured.exists()) return configured
        val inRoot = repositoryRoot().resolve("flix.jar")
        return inRoot.takeIf { it.exists() }
    }

    /** The module runs from `debugger/`, so the repository is its parent. */
    private fun repositoryRoot(): Path = Path.of("").toAbsolutePath().let { cwd ->
        if (cwd.fileName?.toString() == "debugger") cwd.parent else cwd
    }

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").absolutePathString()

    private fun lineOf(source: Path, marker: String): Int {
        val index = source.readText().lines().indexOfFirst { it.contains(marker) }
        assertTrue("the fixture no longer contains `$marker`", index >= 0)
        // JDI line numbers are one-based.
        return index + 1
    }

    private companion object {
        /** A cold build resolves dependencies before it compiles anything. */
        private const val BUILD_TIMEOUT_MINUTES = 10L
        private const val ATTACH_TIMEOUT_MS = 60_000L
        private const val HIT_TIMEOUT_MS = 60_000L
        private const val POLL_MS = 200L
        private const val SESSION_TIMEOUT_MS = 15 * 60 * 1000L

        /** JDI's name for the unmapped line space -- what the `LineNumberTable` literally holds. */
        private const val RAW_STRATUM = "Java"
    }
}
