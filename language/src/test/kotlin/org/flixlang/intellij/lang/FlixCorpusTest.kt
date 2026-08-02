package org.flixlang.intellij.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import org.flixlang.intellij.lang.psi.FlixDefDecl
import org.flixlang.intellij.run.nameOrNull
import org.flixlang.intellij.run.namePsiOrNull
import java.io.File

/**
 * Parses every `.flix` file in the upstream Flix corpus and gates the grammar on the result.
 *
 * The corpus is the strongest oracle available without building the Scala compiler: every file
 * under `main/src/library` and `examples` is source that upstream Flix compiles successfully, so a
 * `PsiErrorElement` on one of them is by definition a defect in this grammar rather than in the
 * input. The hand-written snippet tests elsewhere in this suite cannot make that claim -- they were
 * written against the grammar, so they can only confirm it is self-consistent.
 *
 * Three properties are checked per file:
 *
 *  - **No crash.** Parsing must not throw, assert, or hang.
 *  - **Losslessness.** Concatenating every leaf must reproduce the input exactly. A parser that
 *    silently drops text would otherwise look clean.
 *  - **Error-free.** Zero [PsiErrorElement]s.
 *
 * Losslessness and crash-freedom are absolute; the error-free ratio is gated by
 * [MIN_CLEAN_RATIO]. Running it also writes `build/reports/flix-parser-corpus.json`, which groups
 * the failures by first-error token so that a regression names the syntax family it broke rather
 * than just a count.
 *
 * The corpus lives in a sibling Flix checkout, so the test skips rather than fails when it is
 * absent locally. Point it somewhere else with `-DflixCorpusDir=...` or `FLIX_DIR`, and see
 * `flixCorpusCommit` for the pinned revision.
 *
 * **In CI it fails instead of skipping.** The workflow clones the pinned revision before running
 * `check`, so an absent corpus there means that step broke -- and a skip would report the strongest
 * gate in this suite as an ordinary pass. JUnit 3 has no skip state, so a skipped run is
 * indistinguishable from a real one in the pass/fail count; the loud console line is the only
 * signal, and nobody reads console output of a green build.
 */
class FlixCorpusTest : ParsingTestCase("", "flix", FlixParserDefinition()) {

    override fun getTestDataPath(): String = ""

    override fun skipSpaces(): Boolean = false

    fun testCorpusParsesCleanly() {
        val corpus = locateCorpus()
        if (corpus == null) {
            failIfRequired()
            println("[flix-corpus] SKIPPED: no Flix checkout found. $HOW_TO_POINT_AT_A_CHECKOUT")
            return
        }

        verifyPinnedRevision(corpus)

        val files = collectCorpusFiles(corpus)
        assertFalse(
            "Found a Flix checkout at $corpus but no .flix files under ${CORPUS_ROOTS.joinToString()}",
            files.isEmpty(),
        )

        val all = files.map { evaluate(corpus, it) }
        writeReport(corpus, all)

        val (excluded, results) = all.partition { it.path in UNCOMPILABLE_UPSTREAM }
        val crashed = results.filter { it.crash != null }
        val lossy = results.filter { !it.lossless }
        val clean = results.count { it.errors.isEmpty() }
        val ratio = clean.toDouble() / results.size

        println(
            "[flix-corpus] %d files (%d excluded), %d clean (%.1f%%), %d crashed, %d lossy"
                .format(all.size, excluded.size, clean, ratio * 100, crashed.size, lossy.size),
        )
        failuresByFirstErrorToken(results).forEach { (token, group) ->
            println("[flix-corpus]   %4d file(s) first fail at %s".format(group.size, token))
        }

        // An exclusion that silently starts passing is an exclusion nobody will ever remove, so
        // each one must keep failing for the reason it was granted. If upstream fixes the file --
        // or implements the syntax it uses -- this fails and the entry gets deleted.
        excluded.forEach { result ->
            assertTrue(
                "${result.path} is excluded as uncompilable upstream (${UNCOMPILABLE_UPSTREAM[result.path]}) " +
                    "but now parses cleanly. Drop the exclusion.",
                result.errors.isNotEmpty(),
            )
        }
        assertEquals(
            "Exclusions must name a file that exists in the corpus",
            UNCOMPILABLE_UPSTREAM.keys.sorted(),
            excluded.map { it.path }.sorted(),
        )

        assertEquals("Parsing must never throw: ${crashed.take(5).map { "${it.path}: ${it.crash}" }}", 0, crashed.size)
        assertEquals("Every parse must be lossless: ${lossy.take(5).map { it.path }}", 0, lossy.size)
        assertTrue(
            "Only %.1f%% of the %d-file corpus parses without errors, below the %.0f%% gate. %s"
                .format(ratio * 100, results.size, MIN_CLEAN_RATIO * 100, SEE_REPORT),
            ratio >= MIN_CLEAN_RATIO,
        )
    }

