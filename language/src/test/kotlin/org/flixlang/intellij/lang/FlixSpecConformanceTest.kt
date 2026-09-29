package org.flixlang.intellij.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import org.flixlang.intellij.lang.psi.FlixTokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import java.io.File
import java.util.jar.JarFile

/**
 * Parses the pinned data fixtures with real PSI and emits native raw projections.
 * The conformance/ Gradle build owns comparison through the published flix-spec plugin;
 * this adapter contains no copy of its comparison algorithm.
 */
class FlixSpecConformanceTest : ParsingTestCase("", "flix", FlixParserDefinition()) {

    private companion object {
        /**
         * Fixtures the reference compiler accepts and this grammar does not, each a known defect.
         *
         * An exact set rather than a tolerated count: the assertion fails both when a new fixture
         * starts failing *and* when a listed one starts passing, so fixing a defect forces the
         * entry to be removed and the ratchet can only tighten.
         */
        private val KNOWN_DIVERGENCES = mapOf(
            // `use Foo.Bar.baz;` -- the reference permits a trailing semicolon on a use
            // declaration; Flix.bnf does not, so the ';' is unexpected after the qualified name.
            "fixtures/positive/declarations__uses-and-imports.flix" to
                "trailing ';' after a use declaration is rejected",
        )

    }

    override fun getTestDataPath(): String = ""

    override fun skipSpaces(): Boolean = true

