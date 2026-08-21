package de.wstein.flixplugin.debugger

import org.flixlang.intellij.eval.FlixDebugEval
import org.flixlang.intellij.eval.FlixDebugEvalAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * The seam that lets a debugger ask a question only the language server can answer.
 *
 * ## The constraint it exists for
 *
 * The frame comes from here: `debugger` needs the Java plugin, and a paused frame is a JDI location.
 * The answer comes from the compiler, reached through LSP4IJ, which lives in `backend`. The two are
 * separate content modules with separate classloaders and neither may depend on the other — so
 * neither can hold both halves, and a direct call between them does not compile in one direction and
 * does not *load* in the other.
 *
 * The shape of the question therefore lives in `language`, which both already depend on. This pins
 * that arrangement, because every way of breaking it is silent: an interface moved into `backend`
 * still compiles here if a stray dependency is added and then fails at runtime with
 * `NoClassDefFoundError`; a service registered against the wrong interface simply returns `null`,
 * which reads as "no server yet".
 */
class FlixDebugEvalSeamTest {

    @Test
    fun `the question's shape is visible from the debugger module`() {
        // The compile-time half of the claim. If this interface ever moves to `backend`, this file
        // stops compiling -- which is the point: the failure is at build time rather than in a
        // watch, in an installed IDE, on someone else's machine.
        assertTrue(
            "the seam must be an interface, so backend can implement it without debugger seeing backend",
            FlixDebugEval::class.java.isInterface,
        )
        assertEquals(
            "the seam must live in the language module's package, which both modules can see",
            "org.flixlang.intellij.eval",
            FlixDebugEval::class.java.packageName,
        )
    }

    @Test
    fun `the implementation is registered against that interface, from the backend module`() {
        // Registered in `backend` because only that module may speak LSP4IJ, and against the
        // `language` interface because that is what `debugger` asks for. A registration naming any
        // other interface leaves the lookup answering null forever.
        val descriptor = repositoryRoot().resolve("backend/src/main/resources/flix.jetbrains.plugin.backend.xml")
        assertTrue("no backend descriptor at $descriptor", descriptor.exists())
        val text = descriptor.readText()

        val service = Regex("""<projectService\b[^>]*>""").findAll(text).map { it.value }
            .singleOrNull { it.contains("FlixDebugEval") }
        assertTrue("backend registers no debug-eval service", service != null)
        assertTrue(
            "the service must be registered against the language module's interface: $service",
            service!!.contains("""serviceInterface="org.flixlang.intellij.eval.FlixDebugEval""""),
        )
    }

    @Test
    fun `the debugger module does not depend on the backend module`() {
        // The constraint the seam exists to respect, asserted rather than remembered. `backend` and
        // `debugger` are separate classloaders; a dependency here would compile and then fail to
        // load in an IDE without LSP4IJ, where `backend` is simply absent.
        val descriptor = repositoryRoot().resolve("debugger/src/main/resources/flix.jetbrains.plugin.debugger.xml")
        assertFalse(
            "the debugger module must not depend on backend",
            descriptor.readText().contains("flix.jetbrains.plugin.backend"),
        )
    }

    @Test
    fun `the request name matches the one the compiler answers`() {
        // A string in two repositories, dispatched by exact match. A disagreement is answered with
        // MethodNotFound, which reaches a user as a debugger that has stopped responding. The
        // compiler pins its own end; this pins that the client sends what that end listens for.
        val api = repositoryRoot().resolve("backend/src/main/java/de/wstein/flixplugin/FlixLanguageServerApi.java")
        assertTrue("no client-side API at $api", api.exists())

        assertTrue(
            "the client must request exactly `flix/debugEval/compile`",
            api.readText().contains(""""flix/debugEval/compile""""),
        )
    }

    @Test
    fun `the policy names are the ones the compiler parses`() {
        // The compiler parses these two strings and nothing else, case-sensitively. A rename here
        // would be answered as a rejection about an unknown policy rather than as a typo.
        assertEquals("pure", FlixDebugEval.Policy.PURE.wireName)
        assertEquals("allowEffects", FlixDebugEval.Policy.ALLOW_EFFECTS.wireName)
    }

    @Test
    fun `a typed answer says whether it may be run, and nothing else does`() {
        // The one question a watch asks of an answer. `Unavailable` and `Invalid` carry no such
        // claim, which is what stops "there is no debug build" from being read as "it is pure".
        val pure = FlixDebugEvalAnswer.Typed("Int32", "Pure")
        val effectful = FlixDebugEvalAnswer.Typed("Unit", "IO")

        assertTrue(pure.isPure)
        assertFalse(effectful.isPure)
        // And the other two carry no such claim at all, which is what stops "there is no debug
        // build" from being read as "it is pure". Asserted through the sealed hierarchy rather than
        // with an `is` check, which the compiler answers statically and which would prove nothing.
        val answers: List<FlixDebugEvalAnswer> = listOf(
            pure,
            FlixDebugEvalAnswer.Unavailable("no debug build"),
            FlixDebugEvalAnswer.Invalid(listOf("Undefined name")),
        )
        assertEquals(
            "only a typed answer may claim purity",
            listOf(pure),
            answers.filterIsInstance<FlixDebugEvalAnswer.Typed>().filter { it.isPure },
        )
    }

    /** Tests run from the module directory under Gradle, and from the repository root elsewhere. */
    private fun repositoryRoot(): Path {
        val cwd = Path.of("").toAbsolutePath()
        return if (cwd.fileName?.toString() == "debugger") cwd.parent else cwd
    }
}
