package dev.wstein.flixplugin.ui

import com.intellij.driver.client.service
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.toggleLineBreakpoint
import com.intellij.driver.sdk.ui.components.common.gutter
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration.Companion.minutes

/**
 * The gesture, which is the one thing no probe can reach.
 *
 * `scripts/FlixDebugProbe.java` covers everything a debug session does after launch — bind, hit,
 * mixed stacks, exceptions, detach — against the same command line the run configuration builds.
 * What it cannot do is press the button. That gap is not cosmetic: of the three defects found in
 * the run configuration so far, two made a debug session impossible and were found by review rather
 * than by use, because nothing exercised the path.
 *
 * Deliberately small, per the plan. The parser corpus, SMAP variants, exception breakpoints, split
 * mode and the Kotlin/Scala/Groovy profiles all have focused tests with their own diagnostics;
 * folding them into one UI script would trade precise failures for an opaque one.
 *
 * ## What running this costs
 *
 * A real IDE against a real Swing UI, driven by an AWT robot. It is not headless: on macOS it takes
 * over the cursor, and on Linux CI it needs `xvfb` **and** a window manager (`fluxbox`) — with a
 * display but no WM, window geometry misbehaves. That is why it is wired to its own `testIdeUi`
 * task rather than into `check`.
 */
class FlixUiSmokeTest {

    /**
     * Line numbers in `flix-fixture/src/Main.flix`, **0-based** -- the driver counts from zero while
     * the editor gutter displays from one, so these are one less than what the screenshot shows.
     *
     * Asserting on positions rather than searching for them by content: a search that silently
     * matched a different line would report success for the wrong thing, which is the failure mode
     * this suite has a history of.
     */
    private val defMainLine = 5
    private val printlnLine = 7

    @Test
    fun `exactly one run marker sits beside def main`() {
        // What this catches is the marker going *missing*, which is a live failure mode: when the
        // `backend` module failed to load during this test's first run, the gutter was simply empty
        // and the IDE reported nothing.
        //
        // It does **not** catch the duplicate arrow, despite the "exactly one" shape. Both a
        // repeated registration in one descriptor and the historical cross-module pair were injected
        // here and both still rendered a single icon -- the platform merges markers at one offset.
        // `checkIntegrationGlue` remains the only thing that detects duplicate registration, and it
        // does so structurally. Saying otherwise would leave a comment claiming coverage that a
        // fault injection disproves.
        runInIde { driver ->
            driver.openFile("src/Main.flix")
            driver.ideFrame {
                // The driver reports lines 0-based while the editor shows them 1-based, so the
                // whole-file assertion carries the diagnosis: the fixture has exactly one runnable
                // declaration, so one marker in the file *is* one marker on `def main`, and the
                // message names where it actually landed when that stops being true.
                val icons = gutter().getGutterIcons()
                val where = icons.map { "line ${it.line}: ${it.getIconPath()}" }
                assertEquals(1, icons.size, "expected exactly one gutter marker in the file, got $where")
                assertEquals(
                    defMainLine,
                    icons.single().line,
                    "the marker is on the wrong line: $where",
                )
            }
        }
    }

    @Test
    @Disabled(
        "Unfinished, and the reason is recorded rather than guessed at again. Two routes were " +
            "tried. Reading XBreakpointManager through a @Remote stub fails inside the remote call " +
            "with no indication of which method name is wrong -- those names are typed against the " +
            "platform and checked only at call time. Reading the gutter instead shows no breakpoint " +
            "icon after toggleLineBreakpoint, and the icon count read before the toggle disagreed " +
            "with the count read after (2 then 1), so the reading is not stable either. Unresolved: " +
            "whether toggleLineBreakpoint's line argument is 0- or 1-based, whether it resolves " +
            "\"src/Main.flix\" the same way openFile does, and whether getGutterIcons reports " +
            "breakpoint icons at all or only line markers. Settle those against a live IDE first.",
    )
    fun `a breakpoint can be placed on an executable Flix line`() {
        // Until `FlixJavaDebugAware` claimed `.flix`, `JavaLineBreakpointType.canPutAtElement`
        // refused every line in the file -- which also made the position manager unreachable, since
        // nothing ever asked it to resolve anything. This is that claim, exercised as a gesture.
        //
        // Asserted through the gutter rather than through `XBreakpointManager`. A `@Remote` stub
        // would read the model directly, but its method names are typed against the platform and
        // checked only at call time -- the first attempt here failed inside the remote call with no
        // indication of which name was wrong. The gutter is what the user actually sees, and the
        // icon appearing there is the same claim with none of the guesswork.
        runInIde { driver ->
            driver.openFile("src/Main.flix")

            var before = 0
            driver.ideFrame { before = gutter().getGutterIcons().size }
            driver.toggleLineBreakpoint("src/Main.flix", printlnLine)

            driver.ideFrame {
                val icons = gutter().getGutterIcons()
                val where = icons.map { "line ${it.line}: ${it.getIconPath()}" }
                assertEquals(
                    before + 1,
                    icons.size,
                    "toggling a breakpoint added no gutter icon, so the line was refused: $where",
                )
                assertTrue(
                    icons.any { it.line == printlnLine },
                    "an icon was added but not on the toggled line: $where",
                )
            }
        }
    }