    private fun esc(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    fun testProjectionRetainsRealLexerLeavesAndEmptyNodes() {
        val source = "/// doc\n/// more\ndef f(): Int32 = Foo.\n"
        val psi = createPsiFile("tokens", source)
        ensureParsed(psi)
        val tree = Json.parse(buildString { project(psi, this) })
        fun nodes(node: Json): List<Json> = listOf(node) +
            (node.get("children")?.asArray() ?: emptyList()).flatMap(::nodes)
        val all = nodes(tree)
        val leaves = all.filter { it.get("token") != null }
        assertTrue("PSI children alone lose lexer leaves", leaves.size > 5)
        assertEquals(source.filterNot(Char::isWhitespace),
            leaves.joinToString("") { it.get("text")!!.asString() }.filterNot(Char::isWhitespace))
        val dot = all.single { it.get("kind")?.asString() == "TRAILING_DOT" }
        assertEquals(".", dot.get("children")!!.asArray().single().get("text")!!.asString())
        assertTrue("Empty grammar nodes must not become tokens", all.any {
            it.get("kind")?.asString() == "ANNOTATION_LIST" && it.get("children")!!.asArray().isEmpty()
        })
        assertEquals("\\u0000\\u000c", esc("\u0000\u000c"))
    }

    /** Walk the AST, not PsiElement.children (which silently drops lexer leaves). */
    private fun project(element: PsiElement, sb: StringBuilder) {
        val source = element.containingFile.text
        fun position(offset: Int): String {
            val prefix = source.substring(0, offset)
            return "{\"line\":${prefix.count { it == '\n' } + 1},\"col\":${offset - prefix.lastIndexOf('\n')}}"
        }
        fun emit(node: ASTNode) {
            val type = node.elementType
            if (type is FlixTokenType || type == TokenType.BAD_CHARACTER) {
                sb.append("{\"token\":\"").append(esc(type.toString()))
                    .append("\",\"text\":\"").append(esc(node.text))
                    .append("\",\"start\":").append(position(node.startOffset))
                    .append(",\"end\":").append(position(node.startOffset + node.textLength)).append("}")
                return
            }
            val kind = if (node.psi is PsiErrorElement) "PsiErrorElement" else type.toString()
            sb.append("{\"kind\":\"").append(esc(kind)).append("\",\"children\":[")
            var child = node.firstChildNode
            var comma = false
            while (child != null) {
                if (child.elementType != TokenType.WHITE_SPACE) {
                    if (comma) sb.append(",")
                    emit(child)
                    comma = true
                }
                child = child.treeNext
            }
            sb.append("]}")
        }
        emit(element.node)
    }

    /** `upstream.commit` recorded in the artifact's pin.json, read without a JSON dependency. */
    private fun flixSpecPinnedCommit(): String? =
        javaClass.getResourceAsStream("/pin.json")?.use { stream ->
            val text = stream.readBytes().decodeToString()
            val upstream = text.substringAfter("\"upstream\"", "")
            Regex("\"commit\"\\s*:\\s*\"([0-9a-f]{40})\"").find(upstream)?.groupValues?.get(1)
        }

    /** HEAD of the Flix checkout this repository is testing against, or null if there is none. */
    private fun localFlixCommit(): Pair<File, String>? {
        val checkout = FlixCorpusTest.locateCorpus() ?: return null
        val proc = ProcessBuilder("git", "-C", checkout.absolutePath, "rev-parse", "HEAD")
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        return if (proc.waitFor() == 0 && out.length == 40) checkout to out else null
    }

    /**
     * Fails when the Flix that flix-spec was derived from is not the Flix this repository tests
     * against.
     *
     * This is the mismatch no version scheme can catch. flix-spec's coordinate describes the
     * artifact, not the consumer, so a build can depend on fixtures derived from one Flix while
     * FlixCorpusTest reads a checkout of another -- which is exactly the situation this repository
     * was in. The two facts have to be compared, not encoded in a name and trusted.
     *
     * Skips rather than fails when no checkout is present: absence is a missing input, not a
     * disagreement, and FlixCorpusTest already reports that separately.
     */
    fun testPinMatchesLocalFlixCheckout() {
        val pinned = flixSpecPinnedCommit()
        assertNotNull("flix-spec artifact has no readable pin.json", pinned)

        val local = localFlixCommit()
        if (local == null) {
            println("[flix-spec] pin check SKIPPED: no Flix git checkout found (set -DflixCorpusDir or FLIX_DIR)")
            return
        }

        val (checkout, head) = local

        // An explicit override, not a tolerance: working deliberately against a different Flix is
        // legitimate, silently accepting a mismatch is not. The flag has to be typed, so the
        // inconsistency is always someone's stated decision.
        if (System.getProperty("flixSpec.allowPinMismatch") == "true" && pinned != head) {
            println(
                "[flix-spec] pin MISMATCH allowed by -DflixSpec.allowPinMismatch=true: " +
                    "artifact=$pinned checkout=$head ($checkout)",
            )
            return
        }

        assertEquals(
            "flix-spec is derived from Flix $pinned but $checkout is at $head.\n" +
                "Fixtures and the local corpus describe different compilers; one of them must move.",
            pinned,
            head,
        )
    }

    /** The flix-spec artifact on the test classpath, located by a file it is known to contain. */
    private fun flixSpecJar(): File {
        val marker = javaClass.getResource("/ast/treekind.json")
            ?: error("flix-spec artifact is not on the test classpath")
        val path = marker.toString().substringAfter("file:").substringBefore("!")
        return File(path)
    }

    fun testFixturesParseAndProject() {
        val jar = flixSpecJar()
        // The pilot plugin and this test resolve independently. Compare bytes, not just compiler
        // pins: two data releases can share a pin while changing fixtures or normalization.
        System.getProperty("flixSpec.pilotBundle")?.let { expected ->
            assertTrue("Pilot data bundle differs from the adapter's test-classpath artifact",
                jar.readBytes().contentEquals(File(expected).readBytes()))
        }
        val outDir = File(requireNotNull(System.getProperty("flixSpec.projectionDir")) {
            "Set flixSpec.projectionDir to the adapter output directory"
        }).also { it.mkdirs() }
        // This directory belongs to this producer. Never leave removed fixtures in the next run.
        outDir.listFiles { file -> file.extension == "json" }?.forEach { check(it.delete()) }

        val failures = mutableListOf<String>()
        var projected = 0

        JarFile(jar).use { jf ->
            val fixtures = jf.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".flix") && (it.startsWith("fixtures/positive/") || it.startsWith("fixtures/negative/")) }
                .sorted()
                .toList()

            assertTrue("flix-spec artifact contains no fixtures", fixtures.isNotEmpty())

            fixtures.forEach { entry ->
                val source = jf.getInputStream(jf.getEntry(entry)).use { it.readBytes().decodeToString() }
                val name = entry.substringAfterLast('/').removeSuffix(".flix")
                val psi = createPsiFile(name, source)
                ensureParsed(psi)

                // A positive fixture is one the reference compiler accepts. Anything the plugin
                // cannot parse cleanly is a real disagreement with the pinned oracle, not a
                // tolerable dip in a ratio.
                if (entry.startsWith("fixtures/positive/")) {
                    val error = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)
                    if (error != null) failures += "$entry: ${error.errorDescription}"
                }

                val sb = StringBuilder()
                sb.append("{\"schemaVersion\":2,\"form\":\"raw\",")
                sb.append("\"generatedBy\":\"org.flixlang.intellij.lang.FlixSpecConformanceTest\",")
                sb.append("\"units\":[{\"source\":\"").append(esc(entry)).append("\",")
                sb.append("\"diagnostics\":[],\"tree\":")
                project(psi, sb)
                sb.append("}]}")
                File(outDir, "$name.json").writeText(sb.toString())
                projected++
            }
        }

        println("[flix-spec] projected $projected fixtures to $outDir")

        // Compared as an exact set, not a threshold: a regression and an unrecorded fix are both
        // failures, so the list cannot quietly drift in either direction.
        val failing = failures.map { it.substringBefore(": ") }.toSortedSet()
        val known = KNOWN_DIVERGENCES.keys.toSortedSet()

        val regressions = failing - known
        val fixed = known - failing

        val message = buildString {
            if (regressions.isNotEmpty()) {
                appendLine("${regressions.size} fixture(s) newly fail to parse:")
                failures.filter { it.substringBefore(": ") in regressions }.forEach { appendLine("  $it") }
            }
            if (fixed.isNotEmpty()) {
                appendLine("${fixed.size} known divergence(s) now parse -- remove them from KNOWN_DIVERGENCES:")
                fixed.forEach { appendLine("  $it (${KNOWN_DIVERGENCES[it]})") }
            }
        }
        assertTrue(message, regressions.isEmpty() && fixed.isEmpty())
    }

    /**
     * Every `mappings` target must exist in the artifact's own `ast/treekind.json`.
     *
     * flix-spec's `validateProjectionMap` enforces this, but that runs in flix-spec against a map
     * path someone remembers to pass; nothing here did, so a canonical kind disappearing upstream
     * was invisible on this side. It has now happened: Flix v0.75.2 removed law declarations and
     * `Decl.Law` left the inventory, taking two other consumers' maps with it.
     *
     * A mapping onto a kind no tree can contain is not inert. Our node keeps standing where the
     * canonical tree has none, so it manufactures divergences -- and the failure reads as a grammar
     * regression rather than as a stale map, which is the expensive way to find out.
     */
    fun testProjectionMapTargetsExistInTheInventory() {
        val jar = flixSpecJar()
        val inventory =
            JarFile(jar).use { jf ->
                val entry = jf.getEntry("ast/treekind.json")
                    ?: error("flix-spec artifact has no ast/treekind.json")
                val text = jf.getInputStream(entry).use { it.readBytes().decodeToString() }
                Json.parse(text).get("kinds")!!.asArray().map { it.get("name")!!.asString() }.toSet()
            }
        val map = javaClass.getResourceAsStream("/conformance/projection-map.json")!!
            .use { Json.parse(it.readBytes().decodeToString()) }
        val absent = map.get("mappings")!!.asObject().values.map { it.asString() }
            .toSortedSet().filterNot { inventory.contains(it) }
        assertTrue(
            "projection map targets kinds absent from this flix-spec artifact: $absent",
            absent.isEmpty(),
        )
    }

}