    /**
     * Every `def main` the grammar finds must be a real [FlixDefDecl] named `main` whose name
     * (its `Ident` node) lies inside the declaration. `FlixRunLineMarkerContributor` anchors the
     * gutter icon on the single leaf token inside that node, so a corpus-wide check here is what
     * keeps the green arrow from drifting onto the wrong line -- or disappearing -- as the grammar
     * changes.
     */
    fun testEntryPointsAreAddressable() {
        val corpus = locateCorpus() ?: run {
            failIfRequired()
            println("[flix-corpus] SKIPPED: no Flix checkout found. $HOW_TO_POINT_AT_A_CHECKOUT")
            return
        }

        var mains = 0
        collectCorpusFiles(corpus).forEach { relative ->
            val psi = parseOrNull(corpus, relative) ?: return@forEach
            PsiTreeUtil.findChildrenOfType(psi, FlixDefDecl::class.java)
                .filter { it.nameOrNull() == "main" }
                .forEach { decl ->
                    val namePsi = decl.namePsiOrNull()
                    assertNotNull("def main in $relative has no addressable name", namePsi)
                    assertTrue(
                        "def main name in $relative lies outside its declaration",
                        decl.textRange.contains(namePsi!!.textRange),
                    )
                    assertEquals("def main name text in $relative", "main", namePsi.text)
                    mains++
                }
        }
        // Without a floor this test passes vacuously: a parser producing no FlixDefDecl at all
        // never enters the loop, so zero entry points would read as success. The floor is an
        // independent count taken from the corpus text itself, so it cannot drift with the parser.
        val declared = countDeclaredMains(corpus)
        assertTrue(
            "The corpus declares $declared `def main` entry points but only $mains are addressable " +
                "in the PSI. The gutter run marker anchors on exactly these, so a shortfall means " +
                "some entry points would show no run arrow.",
            mains >= declared,
        )
        println("[flix-corpus] verified $mains addressable `def main` entry point(s) (>= $declared declared)")
    }

    /**
     * Counts `def main` declarations by scanning the corpus text, independently of the parser.
     *
     * Deliberately conservative -- it matches only a `def main` at the start of a line, optionally
     * preceded by modifiers -- so it under-counts rather than over-counts and cannot fail the
     * assertion above spuriously. Its job is to be a floor the parser must clear, not an exact
     * oracle.
     */
    private fun countDeclaredMains(corpus: File): Int =
        collectCorpusFiles(corpus).sumOf { relative ->
            File(corpus, relative).readLines().count { line ->
                DECLARED_MAIN.containsMatchIn(line)
            }
        }

    /**
     * Warns when the checkout is not on the pinned revision.
     *
     * The numbers in docs/intellij-flix-parser-evaluation.md describe one specific corpus. Measured
     * against a drifted checkout they mean something else, and silently so -- new upstream files
     * would look like grammar regressions, removed ones like improvements. This reports the
     * mismatch rather than failing, because a developer deliberately testing against newer Flix is
     * a legitimate thing to do; CI pins the revision explicitly.
     */
    private fun verifyPinnedRevision(corpus: File) {
        val pinned = System.getProperty("flixCorpusCommit").orEmpty()
        if (pinned.isBlank()) return

        val head = runCatching {
            ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(corpus)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readText().trim()
        }.getOrNull()

        if (head == null || head.isBlank()) {
            println("[flix-corpus] NOTE: could not read the checkout's revision; expected $pinned")
        } else if (head != pinned) {
            println(
                "[flix-corpus] NOTE: corpus is at $head, not the pinned $pinned. Results are not " +
                    "comparable with docs/intellij-flix-parser-evaluation.md.",
            )
        }
    }

