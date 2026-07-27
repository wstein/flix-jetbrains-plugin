package dev.wstein.flixplugin

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
                "flix.jetbrains.plugin.frontend",
                "flix.jetbrains.plugin.backend",
            ),
            modules,
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
                "lang.braceMatcher",
                "lang.commenter",
                "lang.quoteHandler",
                "lang.foldingBuilder",
                "runLineMarkerContributor",
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
            "lang.braceMatcher" to "com.intellij.lang.PairedBraceMatcher",
            "lang.commenter" to "com.intellij.lang.Commenter",
            "lang.quoteHandler" to "com.intellij.codeInsight.editorActions.QuoteHandler",
            "lang.foldingBuilder" to "com.intellij.lang.folding.FoldingBuilder",
            "runLineMarkerContributor" to "com.intellij.execution.lineMarker.RunLineMarkerContributor",
        )

        languageExtensions().forEach { extension ->
            val className = extension.getAttribute("implementationClass")
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
