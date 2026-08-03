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
 * This is the consumer half of the conformance check. `flix-spec` owns the canonical `TreeKind`
 * vocabulary and the fixtures; this side owns the comparison algorithm ([Conformance], ported from
 * `flix-spec` since the published artifact is data-only), the projection map from this grammar's own
 * PSI kinds onto that vocabulary (`conformance/projection-map.json`), and producing trees in the
 * canonical shape to compare.
 *
 * Why this exists alongside [FlixCorpusTest]: that test gates on a parse-success *ratio* over
 * whichever Flix checkout happens to sit beside this repository, skipping entirely when none is
 * found. A ratio cannot distinguish "parsed" from "parsed correctly", and an unpinned corpus means
 * the number moves when someone else's working tree moves. The fixtures here arrive from a
 * versioned Maven artifact pinned to a known revision of the Flix reference compiler, so the same
 * input produces the same expectation on every machine and in CI.
 *
 * The projected trees are written to `build/flix-spec-projection/`. [testConformanceAgainstReference]
 * feeds them to [Conformance] and ratchets the divergence count via [DIVERGENCE_BASELINE].
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
         *   - 127, after flix-spec mapped IDENT/TYPE_VARIABLE and fixed a wrong TYPE_PARAMETER
         *     guess (flix-spec 0.75.3). 76/136 agree, depth 93%. Agreement dropped from an
         *     intermediate 80/136 in the same change that raised depth -- comparing deeper found
         *     real divergences a shallower comparison never reached, same pattern as every prior
         *     step here.
         *   - 106, after also un-privating `typeVariable`'s reachability (it existed and was
         *     already public, but listed after `qualifiedTypeName` in an ordered choice, so it
         *     never fired) and giving every precedence level's operator token its own wrapper rule
         *     (flix-spec 0.75.4): the reference wraps literally every operator -- `<+>`, `:::`,
         *     `instanceof`, not just the two positions found first -- as its own `Operator` node.
         *     84/136 agree, 1019 nodes compared. The Operator divergence category is now empty.
         *   - 96, after making `arrowType`'s effect suffix right-associative and recursive through
         *     `type` instead of a flat trailing suffix on the whole arrow chain -- see `arrowType`'s
         *     own comment in Flix.bnf for why the flat version, despite parsing every real corpus
         *     file, gave a non-arrow type with a bare `\ effect` suffix (`Unit \ IO`) the wrong
         *     shape (Type.Function instead of two separate Type.Type/Type.Effect siblings).
         *     Verified against the 428-file corpus test, not just these 136 fixtures, since an
         *     earlier version of this exact rule broke 103 of those files -- still 100.0% clean
         *     after this change. 84/136 agree (unchanged), depth 93% (unchanged).
         *   - 91, after un-privating `enumSingletonBody` (covers both the singleton short-hand and
         *     the regular per-case payload, which share the same reference CaseBody kind) and
         *     fixing BLOCK's own instance of the ignored-vs-elide bug (flix-spec 0.75.5): `{ ...
         *     }` always wraps exactly one `statement`, so it was always spliced away, even though
         *     the reference's Expr.Block is a real node regardless of statement count.
         *     88/136 agree, 1024 nodes compared.
         *   - Still 91, after also giving every *type*-level operator (arrow, rvadd/rvsub, rvand,
         *     +/-, &, xor, or, and, unary ~/not/rvnot) its own Operator wrapper rule, the same
         *     generalization as the expression-level fix but one level further (flix-spec 0.75.6).
         *     No measurable effect on this corpus yet -- the affected positions are not currently
         *     reached by the comparator, blocked by other, unrelated divergences deeper in the same
         *     fixtures -- but the map should describe the real structure, not just score well
         *     against what today's divergence set happens to reach.
         *   - 90, after splitting `enumDecl` into `restrictableEnumDecl`/`plainEnumDecl`: one
         *     native kind can't produce two different reference TreeKinds (Decl.RestrictableEnum
         *     vs Decl.Enum), which depend purely on whether RESTRICTABLE_KW was present, so the
         *     old single ENUM_DECL always matched Decl.Enum, wrongly, for restrictable enums
         *     (flix-spec 0.75.7). `enumDecl` itself stays `private` so the split adds no extra
         *     wrapper level. 89/136 agree, 1038 nodes compared.
         *   - 86, after fixing the ARROW_TYPE guess (Type.Function -> Type.Binary, the same
         *     guessed-from-the-name mistake TYPE_PARAMETER made earlier -- neither kind is ever
         *     actually produced by any expected tree in `fixtures/expected`) and, separately,
         *     moving the projection map itself from flix-spec's `ast/projection/flix-jetbrains-plugin.json`
         *     into this repository as `conformance/projection-map.json`. The map's content is
         *     unchanged by the move -- it is knowledge about this grammar, not the reference, so it
         *     now lives next to the grammar changes it tracks instead of forcing a flix-spec release
         *     for a consumer-only edit; `flixSpecVersion` is pinned back to 0.75.1, the only version
         *     flix-spec still publishes. 92/136 agree, 1055 nodes compared, depth 92%.
         *   - 51, after four map-only fixes to conformance/projection-map.json, none touching
         *     Flix.bnf. All four are the PARAMETER_LIST/ignored-vs-elide class or its mirror image:
         *     TYPE_AND_EFFECT moved from `ignored` to `flatten` -- elision only fires at arity <= 1,
         *     so a def *with* an effect (`Unit \ IO`, two TYPE children) kept TYPE_AND_EFFECT as a
         *     real wrapper the reference has no counterpart for at all, while a def *without* one
         *     (arity 1) elided correctly -- the asymmetry alone was worth 16 divergences and flipped
         *     8 fixtures to full agreement, the single largest jump measured against this map.
         *     VARIABLE_PATTERN moved from `ignored` to a direct `Ident` mapping -- the mirror-image
         *     bug: at arity 0 (`variablePattern` matches a bare token, no composite child) the
         *     elision chain-follow found nothing to replace it with and deleted the node outright,
         *     rather than collapsing to Ident the way the reference's Pattern.Variable-wrapping-Ident
         *     does. Fixing this by widening `variablePattern` to route through `ident` was tried
         *     first and reverted: `ident`'s token set overlaps tagPattern's first token and dropped
         *     the 428-file corpus to 49.2% (`use` statements failing several lines later) -- the
         *     map-only fix has no such risk, since both sides collapse to the same arity-0 Ident
         *     once each side's bare-token child is filtered out. DEBUG_INTERPOLATOR_EXPR and both
         *     CHECKED_EFFECT_CAST_EXPR/CHECKED_TYPE_CAST_EXPR moved from `ignored` to `mappings`,
         *     the ordinary PARAMETER_LIST-class fix: each always wraps 0 or 1 children by
         *     construction, so `ignored` always fired, even though none of the three reference
         *     kinds are in `elide`. Also fixed, unrelated to the map: `recordType`'s alternatives in
         *     Flix.bnf could all match empty, so bare `{}` was always consumed as an empty record
         *     before `effectSetType` ever got a turn -- Parser2.scala's recordOrEffectSetType()
         *     special-cases exactly this input as an effect set. Restructured to require a bar or a
         *     field, verified clean against the 428-file corpus. 109/136 agree, 1144 nodes compared,
         *     depth 93%.
         *   - 46, after restructuring every binary precedence level (9 expression, 7 type) from
         *     Grammar-Kit's flat `sub (op sub)*` repetition to `sub subTail*` / `left subTail ::=
         *     op sub`. The flat shape puts every repetition as a flat sibling under one node
         *     (`a + b + c` -> one 5-child ADDITIVE_EXPR), but the reference's Pratt loop closes a
         *     new Expr.Binary per operator (`Binary(Binary(a,+,b),+,c)`) -- confirmed via
         *     declarations__definitions-may-be-named-by-a-user-defined-opera.flix's own `x + y + 1`
         *     body. A naive self-recursive rule (`additiveExpr ::= additiveExpr op x | x`) was
         *     tried first and is NOT what `left` replaces it with: that naive form parsed a
         *     synthetic 8-operand chain of bare identifiers in single-digit milliseconds but hung
         *     indefinitely on the very first real corpus file (`Abort.flix`), reproduced down to a
         *     minimal case (`bold(red(fromString(m))) + nl + header + nl + trace` -- several
         *     levels of nested-call operand chained with plain identifiers, ordinary code, not a
         *     contrived edge case). Grammar-Kit's actual documented mechanism for this
         *     (README.md's `left` rule modifier: "take an AST node on the left (previous sibling)
         *     and enclose it by becoming its parent") avoids the problem entirely -- parsing stays
         *     flat and iterative (a plain generated `while` loop), and tree nesting is an O(1) PSI
         *     marker-reparenting step, the same mechanism every JetBrains-authored Grammar-Kit
         *     grammar uses for binary expressions. Every level was verified together against the
         *     same full 428-file corpus run (still fast: single-digit seconds, unchanged from
         *     before) and the complete 96-test suite (95 pass; the one failure is the pre-existing,
         *     unrelated local-checkout pin mismatch). 111/136 agree, 1178 nodes compared, depth
         *     93%. `consExpr` (`::`/`:::`) and `arrowType` (the effect-suffix backslash) needed no
         *     change: both are already right-recursive through the *next* rule, not
         *     self-recursive, which is ordinary recursive descent with no left-recursion risk.
         *   - 40, after clearing every remaining *positive*-fixture divergence bar the one
         *     documented in KNOWN_DIVERGENCES. Six independent fixes, each the ordinary
         *     PARAMETER_LIST-class bug or the enumDecl-class one-native-kind-two-reference-kinds
         *     split, none touching the precedence chain: GUARD (`if (cond)` in a Datalog rule
         *     body) moved from `ignored` to `mappings` -- always arity 1, so always elided, even
         *     though the reference's Predicate.Guard is real regardless. `latticeTermExpr`/
         *     `latticeTermPattern` (the `; term` tail in `P(x; term)`) un-privated -- inlined with
         *     no node of their own, but the reference wraps the tail in a mandatory
         *     Predicate.LatticeTerm. `fixpointSolveExpr` split into `provenanceSolveExpr`
         *     (`psolve`) / `projectSolveExpr` (`solve`) -- one native kind closing two reference
         *     kinds (Expr.FixpointSolveWithProvenance vs. Expr.FixpointSolveWithProject) purely on
         *     which keyword was present. `invokeSuperExpr` split into `invokeSuperMethodExpr` /
         *     `invokeSuperConstructorExpr` the same way (`super.m()` vs. bare `super()`). `type`'s
         *     kind-ascription tail (`expr : Kind`) moved from `(COLON kind)?` to `left
         *     typeAscribeTail ::= COLON kind` -- the highest-blast-radius change of the six, since
         *     `type` is the entry point for every type position in this grammar, but safe by the
         *     same reasoning as the precedence-chain fix: parses `arrowType` exactly once (no
         *     dispatcher-fallback re-parse), verified against the generated code before trusting
         *     it. `parenOrTupleOrLambdaExpr`'s plain-paren case (`(u - f)`, no ascription or tuple
         *     suffix) split out into its own `parenExpr` rule -- folded into the same wrapper as
         *     unit/operator-section/lambda, so arity 1 always elided it even though the
         *     reference's Expr.Paren is real there. Each verified individually against the full
         *     428-file corpus before moving to the next. 116/136 agree, 1200 nodes compared, depth
         *     94%.
         */
        private const val DIVERGENCE_BASELINE = 40
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

    /**
     * `conformance/projection-map.json`, committed in this repository rather than read from the
     * flix-spec artifact.
     *
     * The map from this consumer's PSI element types onto canonical TreeKind names is knowledge
     * about this grammar, not about the reference: every entry, `ignored`/`elide` decision and note
     * here exists because of something specific to `Flix.bnf`. flix-spec still owns the canonical
     * `TreeKind` vocabulary, the fixtures and the comparison algorithm ([Conformance]) that this map
     * is fed into -- moving only the map keeps a projection-map-only change from forcing a flix-spec
     * release, and keeps the map's edit history next to the grammar changes it tracks.
     */
    private fun loadProjectionMap(): Conformance.ProjectionMap {
        val text = javaClass.getResourceAsStream("/conformance/projection-map.json")
            ?.use { it.readBytes().decodeToString() }
            ?: error("missing test resource conformance/projection-map.json")
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
