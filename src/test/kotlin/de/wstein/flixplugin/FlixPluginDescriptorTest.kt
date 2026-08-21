package de.wstein.flixplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Asserts the plugin's registration wiring: that the descriptors name the extensions we intend,
 * that the classes they name exist and implement the right platform interfaces, and that nothing
 * is registered twice.
 *
 * This exists because the behavioural tests deliberately instantiate the language layer's classes
 * directly. A content-module descriptor is inert on its own -- the platform only reads it when the
 * parent `plugin.xml` names it in `<content>` -- so a module-local test fixture never loads our
 * `<extensions>` and every extension lookup resolves null. Rather than assert wiring through a
 * fixture that cannot represent it, wiring is asserted here against the descriptors themselves and
 * behaviour is asserted against the implementations.
 *
 * The invariants below are the ones that would silently break the plugin rather than fail loudly:
 * a class name that no longer resolves, an extension point renamed, a second `Language("Flix")`
 * owner appearing, or a content module dropping out of `<content>`.
 */
class FlixPluginDescriptorTest {

    private val root = File(System.getProperty("user.dir"))

    private fun parse(path: String): Element {
        val file = File(root, path)
        assertTrue("Missing descriptor: $path", file.isFile)
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun Element.childrenNamed(name: String): List<Element> =
        getElementsByTagName(name).let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }

    @Test
    fun `every descriptor is well-formed XML`() {
        // `--` is not permitted inside an XML comment. It is an easy thing to type when writing a
        // prose comment, it is not caught by verifyPluginProjectConfiguration or buildPlugin, and
        // when it reaches a descriptor it makes the *entire plugin* fail to load on both sides of
        // split mode with only "contains invalid plugin descriptor" to go on. Parsing every
        // descriptor here turns that into an immediate, located test failure.
        descriptors().forEach { path ->
            runCatching { parse(path) }.onFailure {
                throw AssertionError("$path is not well-formed XML: ${it.message}", it)
            }
        }
    }

    @Test
    fun `plugin declares every content module`() {
        val modules = parse("src/main/resources/META-INF/plugin.xml")
            .childrenNamed("module")
            .map { it.getAttribute("name") }
        assertEquals(
            listOf(
                "flix.jetbrains.plugin.shared",
                "flix.jetbrains.plugin.language",
                "flix.jetbrains.plugin.debugger",
                "flix.jetbrains.plugin.frontend",
                "flix.jetbrains.plugin.backend",
            ),
            modules,
        )
    }

    @Test
    fun `the debugger module is optional and scopes its Java plugin dependency`() {
        val debugger = parse("debugger/src/main/resources/flix.jetbrains.plugin.debugger.xml")

        // com.intellij.debugger.positionManagerFactory lives in the Java plugin, so this module
        // cannot load in an IDE without Java support. It must therefore stay optional: marking it
        // loading="required" would take the whole plugin -- language layer included -- down with it.
        val declared = parse("src/main/resources/META-INF/plugin.xml")
            .childrenNamed("module")
            .single { it.getAttribute("name") == "flix.jetbrains.plugin.debugger" }
        assertEquals(
            "The debugger module must not be required; the language layer has to load without Java",
            "",
            declared.getAttribute("loading"),
        )

        assertTrue(
            "The Java plugin dependency belongs here, not in the language module",
            debugger.childrenNamed("plugin").any { it.getAttribute("id") == "com.intellij.java" },
        )
    }

    @Test
    fun `language module registers exactly the intended extensions`() {
        val extensions = languageExtensions()
        assertEquals(
            "The language layer's registrations are the plugin's contract with the platform; " +
                "adding or losing one silently changes what the IDE does with a .flix file",
            listOf(
                "fileType",
                "lang.parserDefinition",
                "lang.syntaxHighlighterFactory",
                // Settings > Editor > Color Scheme > Flix. Every colour key falls back to a
                // platform default, so a key changes nothing on screen until someone picks
                // one -- and without this page there is nowhere to pick.
                "colorSettingsPage",
                // Two annotators, and the count is the assertion. Both read this plugin's own PSI,
                // so they belong wherever the parser loads rather than beside the LSP client:
                //   - control flow: tells the four productions `IF_KW` appears in apart, one of
                //     which -- the Datalog constraint -- is not a conditional;
                //   - semantic fallback: colours the names a file declares, which are roles rather
                //     than token kinds and so are invisible to the lexer.
                "annotator",
                "annotator",
                "lang.braceMatcher",
                "lang.commenter",
                "lang.quoteHandler",
                "lang.foldingBuilder",
                // The Flix subcommands. Here rather than in backend or debugger because running
                // `flix build` needs neither LSP4IJ nor the Java plugin, and this is the only
                // always-loaded module that can register a platform extension.
                "configurationType",
                // Settings > Languages & Frameworks > Flix. Here because the task runner reads the
                // same service, and tasks have to work without LSP4IJ.
                "projectConfigurable",
                // Declaration scaffolding. The server's expression snippets are left to it, so one
                // construct is never offered from two inventories.
                "liveTemplateContext",
                "defaultLiveTemplates",
            ),
            extensions.map { it.tagName },
        )
    }

