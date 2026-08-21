package de.wstein.flixplugin

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * That the bundled plugin which breaks this suite is not in the test IDE.
 *
 * ## The failure
 *
 * IU bundles the Vue plugin, whose LSP activation rule fails to initialise in a headless test IDE:
 *
 * ```
 * Cannot create extension (class=org.jetbrains.vuejs.…lsp.VueLspServerSupportProvider)
 *   … Could not initialize class …VueLspServerActivationRule
 *   … java.lang.ExceptionInInitializerError [in thread "ApplicationImpl pooled thread 1"]
 * ```
 *
 * `LspOpenedFilesService` enumerates every `LspServerSupportProvider` when a file is opened, so any
 * `BasePlatformTestCase` sets it off. The error is logged on a **pooled thread**, and
 * `TestLoggerFactory` turns a logged error into a failure of whichever test happens to be running —
 * which is why it landed on a different test each run, and why rerunning that test alone passed.
 * Observed in two of six full runs.
 *
 * ## Why disabling it is the fix rather than a workaround
 *
 * Nothing in this plugin touches the platform LSP API; it uses LSP4IJ (ADR 0001). The Vue plugin has
 * no part in anything this suite tests, and the platform offers `disabled_plugins.txt` for exactly
 * this. It is scoped to the *test* sandbox, wired in `prepareTestSandbox`: the IDEs launched by
 * `runIde`, `runIdeSplitMode` and `testIdeUi` keep every bundled plugin, because a developer looking
 * at a real IDE should see the real set.
 *
 * The alternative the platform offers is `idea.load.plugins.id`, an **allowlist** — which would mean
 * enumerating every plugin these tests need and re-enumerating it on every platform bump.
 *
 * ## Why this test exists
 *
 * The build asks for a plugin by id. An id that stops matching — a rename, a bundling change, a
 * property the framework starts ignoring — leaves that request silently doing nothing, and the flake
 * comes back as an unrelated test failing once a fortnight. This turns that into one honest failure
 * with the reason attached.
 */
class FlixTestSandboxTest : BasePlatformTestCase() {

    fun testTheVuePluginIsNotLoadedInTheTestIde() {
        val vue = PluginManagerCore.getPlugin(PluginId.getId(VUE_PLUGIN_ID))

        assertTrue(
            "the Vue plugin is loaded in the test IDE, so its LSP activation rule can fail on a " +
                "pooled thread and be reported as a failure of an unrelated test. Check that " +
                "prepareTestSandbox still disables '$VUE_PLUGIN_ID' and that the id is still right.",
            vue == null || !vue.isEnabled,
        )
    }

    fun testTheDisablingMechanismStillWorksAtAll() {
        // Without this, the test above passes when `getPlugin` starts answering null for everything
        // -- an id typo, an API change, a test IDE with no plugins at all. A plugin this suite
        // genuinely depends on must still be loaded and enabled.
        val java = PluginManagerCore.getPlugin(PluginId.getId("com.intellij.java"))

        assertNotNull("the Java plugin is not loaded, so this check cannot tell disabled from absent", java)
        assertTrue("the Java plugin is loaded but disabled", java!!.isEnabled)
    }

    private companion object {
        /** As it appears in the Vue plugin's own descriptor, and in `disabled_plugins.txt`. */
        const val VUE_PLUGIN_ID = "org.jetbrains.plugins.vue"
    }
}