    private fun evaluate(corpus: File, relative: String): FileResult {
        val source = File(corpus, relative).readText()
        return try {
            val psi = createPsiFile(relative.substringAfterLast('/').removeSuffix(".flix"), source)
            ensureParsed(psi)
            FileResult(
                path = relative,
                lossless = leafText(psi) == source,
                errors = PsiTreeUtil.findChildrenOfType(psi, PsiErrorElement::class.java).map {
                    ParseError(it.textRange.startOffset, it.errorDescription, firstTokenAt(psi, it))
                },
                crash = null,
            )
        } catch (t: Throwable) {
            FileResult(relative, lossless = false, errors = emptyList(), crash = "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun parseOrNull(corpus: File, relative: String): PsiFile? = try {
        createPsiFile(relative.substringAfterLast('/').removeSuffix(".flix"), File(corpus, relative).readText())
            .also { ensureParsed(it) }
    } catch (_: Throwable) {
        null
    }

    /** Concatenation of every leaf, which must equal the original text for a lossless parse. */
    private fun leafText(file: PsiFile): String = buildString {
        PsiTreeUtil.processElements(file) { element ->
            if (element.firstChild == null) append(element.text)
            true
        }
    }

    /** The token an error sits on, which is a far better failure grouping key than the offset. */
    private fun firstTokenAt(file: PsiFile, error: PsiErrorElement): String {
        val at: PsiElement? = file.findElementAt(error.textRange.startOffset)
        return at?.text?.trim()?.takeIf { it.isNotEmpty() } ?: "<end-of-file>"
    }

    private fun failuresByFirstErrorToken(results: List<FileResult>): List<Pair<String, List<FileResult>>> =
        results.filter { it.errors.isNotEmpty() }
            .groupBy { it.errors.first().token }
            .toList()
            .sortedByDescending { it.second.size }

    private fun writeReport(corpus: File, results: List<FileResult>) {
        val report = File("build/reports/flix-parser-corpus.json").apply { parentFile.mkdirs() }
        val clean = results.count { it.errors.isEmpty() }
        report.writeText(
            buildString {
                appendLine("{")
                appendLine("""  "corpus": ${quote(corpus.path)},""")
                appendLine("""  "roots": [${CORPUS_ROOTS.joinToString { quote(it) }}],""")
                appendLine("""  "files": ${results.size},""")
                appendLine("""  "clean": $clean,""")
                appendLine("""  "cleanRatio": ${"%.4f".format(clean.toDouble() / results.size)},""")
                appendLine("""  "crashed": ${results.count { it.crash != null }},""")
                appendLine("""  "lossy": ${results.count { !it.lossless }},""")
                appendLine("""  "failuresByToken": {""")
                appendLine(
                    failuresByFirstErrorToken(results).joinToString(",\n") { (token, group) ->
                        """    ${quote(token)}: { "files": ${group.size}, "examples": [${
                            group.take(3).joinToString { quote(it.path) }
                        }] }"""
                    },
                )
                appendLine("  },")
                appendLine("""  "failures": [""")
                appendLine(
                    results.filter { it.errors.isNotEmpty() || it.crash != null }.joinToString(",\n") { r ->
                        """    { "path": ${quote(r.path)}, "lossless": ${r.lossless}, "crash": ${
                            r.crash?.let { quote(it) } ?: "null"
                        }, "errors": ${r.errors.size}, "firstError": ${
                            r.errors.firstOrNull()?.let {
                                """{ "offset": ${it.offset}, "token": ${quote(it.token)}, "message": ${quote(it.message)} }"""
                            } ?: "null"
                        } }"""
                    },
                )
                appendLine("  ]")
                appendLine("}")
            },
        )
        println("[flix-corpus] report written to ${report.absolutePath}")
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""

    private data class FileResult(
        val path: String,
        val lossless: Boolean,
        val errors: List<ParseError>,
        val crash: String?,
    )

    private data class ParseError(val offset: Int, val message: String, val token: String)

    /**
     * Turns an absent corpus into a failure wherever it is supposed to be present.
     *
     * Locally, skipping is right -- most work on this plugin needs no Flix checkout. In CI it is
     * not: the workflow clones the pinned revision precisely so this gate runs, so an absent corpus
     * means that step silently failed. JUnit 3 has no skip state, so skipping would report the
     * strongest check in this suite as an ordinary pass, and the console line saying otherwise sits
     * in the output of a green build that nobody reads.
     */
    private fun failIfRequired() {
        val ci = System.getenv("CI").orEmpty().ifBlank { System.getenv("GITHUB_ACTIONS").orEmpty() }
        if (ci.isNotBlank()) {
            fail(
                "No Flix corpus checkout found, but CI is expected to provide one. The workflow " +
                    "clones the pinned revision before `check`; if that step changed or failed, " +
                    "this gate would otherwise be skipped and reported as a pass. " +
                    HOW_TO_POINT_AT_A_CHECKOUT,
            )
        }
    }

    companion object {
        /**
         * The corpus roots. Both are ordinary Flix source that upstream compiles; `main/test/flix`
         * is deliberately excluded because it contains deliberately-invalid fixtures whose parse
         * errors are the point.
         */
        private val CORPUS_ROOTS = listOf("main/src/library", "examples")

        /** A line-leading `def main`, optionally modified. Deliberately conservative; see usage. */
        private val DECLARED_MAIN = Regex("""^\s*(?:pub\s+)?(?:sealed\s+)?(?:lawful\s+)?def\s+main\s*\(""")

        /**
         * Corpus files that the Flix compiler itself cannot compile, keyed to the reason. They are
         * excluded from the ratio because a grammar that accepted them would let the IDE endorse
         * code the compiler rejects -- the opposite of what this gate is for. Every entry is
         * asserted to keep failing, so the list cannot rot.
         */
        private val UNCOMPILABLE_UPSTREAM = mapOf(
            "examples/apps/langcensus/src/Analyse.flix" to
                "uses `foreach (...) yield e`, which Flix does not implement: Parser2.foreachExpr " +
                "is `foreach forFragments expression` with no yield, and no ForEachYield node " +
                "exists anywhere in the compiler (verified against origin/master). It is the only " +
                "occurrence in all 898 tracked .flix files.",
        )

        /**
         * Ratchet, not an aspiration: raise it as defects are fixed, never lower it to make a
         * change pass. Every corpus file the compiler accepts must parse cleanly.
         */
        private const val MIN_CLEAN_RATIO = 1.0

        private const val SEE_REPORT = "See build/reports/flix-parser-corpus.json for the breakdown."

        private const val HOW_TO_POINT_AT_A_CHECKOUT =
            "Set -DflixCorpusDir=/path/to/flix or FLIX_DIR=/path/to/flix to run it."

        private val DEFAULT_LOCATIONS = listOf(
            "${System.getProperty("user.home")}/github.com/flix/flix",
            "${System.getProperty("user.home")}/flix",
        )

        internal fun locateCorpus(): File? =
            (listOfNotNull(System.getProperty("flixCorpusDir"), System.getenv("FLIX_DIR")) + DEFAULT_LOCATIONS)
                .map(::File)
                .firstOrNull { dir -> CORPUS_ROOTS.any { File(dir, it).isDirectory } }

        internal fun collectCorpusFiles(corpus: File): List<String> =
            CORPUS_ROOTS.flatMap { root ->
                File(corpus, root).walkTopDown()
                    .filter { it.isFile && it.extension == "flix" }
                    .map { it.relativeTo(corpus).path }
                    .toList()
            }.sorted()
    }
}
