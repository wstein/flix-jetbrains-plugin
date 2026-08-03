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
         *   - 39, entering the *negative*-fixture (malformed-input / error-recovery) divergences
         *     for the first time -- the first of these ratchets required reading Parser2.scala
         *     directly rather than inferring shape from fixtures alone, since flix-jetbrains-plugin
         *     `pin`/`recoverWhile` are the tools for this and using them safely requires knowing
         *     precisely what the reference recovers to. `qualifiedName` now consumes a genuinely
         *     dangling trailing `.` (lexed as its own DOT_WHITESPACE token whenever whitespace/EOF
         *     follows the dot, per `_Flix.flex`'s `"." / {WHITE_SPACE_CHAR}` rule, distinct from
         *     plain DOT) into a new nested `TrailingDot` node, matching Parser2.scala's
         *     `nameAllowQualified`: it treats DotWhiteSpace as an unconditional trailing-dot error
         *     case regardless of the `allowTrailingDot` flag that governs plain DOT for `use`/
         *     `import`'s own `.{...}` continuation. Safe as a change to the one shared rule since
         *     DOT_WHITESPACE cannot appear in any of qualifiedName's other call sites' valid
         *     continuations (none tolerate whitespace before the dot).
         *     Two further attempts this round were tried and reverted, not landed: adding
         *     `{pin=1}` to `argumentList` (to match Parser2.scala's `arguments()`, which never
         *     requires a closing `)` to succeed) broke `testUnclosedExtTagArgsRecovers` in
         *     FlixRareSyntaxRecoveryTest -- a truncated `xvar A(` swallowed the *following*
         *     top-level declaration instead of leaving it for `declaration`'s own
         *     `declarationRecover`. Adding a matching `recoverWhile` (stop at COMMA/PAREN_R/
         *     declarationStart) was worse: 14 previously-passing FlixRareSyntaxTest cases started
         *     reporting spurious errors on ordinary, well-formed argument lists. `argumentList` is
         *     shared across five call-site shapes, some optional/speculative, and is evidently not
         *     safe to pin as a single shared rule -- unlike `qualifiedName` above, whose fix touched
         *     only a genuinely unambiguous token. 117/136 agree.
         *   - 31, after finding the shared root cause behind nearly every remaining "whole
         *     declaration collapses to a bare ErrorTree" divergence: `defDecl` itself had no pin.
         *     `declaration`'s own `pin=3` (see its comment above) only protects declarationBody's
         *     three direct children from vanishing -- it does nothing for a failure many levels
         *     down inside `statement`'s own descent (e.g. an unterminated char/string/regex/builtin
         *     literal, whose lexeme starts no known expr alternative), which backtracks
         *     defDecl -> declarationBody -> declaration in full before the outer pin ever gets a
         *     chance to matter. Added `{pin=9}` to `defDecl`, committing once `EQUAL` (the 9th
         *     element in its sequence) has matched, verified against flix-spec's own reference tree
         *     for lexical__unterminated-char.flix (which keeps every other Decl.Def child intact
         *     and only wraps the body's ErrorTree). Unlike both argumentList attempts above, this
         *     one is clean: no new failures across the full 428-file corpus or either rare-syntax
         *     test suite. Fixed six fixtures outright (unterminated-builtin/char/
         *     string-is-a-lexer-error, bang-caret-dollar-have-no-standalone-meaning,
         *     free-dot-and-unexpected-char, static-lowercase-is-reserved-but-unused) and reduced
         *     several more from a full ErrorTree collapse down to a narrower arity mismatch
         *     (doc-comment-misplaced-before-paren, trait-and-instance-with-an-operator-signature,
         *     anonymous-class-with-methods-and-a-constructor, malformed-tuple-reaches-
         *     enclosing-brace, match-rule-wrong-arrow, missing-semicolon-before-let,
         *     numeric-literal-errors, unterminated-regex, unterminated-string-interpolation,
         *     operator-error, effect-annotation-wrong-slash, unclosed-paren) -- real progress on
         *     each even though they still diverge. 123/136 agree.
         *   - 30, after giving `typeAndEffect` an explicit alternative for `/` where `\` was meant
         *     (`def f(): Unit / IO = ...`). Unlike everything else on this list, this one is not
         *     generic recovery: Parser2.Type.typeAndEffect special-cases this exact typo directly
         *     (`ParseError.ExpectedBackslashGotSlash`), consuming the `/` and still parsing the
         *     effect type normally rather than losing the suffix. `typeAndEffectSlashError`
         *     deliberately isn't `private`: its only content is the SLASH token, which (like every
         *     bare token) carries no `kind` and is already invisible to the comparison, so a real
         *     node is what makes it land as the reference's empty ErrorTree sibling instead of
         *     vanishing into typeAndEffect's `flatten`ed children. 124/136 agree.
         *   - 21, reversing the larger half of simplification #4 (see header): `statement`
         *     replicates Parser2.Expr.statement()'s notBinaryOperator / canFollowBinaryOperator
         *     recovery, the single highest-blast-radius change on this branch since `statement` is
         *     reached from every function/lambda/block body in the whole grammar -- riskier even
         *     than the two reverted `argumentList` pin attempts above, because a mistake here could
         *     corrupt ordinary, well-formed multi-declaration files rather than just one malformed
         *     construct. Concretely: LET_KW/FOREACH_KW/DISCARD_KW can never continue an expression,
         *     so their presence where SEMI was expected unconditionally means a forgotten
         *     semicolon (`missingSemicolonError`, an empty ErrorTree); anything that could
         *     plausibly be a binary operand instead means a forgotten *operator*
         *     (`statementOperand`/`implicitOperator`, an empty Operator[OperatorError] node,
         *     producing Expr.Binary). Both gates are genuine zero-width lookahead (Grammar-Kit's
         *     `&`, matching PEG's and-predicate -- verified against the generated parser's `_AND_`
         *     marker before trusting it, the same discipline as verifying `left`'s `_LEFT_` marker
         *     earlier on this branch), not a bare token reference, so the gating token itself is
         *     left for the following `expr` to consume normally.
         *     The binary-operand gate deliberately excludes DEF_KW (among others), identified
         *     *before* writing any code, not discovered via a failing test: `localDefExpr` can
         *     start an expr, so an ungated version would parse `def bar(): Int32 = 2` immediately
         *     following `def foo(): Int32 = 1` as a nested local def *inside* foo's body instead of
         *     the next top-level declaration -- corrupting the single most common shape in any
         *     multi-function file. Verified clean against the full 428-file corpus and both
         *     rare-syntax test suites regardless, same as everything else on this branch.
         *     Not modeled: the reference's line-sensitivity for the binary-operand case (same
         *     line -> Binary/OperatorError; different line -> the semicolon-substitution shape
         *     instead). This grammar has no line-sensitive lookahead, so the binary-operand
         *     recovery fires the same way regardless of line -- acceptable since the whole path is
         *     unreachable for well-formed input and only changes an already-malformed parse's
         *     shape, not whether it succeeds; no fixture currently exercises that distinction.
         *     Fixed both operator-error.flix and expressions__missing-semicolon-before-let.flix
         *     outright. 126/136 agree.
         *   - 19, after giving `matchRule` an explicit alternative for `=`/`->` typo'd for `=>`
         *     (`case Foo = true` / `case Bar -> false`). Same shape as the earlier typeAndEffect
         *     slash-typo fix, not generic recovery: Parser2.Expr.matchRule special-cases both
         *     mistakes directly (ExpectedArrowThickRGotEqual / ExpectedArrowThickRGotArrowThinR),
         *     consuming the wrong token and still parsing the rule body normally. Both branches
         *     produce the same empty ErrorTree regardless of which wrong token fired (confirmed
         *     against flix-spec's own reference tree before writing the fix), so one unified
         *     `matchRuleArrowError` alternative covers both. The same pattern also exists in
         *     `extMatchRule`/`selectRule`/`catchRule` in Parser2.scala, left unchanged here since no
         *     fixture currently exercises those positions. Fixed
         *     expressions__match-rule-wrong-arrow.flix outright. 127/136 agree.
         *   - 17, after giving `_Flix.flex` an explicit fallback rule for an unterminated regex
         *     literal (`regex"unterminated`, no closing quote before EOF). A lexer-level fix, not a
         *     grammar one: without it, the strict `regex"..."` rule (which requires a real closing
         *     quote to match at all) simply fails, so JFlex falls through character-by-character --
         *     "regex" alone is a valid NAME_LOWERCASE identifier, so it gets lexed and later parsed
         *     as an ordinary name expression before the broken remainder fragments the tree deeply.
         *     Unlike char/string literals, which already collapsed cleanly into one PsiErrorElement
         *     by incidence (not by any deliberate matching rule -- see Flix.tokens.txt's own "not
         *     fully modeled in this port" note), regex's five-letter prefix was long enough to
         *     parse as something real first. The new rule only ever fires as JFlex's own
         *     longest-match fallback when the strict rule already failed to match anything, since a
         *     real closing quote always makes the strict rule's match longer -- the same resolution
         *     mechanism already relied on for DOT vs DOT_WHITESPACE earlier on this branch, not a
         *     new technique. Verified clean against the full 428-file corpus (a lexer change risks
         *     legitimate regex-literal tokenization broadly, not just this one fixture) and both
         *     rare-syntax test suites. Fixed lexical__unterminated-regex.flix outright. 128/136
         *     agree.
         *   - 14, after a second, more general _Flix.flex fallback covering all seven
         *     numeric-literal-error shapes bundled into lexical__numeric-literal-errors.flix at
         *     once (1_, 0xZ, 0x1i99, 1i99, 1.5i32, 0x1g, 1x). Grounded directly in Lexer.scala's
         *     acceptNumber() doc comment: "any characters in [0-9a-zA-Z_.] following a number
         *     should be treated as an error part of the same number" (isNumberLikeChar =
         *     digit|letter|'.'|'_'). Every strict numeric rule requires a specific, complete
         *     shape, so trailing garbage a strict rule can't absorb (a lone trailing '_' not
         *     followed by a digit, a non-hex digit after '0x', an unrecognized suffix, an int
         *     suffix on a float, ...) simply isn't part of that rule's match, leaving JFlex to
         *     re-lex the garbage as separate, unrelated tokens instead of one error. One rule,
         *     `{DIGIT}[0-9a-zA-Z_.]*` -> BAD_CHARACTER, covers all seven: since every valid
         *     suffix/digit/dot is itself within that same character class, it always *ties* in
         *     length with whichever strict rule matched a well-formed literal in full, and ties
         *     resolve to the first-listed (strict) rule -- so it only ever wins, and only ever
         *     fires, when a strict rule left something behind. Same JFlex longest-match
         *     resolution as DOT_WHITESPACE and the unterminated-regex fallback above, not a new
         *     technique, just applied more broadly. Verified clean against the full 428-file
         *     corpus (this touches every numeric literal in the grammar) and both rare-syntax
         *     test suites. Fixed lexical__numeric-literal-errors.flix outright -- all seven
         *     sub-cases at once. 129/136 agree.
         */
        private const val DIVERGENCE_BASELINE = 14
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
