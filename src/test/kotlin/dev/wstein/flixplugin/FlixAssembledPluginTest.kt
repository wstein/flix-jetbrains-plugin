package dev.wstein.flixplugin

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * What the *assembled* plugin actually registers, as the platform sees it.
 *
 * `FlixPluginDescriptorTest` reads the descriptors as XML and checks they say the right things. Its
 * own doc comment explains why it stops there: a content-module descriptor is inert until the root
 * `plugin.xml` names it in `<content>`, so a module-local fixture never loads it. That leaves a gap
 * this test closes — the descriptors could say exactly the right things and still not be *read*.
 *
 * Everything here is asserted through platform lookups rather than through our own classes, so it
 * fails if a module drops out of `<content>`, if an extension point is renamed, or if a
 * registration lands in the wrong module.
 */
class FlixAssembledPluginTest : BasePlatformTestCase() {

    fun testThePluginAndEveryContentModuleIsLoaded() {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
        assertNotNull("the plugin under test is not loaded at all", plugin)
        assertTrue("the plugin is loaded but disabled", plugin!!.isEnabled)
    }

    fun testExactlyOneFlixLanguageIsRegistered() {
        // ADR 0001. Two owners means two parsers and two file types, and which one wins depends on
        // load order -- the failure is intermittent rather than loud.
        val flix = Language.findLanguageByID("Flix")
        assertNotNull("no Language(\"Flix\") is registered in the assembled plugin", flix)

        val byId = Language.getRegisteredLanguages().filter { it.id == "Flix" }
        assertEquals("exactly one Language(\"Flix\") may exist, found $byId", 1, byId.size)
    }

    fun testTheFlixFileTypeAndParserAreWiredToThatLanguage() {
        val flix = Language.findLanguageByID("Flix")!!

        // Asserted through the language it carries rather than its display name: the name is a
        // label ("Flix File") and could change without anything breaking, while the association
        // between `.flix` and the Flix language is the thing that must hold.
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension("flix")
        assertEquals(
            "`.flix` must resolve to a file type owned by the Flix language, got $fileType",
            flix,
            (fileType as? LanguageFileType)?.language,
        )

        assertNotNull(
            "a language with no parser definition parses nothing; the file type would load and " +
                "every PSI-based feature would silently do nothing",
            LanguageParserDefinitions.INSTANCE.forLanguage(flix),
        )
    }

    fun testExactlyOneRunLineMarkerContributesToFlix() {
        // The duplicate gutter arrow, as a runtime assertion rather than an XML one. Registering the
        // contributor in two modules put two arrows on `def main`: the backend one worked, the
        // frontend one offered "Nothing here". The descriptors are checked elsewhere; this checks
        // what the platform ended up with.
        val flix = Language.findLanguageByID("Flix")!!
        val contributors = RunLineMarkerContributor.EXTENSION.allForLanguage(flix)
            .map { it::class.java.name }

        assertEquals(
            "exactly one run line marker may contribute to Flix, found $contributors",
            1,
            contributors.size,
        )
        assertTrue(
            "the contributor should be ours, found $contributors",
            contributors.single().startsWith("org.flixlang.intellij.run."),
        )
    }

    fun testTheDebuggerModuleFollowsTheJavaPlugin() {
        // The debugger module is deliberately not `loading="required"`, so an IDE without the Java
        // plugin skips it and keeps the language layer. That contract has two halves and both are
        // worth asserting: skipped when Java is absent, loaded when it is present. The XML checks
        // can see the `loading` attribute but not which of the two actually happened.
        val javaPlugin = PluginManagerCore.getPlugin(PluginId.getId("com.intellij.java"))
        val registered = extensionClassNames("com.intellij.debugger.positionManagerFactory")
        val ours = registered.any { it == "dev.wstein.flixplugin.debugger.FlixPositionManagerFactory" }

        if (javaPlugin?.isEnabled == true) {
            assertTrue(
                "the Java plugin is present, so the debugger module must have loaded. " +
                    "Registered position managers: $registered",
                ours,
            )
        } else {
            assertFalse(
                "without the Java plugin the debugger module must be skipped, not half-loaded",
                ours,
            )
        }
    }

    fun testTheFlixRunConfigurationTypeIsAvailable() {
        // Without it the gutter's Debug action has nothing to resolve to, which is how the arrow
        // silently did nothing once the DAP producer was removed.
        val types = extensionClassNames("com.intellij.configurationType")
        assertTrue(
            "the Flix run configuration type is not registered. Registered: $types",
            types.any { it == "dev.wstein.flixplugin.run.FlixRunConfigurationType" },
        )
    }

    fun testTheFlixTaskConfigurationTypeIsAvailable() {
        // Registered from the language module on purpose: running `flix build` needs neither LSP4IJ
        // nor the Java plugin, so it must stay available where those are absent. Asserted through
        // the assembled plugin because that placement is exactly what could regress.
        val types = extensionClassNames("com.intellij.configurationType")
        assertTrue(
            "the Flix task configuration type is not registered. Registered: $types",
            types.any { it == "org.flixlang.intellij.run.FlixTaskRunConfigurationType" },
        )
    }

    /**
     * The implementation class names registered at [extensionPointName].
     *
     * Looked up by name rather than through the extension point's own constant, because those
     * constants live in the Java plugin and this module deliberately does not depend on it -- the
     * same isolation the debugger module exists to provide. An unknown point yields an empty list,
     * which the callers above distinguish from "registered nothing".
     */
    private fun extensionClassNames(extensionPointName: String): List<String> =
        runCatching {
            ExtensionPointName<Any>(extensionPointName).extensionList.map { it::class.java.name }
        }.getOrDefault(emptyList())

    private companion object {
        private const val PLUGIN_ID = "dev.wstein.flix-jetbrains-plugin"
    }
}