    @Test
    fun `every registered implementation class exists and fits its extension point`() {
        val expectedSupertype = mapOf(
            "fileType" to "com.intellij.openapi.fileTypes.LanguageFileType",
            "lang.parserDefinition" to "com.intellij.lang.ParserDefinition",
            "lang.syntaxHighlighterFactory" to "com.intellij.openapi.fileTypes.SyntaxHighlighterFactory",
            "colorSettingsPage" to "com.intellij.openapi.options.colors.ColorSettingsPage",
            "annotator" to "com.intellij.lang.annotation.Annotator",
            "lang.braceMatcher" to "com.intellij.lang.PairedBraceMatcher",
            "lang.commenter" to "com.intellij.lang.Commenter",
            "lang.quoteHandler" to "com.intellij.codeInsight.editorActions.QuoteHandler",
            "lang.foldingBuilder" to "com.intellij.lang.folding.FoldingBuilder",
            "configurationType" to "com.intellij.execution.configurations.ConfigurationType",
            "projectConfigurable" to "com.intellij.openapi.options.Configurable",
            "liveTemplateContext" to "com.intellij.codeInsight.template.TemplateContextType",
        )

        // `defaultLiveTemplates` names a resource file, not a class; a separate test checks it.
        languageExtensions().filterNot { it.tagName == "defaultLiveTemplates" }.forEach { extension ->
            // Three spellings across the extension points this module uses: `implementationClass`
            // for the language ones, `implementation` for configurationType, `instance` for
            // projectConfigurable. Reading only one silently skipped the others.
            val className = extension.getAttribute("implementationClass")
                .ifEmpty { extension.getAttribute("implementation") }
                .ifEmpty { extension.getAttribute("instance") }
            val loaded = runCatching { Class.forName(className) }.getOrNull()
            assertTrue("<${extension.tagName}> names a class that does not exist: $className", loaded != null)

            val supertype = Class.forName(expectedSupertype.getValue(extension.tagName))
            assertTrue(
                "$className is registered as <${extension.tagName}> but does not implement " +
                    "${supertype.name}; the platform would reject it at runtime",
                supertype.isAssignableFrom(loaded!!),
            )
        }
    }

    @Test
    fun `the live template file the descriptor names is on the classpath`() {
        // `defaultLiveTemplates` names a resource rather than a class, so nothing else would notice
        // it being moved or renamed -- the templates would simply stop existing, in every project.
        val declared = languageExtensions().single { it.tagName == "defaultLiveTemplates" }.getAttribute("file")
        assertTrue(
            "the descriptor points at $declared, which is not on the classpath",
            javaClass.getResource("$declared.xml") != null,
        )
    }

    @Test
    fun `every language extension targets the Flix language`() {
        languageExtensions()
            .filter { it.hasAttribute("language") }
            .forEach {
                assertEquals(
                    "Registering a Flix extension against another language would steal that " +
                        "language's behaviour",
                    "Flix",
                    it.getAttribute("language"),
                )
            }
    }

    @Test
    fun `the gutter run marker is registered backend-only`() {
        // Line markers are produced by the highlighting pass and resolved through ExecutorAction
        // against run-configuration producers, both of which are backend concerns under split mode.
        // Registered in the always-loaded language module instead, it loaded in both processes and
        // drew two arrows on the line -- the backend one offering Run/Debug, the frontend one, with
        // no producers available to it, offering "Nothing here".
        val language = parse("language/src/main/resources/flix.jetbrains.plugin.language.xml")
        assertTrue(
            "The language module must not register the run line marker; it loads in both processes",
            language.childrenNamed("runLineMarkerContributor").isEmpty(),
        )

        val backend = parse("backend/src/main/resources/flix.jetbrains.plugin.backend.xml")
        val markers = backend.childrenNamed("runLineMarkerContributor")
        assertEquals("Exactly one run line marker contributor", 1, markers.size)
        assertEquals("Flix", markers.single().getAttribute("language"))

        // The contributor class lives in the language module, so the backend has to depend on it
        // for the class to resolve.
        assertTrue(
            "The backend module must depend on the language module to resolve the contributor class",
            backend.childrenNamed("module").any { it.getAttribute("name") == "flix.jetbrains.plugin.language" },
        )
    }

    @Test
    fun `only the language module owns the flix file extension`() {
        val fileTypes = languageExtensions().filter { it.tagName == "fileType" }
        assertEquals("Exactly one file type may claim *.flix", 1, fileTypes.size)
        assertEquals("flix", fileTypes.single().getAttribute("extensions"))

        // The backend still maps *.flix for LSP4IJ, which is filename-based and independent of the
        // file type. What must not exist is a second fileType or parserDefinition.
        val backend = parse("backend/src/main/resources/flix.jetbrains.plugin.backend.xml")
        assertTrue(
            "The backend module must not register a second file type or parser definition; " +
                "ADR 0001 makes the language module the single owner",
            backend.childrenNamed("fileType").isEmpty() &&
                backend.childrenNamed("lang.parserDefinition").isEmpty(),
        )
    }

    /** Every plugin descriptor in the repository, root and content modules alike. */
    private fun descriptors(): List<String> = listOf(
        "src/main/resources/META-INF/plugin.xml",
        "language/src/main/resources/flix.jetbrains.plugin.language.xml",
        "debugger/src/main/resources/flix.jetbrains.plugin.debugger.xml",
        "backend/src/main/resources/flix.jetbrains.plugin.backend.xml",
        "frontend/src/main/resources/flix.jetbrains.plugin.frontend.xml",
        "shared/src/main/resources/flix.jetbrains.plugin.shared.xml",
    )

    private fun languageExtensions(): List<Element> =
        parse("language/src/main/resources/flix.jetbrains.plugin.language.xml")
            .childrenNamed("extensions")
            .single { it.getAttribute("defaultExtensionNs") == "com.intellij" }
            .let { block ->
                (0 until block.childNodes.length)
                    .map { block.childNodes.item(it) }
                    .filterIsInstance<Element>()
            }
}
