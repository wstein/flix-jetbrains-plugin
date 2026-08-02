package org.flixlang.intellij.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import java.io.File
import java.util.jar.JarFile

/**
 * Parses every fixture published by `flix-spec` and emits each parse as a canonical projected tree.
 *
 * This is the consumer half of the conformance check. `flix-spec` owns the comparison itself so
 * that several parser repositories do not re-derive it several times; this side is responsible only
 * for producing trees in the canonical shape.
 *
 * Why this exists alongside [FlixCorpusTest]: that test gates on a parse-success *ratio* over
 * whichever Flix checkout happens to sit beside this repository, skipping entirely when none is
 * found. A ratio cannot distinguish "parsed" from "parsed correctly", and an unpinned corpus means
 * the number moves when someone else's working tree moves. The fixtures here arrive from a
 * versioned Maven artifact pinned to a known revision of the Flix reference compiler, so the same
 * input produces the same expectation on every machine and in CI.
 *
 * The projected trees are written to `build/flix-spec-projection/`. Feeding them to `flix-spec`'s
 * comparator, and ratcheting the divergence count, is a follow-up: the comparator is not yet
 * published as a runnable artifact, only the data is.
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

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    /** Emits a projected node: kind plus ordered children, whitespace dropped. */
    private fun project(element: PsiElement, sb: StringBuilder) {
        val kind = if (element is PsiErrorElement) "PsiErrorElement" else element.node.elementType.toString()
        sb.append("{\"kind\":\"").append(esc(kind)).append("\",\"children\":[")
        element.children.filterNot { it is PsiWhiteSpace }.forEachIndexed { i, child ->
            if (i > 0) sb.append(",")
            project(child, sb)
        }
        sb.append("]}")
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
        val outDir = File(project.basePath ?: ".", "build/flix-spec-projection").also { it.mkdirs() }

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
                sb.append("{\"schemaVersion\":1,")
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
}
