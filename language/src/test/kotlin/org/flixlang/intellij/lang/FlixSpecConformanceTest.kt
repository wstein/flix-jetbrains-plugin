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

        /**
         * A divergence *count* ratchet for [testConformanceAgainstReference], not an exact-fixture-
         * set one like [KNOWN_DIVERGENCES]: an exact-set ratchet moves in large, lumpy steps here
         * (see the history of this constant) rather than never at all, but the count still catches
         * a regression the moment one lands, matching the semantics `flix.spec.Conformance
         * --baseline` uses on the flix-spec side, and needs no per-fixture bookkeeping.
         *
         * History:
         *   - 378, after fixing the `ignored`-vs-`elide` conflation bug in flix-spec's
         *     `ast/projection/flix-jetbrains-plugin.json` (flix-spec commit 7ddf005): depth 78% ->
         *     89%, 505 nodes compared, 0/136 fixtures agreeing.
         *   - 102, after making `ident` non-`private` in this repository's own `Flix.bnf`: it was
         *     the single largest remaining divergence class, touching nearly every declaration,
         *     parameter, and case name. 80/136 fixtures now agree. Depth *dropped* to 58% in the
         *     same change -- not a regression: the newly-real `IDENT` node has no entry yet in
         *     flix-spec's projection map, so it counts as unmapped (421 occurrences) rather than
         *     compared, until that map is updated on the flix-spec side.
         */
        private const val DIVERGENCE_BASELINE = 102
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

    /** `/ast/projection/flix-jetbrains-plugin.json` from the flix-spec artifact's classpath. */
    private fun loadProjectionMap(): Conformance.ProjectionMap {
        val text = javaClass.getResourceAsStream("/ast/projection/flix-jetbrains-plugin.json")
            ?.use { it.readBytes().decodeToString() }
            ?: error("flix-spec artifact has no /ast/projection/flix-jetbrains-plugin.json")
        return Conformance.loadProjectionMap(Json.parse(text))
    }

    /** Every JSON file under `fixtures/expected` in the flix-spec artifact, keyed by source path. */
    private fun loadExpectedTrees(jar: File): Map<String, Conformance.KTree> =
        JarFile(jar).use { jf ->
            val entries = jf.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("fixtures/expected/") && it.endsWith(".json") }
                .toList()
            check(entries.isNotEmpty()) { "flix-spec artifact contains no fixtures/expected/*.json" }
            entries.associate { entry ->
                val text = jf.getInputStream(jf.getEntry(entry)).use { it.readBytes().decodeToString() }
                val units = Conformance.loadUnits(Json.parse(text))
                check(units.size == 1) { "$entry: expected exactly one unit, found ${units.size}" }
                units.entries.single().toPair()
            }
        }

    /**
     * Parses and projects every fixture into a [Conformance.KTree], keyed by its
     * `.flix` source path under `fixtures/positive` or `fixtures/negative`. Self-contained rather than reusing the file this
     * test's [testFixturesParseAndProject] writes to `build/flix-spec-projection/`, so this test
     * does not depend on JUnit running the two in a particular order.
     */
    private fun projectAllFixtures(jar: File): Map<String, Conformance.KTree> =
        JarFile(jar).use { jf ->
            val fixtures = jf.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".flix") && (it.startsWith("fixtures/positive/") || it.startsWith("fixtures/negative/")) }
                .sorted()
                .toList()
            fixtures.associateWith { entry ->
                val source = jf.getInputStream(jf.getEntry(entry)).use { it.readBytes().decodeToString() }
                val name = entry.substringAfterLast('/').removeSuffix(".flix")
                val psi = createPsiFile(name, source)
                ensureParsed(psi)
                val sb = StringBuilder()
                project(psi, sb)
                Conformance.kindTree(Json.parse(sb.toString()))!!
            }
        }

    fun testConformanceAgainstReference() {
        val jar = flixSpecJar()
        val map = loadProjectionMap()
        val expected = loadExpectedTrees(jar)
        val actual = projectAllFixtures(jar)

        val result = Conformance.run(expected, actual, map)
        println("[flix-spec] " + result.summary(map.consumer))

        if (result.divergences.size > DIVERGENCE_BASELINE) {
            val message = buildString {
                appendLine("${result.divergences.size} divergences exceeds baseline $DIVERGENCE_BASELINE")
                result.divergences.take(10).forEach { (source, d) ->
                    appendLine("  $source ${d.path}: expected '${d.expected}', got '${d.actual}' (${d.reason})")
                }
            }
            fail(message)
        }
    }
}
