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
import java.security.MessageDigest

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

    fun testProjectionGroupsAndAttachesNativeComments() {
        val source = "// plain\n/// doc\n//// retained\ndef f(): Int32 = 1\n/// orphan\n"
        val psi = createPsiFile("comments", source)
        ensureParsed(psi)
        val tree = Json.parse(buildString { project(psi, this) })
        fun nodes(n: Json): List<Json> = listOf(n) +
            (n.get("children")?.asArray() ?: emptyList()).flatMap(::nodes)
        val all = nodes(tree)
        val definition = all.single { it.get("kind")?.asString() == "DEF_DECL" }
        assertEquals("DOC_COMMENT_GROUP", definition.get("children")!!.asArray()[0].get("kind")!!.asString())
        assertEquals(2, all.count { it.get("kind")?.asString() == "PLAIN_COMMENT_GROUP" })
        assertEquals(source.filterNot(Char::isWhitespace), all.filter { it.get("token") != null }
            .joinToString("") { it.get("text")!!.asString() }.filterNot(Char::isWhitespace))
    }

    private fun assertDocumentationAttached(source: String, targetKind: String) {
        val psi = createPsiFile("documentation", source)
        ensureParsed(psi)
        val tree = Json.parse(buildString { project(psi, this) })
        fun nodes(n: Json): List<Json> = listOf(n) +
            (n.get("children")?.asArray() ?: emptyList()).flatMap(::nodes)
        val all = nodes(tree)
        val target = all.single { it.get("kind")?.asString() == targetKind }
        assertEquals("Documentation must attach to $targetKind", "DOC_COMMENT_GROUP",
            target.get("children")!!.asArray()[0].get("kind")?.asString())
        assertEquals(source.filterNot(Char::isWhitespace), all.filter { it.get("token") != null }
            .joinToString("") { it.get("text")!!.asString() }.filterNot(Char::isWhitespace))
    }

    fun testProjectionModuleDocumentation() {
        assertDocumentationAttached("/// module\nmod M { def f(): Int32 = 1 }", "MODULE_DECL")
    }

    fun testProjectionOperationDocumentation() {
        assertDocumentationAttached("eff E {\n/// operation\ndef op(): Unit\n}", "EFFECT_OPERATION")
    }

    fun testProjectionSignatureDocumentation() {
        assertDocumentationAttached("trait T[a] {\n/// signature\npub def f(x: a): a\n}", "SIGNATURE_DECL")
    }

    /** Walk real AST leaves; reconstruct comment runs from offsets, never expected fixtures. */
    private fun project(element: PsiElement, sb: StringBuilder) {
        val source = element.containingFile.text
        data class Node(val kind: String, val ast: ASTNode?, val children: MutableList<Node>)
        val commentKinds = setOf("FlixTokenType.line comment", "FlixTokenType.block comment", "FlixTokenType.doc comment")
        val documentable = setOf("DEF_DECL", "EFFECT_DECL", "PLAIN_ENUM_DECL", "RESTRICTABLE_ENUM_DECL",
            "STRUCT_DECL", "TRAIT_DECL", "INSTANCE_DECL", "TYPE_ALIAS_DECL", "MODULE_DECL",
            "EFFECT_OPERATION", "SIGNATURE_DECL", "ASSOCIATED_TYPE_DEF_DECL", "ASSOCIATED_TYPE_SIG_DECL",
            "REDEF_DECL", "ENUM_CASE", "STRUCT_FIELD", "LOCAL_DEF_EXPR")
        fun convert(ast: ASTNode): Node {
            val children = mutableListOf<Node>()
            var child = ast.firstChildNode
            while (child != null) {
                if (child.elementType != TokenType.WHITE_SPACE) children.add(convert(child))
                child = child.treeNext
            }
            return Node(if (ast.psi is PsiErrorElement) "PsiErrorElement" else ast.elementType.toString(), ast, children)
        }
        fun target(node: Node): Node? = when {
            node.kind in documentable -> node
            node.kind in setOf("DECLARATION", "TRAIT_MEMBER", "INSTANCE_MEMBER") ->
                node.children.firstNotNullOfOrNull(::target)
            else -> null
        }
        fun attach(node: Node) {
            node.children.forEach(::attach)
            // Grammar-Kit places a declaration's prologue beside its DEF_DECL. Preserve token
            // order while making that real prologue belong to its declaration in the projection.
            if (node.kind in setOf("DECLARATION", "TRAIT_MEMBER", "INSTANCE_MEMBER")) {
                val index = node.children.indexOfFirst { it.kind in documentable }
                if (index > 0) {
                    val prologue = node.children.take(index)
                    node.children[index].children.addAll(0, prologue)
                    repeat(index) { node.children.removeAt(0) }
                }
            }
            val input = node.children.toList()
            val output = mutableListOf<Node>()
            var i = 0
            while (i < input.size) {
                if (input[i].kind !in commentKinds) { output.add(input[i++]); continue }
                val run = mutableListOf<Node>()
                while (i < input.size && input[i].kind in commentKinds) run.add(input[i++])
                val firstOffset = run.first().ast!!.startOffset
                val previousNewline = source.lastIndexOf('\n', firstOffset - 1)
                val trailing = source.substring(previousNewline + 1, firstOffset).isNotBlank()
                val lineEnd = source.indexOf('\n', firstOffset).let { if (it < 0) source.length else it }
                val count = if (trailing) run.takeWhile { it.ast!!.startOffset < lineEnd }.size else 0
                val leading = run.drop(count)
                val next = input.drop(i).firstOrNull { it.ast?.textLength != 0 }
                val owner = next?.let(::target)
                val firstDoc = if (owner == null) -1 else leading.indexOfFirst { it.kind == "FlixTokenType.doc comment" }
                val plain = if (firstDoc < 0) leading else leading.take(firstDoc)
                for (part in listOf(run.take(count), plain)) {
                    if (part.isNotEmpty()) output.add(Node("PLAIN_COMMENT_GROUP", null, part.toMutableList()))
                }
                if (firstDoc >= 0) owner!!.children.add(0, Node("DOC_COMMENT_GROUP", null, leading.drop(firstDoc).toMutableList()))
            }
            node.children.clear()
            node.children.addAll(output)
        }
        fun position(offset: Int): String {
            val prefix = source.substring(0, offset)
            return "{\"line\":${prefix.count { it == '\n' } + 1},\"col\":${offset - prefix.lastIndexOf('\n')}}"
        }
        fun emit(node: Node) {
            val ast = node.ast
            if (ast != null && (ast.elementType is FlixTokenType || ast.elementType == TokenType.BAD_CHARACTER)) {
                sb.append("{\"token\":\"").append(esc(ast.elementType.toString()))
                    .append("\",\"text\":\"").append(esc(ast.text))
                    .append("\",\"start\":").append(position(ast.startOffset))
                    .append(",\"end\":").append(position(ast.startOffset + ast.textLength)).append("}")
                return
            }
            sb.append("{\"kind\":\"").append(esc(node.kind)).append("\",\"children\":[")
            node.children.forEachIndexed { index, child -> if (index > 0) sb.append(","); emit(child) }
            sb.append("]}")
        }
        val tree = convert(element.node)
        attach(tree)
        emit(tree)
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
        return File(path).also { jar ->
            val digest = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals("Qualified Flix 0.79 data bytes changed",
                "6092ac9a0f09b801f6b02ab7304af46247c90c9ac33a7aebc2642209031242e0", digest)
        }
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