    @Test
    @Disabled(
        "Blocked on the breakpoint test above, and on one more unknown of its own. driver-sdk has " +
            "no session API at all -- its only debugger entry point is XDebuggerUtil." +
            "toggleLineBreakpoint -- so asserting that a session started needs the same @Remote " +
            "stubs that are still unverified. The click-then-Down-then-Enter path through the " +
            "executor popup is also unconfirmed: the popup's entry order is assumed, not read.",
    )
    fun `the gutter's Debug action starts a native debug session`() {
        // The row that has never been run. Clicking the marker rather than invoking the action by ID
        // is the point: an `invokeAction` would bypass `FlixRunLineMarkerContributor` and the
        // configuration producer, which is precisely where the defects were.
        runInIde { driver ->
            driver.openFile("src/Main.flix")
            driver.toggleLineBreakpoint("src/Main.flix", printlnLine)

            driver.ideFrame {
                // Click the marker itself rather than `clickLineMarkerAtLine`, whose match is keyed
                // on an accessible name this contributor does not set. The click opens the executor
                // popup; Debug is its second entry, so one Down and Enter selects it.
                val marker = gutter().getGutterIcons().single { it.line == defMainLine }
                marker.click()
                keyboard {
                    down()
                    enter()
                }
            }

            val manager = driver.service<XDebuggerManagerRef>(driver.singleProject())
            waitUntil("a debug session starts", 3.minutes) {
                manager.getDebugSessions().isNotEmpty()
            }
            waitUntil("the session suspends at the breakpoint", 10.minutes) {
                manager.getDebugSessions().any { it.isPaused() }
            }
        }
    }

    // --- harness -----------------------------------------------------------------------------

    private fun runInIde(body: (com.intellij.driver.client.Driver) -> Unit) {
        val fixture = prepareFixture(requireCompilerJar())
        Starter.newContext(
            testName = "flixUiSmoke",
            // `isReusable = false` on purpose. Flix caches resolved jars into `lib/external/` and
            // does not invalidate them, so a reused fixture would carry stale state between runs --
            // the exact shape of false negative that has already cost this project a round.
            testCase = TestCase(IdeProductProvider.IU, LocalProjectInfo(fixture, isReusable = false))
                .useRelease(IDE_VERSION),
        ).apply {
            PluginConfigurator(this)
                // LSP4IJ first, and from the Marketplace rather than the Gradle sandbox: Starter
                // builds its own IDE installation under `out/ide-tests`, so `testIdeUi { plugins {} }`
                // configures a sandbox this run never reads. Without it the `backend` content module
                // is skipped -- the IDE logs one line and carries on, so the language layer still
                // works while the gutter marker and the LSP session are silently absent.
                .installPluginFromPath(Path.of(requiredProperty(LSP4IJ_PROPERTY)))
                .installPluginFromPath(Path.of(builtPluginZip()))
                .assertPluginIsInstalled(LSP4IJ_ID)
        }.runIdeWithDriver().useDriverAndCloseIde {
            // Both waits are needed, and in this order. The driver connects while the IDE is still
            // on its splash screen, so asking for `singleProject()` first fails with "No projects
            // are opened" -- a message that reads like a broken fixture rather than a race.
            waitForProjectOpen(timeout = 5.minutes)
            waitForIndicators(timeout = 5.minutes)
            body(this)
        }
    }

    /**
     * A private copy of the fixture with a compiler jar beside it.
     *
     * Copied rather than used in place so a run cannot write build output into the source tree, and
     * so the jar -- which is large and machine-specific -- never has to be committed.
     */
    private fun prepareFixture(compiler: Path): Path {
        val source = Path.of("src/integrationTest/resources/flix-fixture")
        val target = Path.of("build/uiFixture").also { it.createDirectories() }
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        source.copyToRecursively(target, followLinks = false, overwrite = true)
        java.nio.file.Files.copy(
            compiler,
            target.resolve("flix-vendor-uitest.jar"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        return target.toAbsolutePath()
    }

    /** [BUILT_PLUGIN_PROPERTY] is set by `TestIdeUiTask` itself; the rest by this build. */
    private fun requiredProperty(name: String): String =
        checkNotNull(System.getProperty(name)) {
            "$name is unset -- run this through the `testIdeUi` task, which sets it"
        }

    private fun builtPluginZip(): String = requiredProperty(BUILT_PLUGIN_PROPERTY)

    /**
     * The compiler jar, or a failure naming how to supply one.
     *
     * Required by every test here, not only the debug one. Without it the language server cannot
     * start, and the IDE then never finishes code analysis -- so `openFile` times out waiting for a
     * daemon that will never settle, and the failure points at the editor rather than at the
     * missing compiler. Failing here instead says what is actually wrong.
     */
    private fun requireCompilerJar(): Path =
        checkNotNull(resolveCompilerJar()) {
            "No Flix compiler jar found. Set $PINNED_JAR_ENV, or place a flix-vendor-*.jar in the " +
                "repository root. Without one the language server cannot start and no UI assertion " +
                "here is meaningful."
        }

    /** The same resolution order the plugin itself uses, so the test cannot disagree with it. */
    private fun resolveCompilerJar(): Path? {
        System.getenv(PINNED_JAR_ENV)?.takeIf { it.isNotBlank() }?.let { pinned ->
            return Path.of(pinned).takeIf { it.exists() }
        }
        val root = Path.of(".").toAbsolutePath().normalize()
        return root.takeIf { it.isDirectory() }
            ?.listDirectoryEntries("flix-vendor-*.jar")
            ?.maxByOrNull { it.fileName.toString() }
    }

    private fun waitUntil(what: String, timeout: kotlin.time.Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out after $timeout waiting for: $what")
    }

    private companion object {
        const val IDE_VERSION = "2026.1.3"

        const val LSP4IJ_ID = "com.redhat.devtools.lsp4ij"

        /** Set by the `testIdeUi` task from the `lsp4ijDistribution` configuration. */
        const val LSP4IJ_PROPERTY = "path.to.lsp4ij"
        const val PINNED_JAR_ENV = "FLIX_FORK_JAR"
        const val BUILT_PLUGIN_PROPERTY = "path.to.build.plugin"
        const val POLL_MS = 500L
    }
}
