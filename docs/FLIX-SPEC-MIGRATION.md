# Migrating to Flix v0.77.0 and the current flix-spec

Status: **not started.** `language/build.gradle.kts` pins `flixSpecVersion = "0.75.8"` and
`gradle.properties` pins `flixCorpusCommit = 40949531...` (Flix v0.75.2), two releases behind.

## What changed in Flix

This repository is pinned to Flix **v0.75.2** (`40949531`). The reference has moved twice since.

### v0.75.2 → v0.76.0

- Effects accept type parameters. Generic *operations* remain invalid and now report
  `IllegalOperationTypeParams` rather than `IllegalEffectTypeParams`.
- Malformed `match` and `ematch` expressions retain the match node and the scrutinee through
  ordinary recovery instead of collapsing.
- **Vocabulary unchanged**: 191 TreeKinds and 158 TokenKinds, same names, same digests.

### v0.76.0 → v0.77.0

- **`+UsesOrImports.Package`** (TreeKind 191 → 192). `use` now recognises a package path:
  `use flixball::Game.Board` and `use flixball::{Game, Board}`.
- **`+ColonColonTight`** (TokenKind 158 → 159). `::` written **without surrounding whitespace** lexes
  as a distinct token. Tight `::` is the package-path separator; spaced `::` remains list cons.
  Writing the separator with whitespace is now a `Malformed` error.
- Nothing was removed or re-parented. Both releases are additive at the vocabulary level.
- Internally, Flix deleted its `Reader` phase and `shared.Input`. That broke `flix-spec`'s own
  adapter and is fixed there; it does not reach consumers.

> **`ColonColonTight` is the one that bites quietly.** Upstream left `("::", ColonColon)` in the
> lexer's operator table and decides tightness in hand-written dispatch outside every table. Nothing
> that scrapes or reflects over that table sees a change. A rule matching `ColonColon` today simply
> stops matching `a::b`, with no error anywhere.

## What changed in flix-spec

Beyond the pin, the release you are moving to changes four things that affect consumers.

**1. The transparency contract is stated per occurrence, and is much larger.**
It used to admit a kind only if *every* occurrence had at most one child. It now fires per
occurrence — dropped when empty, replaced when singular, kept when branching — which admitted four
kinds every structural consumer was already eliding for itself: `Expr.Expr`, `Pattern.Pattern`,
`QName`, `UsesOrImports.UseOrImportList`. A third rule, `elide-empty`, drops empty `AnnotationList`
and `ModifierList` without splicing their tokens.

Normalisation now removes **2301 of 4484 nodes (51.3%)**, up from 753 of 4398 (17.1%). Canonical
trees are substantially smaller and every baseline is stale.

Because the rules fire per occurrence, an elided kind is **not always absent**: `QName` survives
wherever a name is qualified (23 occurrences), and `ModifierList` wherever it holds a modifier (12).
Mappings onto those are legitimate, and `validateProjectionMap` now decides that by measuring
`fixtures/expected` rather than inferring it from the rule name.

**2. A fourth lane: `diagnostic_conformance`.**
It compares whether the same units are **rejected**, and whether each carries the same gated
`kind`/`line`. Accept/reject needs no tree, no projection map and no shared vocabulary. If your
diagnostic names are your own, declare `diagnosticMappings` in your projection map; without it the
lane compares accept/reject alone and says so. A consumer that emits no diagnostics at all is
`not-applicable`, not failed.

**3. Depth is published and can be gated.**
Reports now carry `nodesExpected` and `depthPercent` beside `nodesCompared`, and the CLI accepts
`--depth-floor` / `--recovery-depth-floor`. Report `schemaVersion` is **7**. A version-6 report's
depth was computed against the walk rather than the expectation — it read *highest* for the maps
that skipped most — so old and new depth figures are not comparable.

**4. `source_invariants` gained `token-positions`.**
Token `start`/`end` were schema-required and read by nothing. The lane now checks that each token's
text is what its source holds at those offsets, that tokens advance in order, and that what lies
between them is only whitespace or the `$` escape. It stands down for consumers that emit no tokens.

New projection-map keys, both optional: `dropWhenEmpty` (the consumer-side counterpart of
`elide-empty`) and `diagnosticMappings`.

## Target: 0.77.2

The one version to move to is **`0.77.2`**, the newest release on the flix-spec Maven repository.
`0.77.0`, `0.77.1` and `0.77.2` share one upstream pin (`4a5b60a31ac03bb762f68b554a0fc2b6f4d982b9`),
and each later release is additive for consumers, so there is no reason to stop at an earlier one.
The two sections below record what each release added, as history; neither is a separate step.

`v0.77.3` is tagged but not published (only its `-SNAPSHOT` is in the repository). Its data differs
from `0.77.2` only in the defect ledger's `schemaVersion` (1 → 2) and in `pin.json`'s description of
the reference entry point, so moving to it later is a coordinate bump for a consumer that reads
neither.

### What 0.77.1 added

It left the three vocabularies, the report `schemaVersion` 7 and both fixture forms unchanged, and
added:

- **`ast/annotation.json`** — the 16 annotations the reference defines, digest-pinned in `pin.json`.
  A third vocabulary, because the lexer emits a single `TokenKind.Annotation` for every one of them
  and the name survives only in the token's `text`, where no `TokenKind` digest can see it change.
  It is a **coverage** vocabulary and never a validity check: the token is genuinely open, because
  Java interop annotations lex identically and upstream models exactly that with
  `Annotation.Error`. 13 of the 16 occur in Flix's own 893-file corpus.
- **`ast/retired.json`** — vocabulary the reference once defined and has removed, with the tag each
  went at: `Decl.Law`, `KeywordLaw` and `KeywordLawful`, all gone at v0.75.2. An added kind appears
  in the inventory under a name you can look up; a removed one leaves only a digest that stopped
  matching, and this is what survives it.
- **The fixture suite is 147**, not 146 — one fixture covers the three annotations Flix's own
  corpus never exercises (`@Deprecated`, `@DontInline`, `@Skip`).
- **FLIX-0002 in the defect ledger.** flix-spec now runs `Weeder2` over its positive fixtures,
  advisory only, and the first run found a reference defect: `Parser2` has a dedicated
  `BinaryOp.NameMath` and lists `NameMath` in `FIRST_BINARY_OP`, so `a ⊆ b` parses cleanly into
  `Expr.Binary`, while `Weeder2`'s operator match omits `NameMath` and throws
  `InternalCompilerException`. Confirmed against the released jar, which prints the compiler's own
  bug-report banner. Nothing is required of a parser — the reference's own parser accepts the input
  and produces the tree flix-spec publishes — but it bounds what a *positive* fixture means here:
  it parses, and that is all it promises.

### What 0.77.2 added

Same upstream pin as 0.77.0 and 0.77.1. The three vocabularies, both fixture forms, all 147 fixtures
and the report `schemaVersion` 7 are unchanged, so a result *already measured* against 0.77.1 stays
valid on 0.77.2. That is a statement about consumers who measured 0.77.1, and this plugin has not:
it is still on 0.75.8, so the whole 0.75.8 → 0.77.2 step below has to be qualified here.

The one published change is `defects/ledger.json`, and one schema field moved with it:

- `defect-ledger.schema.json` replaces the required `review` (a date) with **`reviewedAtPin`** (the
  upstream commit an entry was last triaged against). Only relevant if you read that file; none of
  the consumers do today.
- Both entries now record their upstream search result, a review-ready draft, and a standalone
  reproduction you can run with only a JDK:
  [FLIX-0001](https://github.com/wstein/flix-repro-predicate-paramuntyped) ·
  [FLIX-0002](https://github.com/wstein/flix-repro-namemath-infix-crash).

**Why the field changed, since the reasoning may be worth borrowing.** The date gate failed the
build once it passed, which put a fuse in every tag: rebuilding `v0.77.0` or `v0.77.1` after
2026-11-01 would have failed, although nothing about those commits had changed and the artifacts
they published were still exactly what they published. Time passing is not evidence about a defect.
The oracle changing is — and it is the only thing that can make one of these entries stop being
true. Any ratchet you keep against a pinned input is better tied to that input than to a clock.

## What this repository must do

### 1. Move the pin

`language/build.gradle.kts` — `flixSpecVersion` to `0.77.2`.
`gradle.properties` — `flixCorpusCommit` to `4a5b60a31ac03bb762f68b554a0fc2b6f4d982b9`.
`FlixSpecConformanceTest.testPinMatchesLocalFlixCheckout` holds this from the plugin side and has
caught real mismatches twice, so it will stop you first.

### 2. Fix the Kotlin port's depth metric — it is publishing a wrong number today

`language/src/test/kotlin/org/flixlang/intellij/lang/Conformance.kt` computes

```kotlin
val encountered = nodesCompared + nodesUnmapped
return nodesCompared.toDouble() / encountered
```

That is the metric flix-spec retired, for the reason its own scaladoc gives: *the metric read
highest for the maps that skipped most*. The port has no `sizeOf` and no `expected` counter at all.

`1269 / (1269 + 89) = 93.4%` reproduces the 93% in the `FlixSpecConformanceTest` changelog.
`1269 / 2034 = 62%` is the same measurement against the expectation. The plugin is not at 93% depth;
it is at **62%**, behind tree-sitter's 95%.

Fix: accumulate `sizeOf(expT)` after transparency into a `nodesExpected` field and divide by it.
Then correct the two changelog entries that claim 93%.

### 3. Fix the divergence cap, which corrupts the node count too

`Conformance.kt:107` returns from `compare()` *before* `stats.inc("compared")` once
`MAX_DIVERGENCES_PER_FIXTURE` is hit, so the cap poisons `nodesCompared` — and therefore depth — as
well as the divergence count. flix-spec's own fix stops *recording* but keeps *walking*.

Latent at `DIVERGENCE_BASELINE = 4`; live the moment one fixture regresses past 20.

### 4. Implement the new contract semantics, or the port will disagree with the CLI

This is the part the port cannot inherit. The contract now fires **per occurrence**, and two
subtleties follow that a naive port gets wrong:

- **Arity counts tokens.** `Normalizer` counts a node's children *including token leaves*, so
  `QName` over `Ident .` is a two-child node and is kept. A comparison that drops tokens first sees
  one child and elides it. The port must carry a token count per node.
- **Splicing promotes tokens.** Splicing `TrailingDot` leaves its `.` a direct child of `QName`,
  which is then branching. That promotion must propagate.
- **`elide-empty`** drops a node only when it has no children at all — an empty `ModifierList` goes,
  a `ModifierList` over `pub` stays.

flix-spec's `Conformance.scala` is the reference for all three. **Consider deleting the port
instead:** the comparison closure is oracle-free (6 files, ~1530 lines, no `ca.uwaterloo` imports)
and this is its third drift.

### 5. Delete six now-redundant `elide` entries
Expect a `NOTE:` from `validateProjectionMap` naming `elide` as deprecated. The reduction below
does **not** clear it: the note fires for as long as the key is present at all, and three of these
nine entries (`CommentList`, `Expr.Statement`, `Type.Apply`) are genuinely yours, so the key stays.
What the reduction does is shrink it to entries the contract does not cover. The note lists those
separately, as candidates to argue into `ast/transparency.json` rather than as things to delete.


In `language/src/test/resources/conformance/projection-map.json`:

```
AnnotationList  Expr.Expr  ModifierList  Pattern.Pattern  QName  UsesOrImports.UseOrImportList
```

`CommentList`, `Expr.Statement` and `Type.Apply` remain yours. All four of your **mappings** onto
now-elided kinds (`AnnotationList`, `ModifierList`, `QName`, `UsesOrImports.UseOrImportList`) stay
valid — each survives in the canonical tree at some arity.

### 6. `::` and the package path

Grammar-Kit must distinguish tight `::` (package-path separator) from spaced `::` (cons), and
produce a node mapping to `UsesOrImports.Package` for `use flixball::Game.Board` and
`use flixball::{Game, Board}`.

### 7. Two lanes you are still not running

`docs/CONFORMANCE.md` in flix-spec records `recovery_conformance | fail (measured here; its own port
has no recovery lane)` — flix-spec measures that lane on this plugin's behalf, by hand, despite the
map declaring 13 `recoveryMarkers`. The new `diagnostic_conformance` lane is likewise unimplemented
in the port. Both come free if you call the published comparison instead of re-implementing it.

## Two guards worth adding while you are here

Neither is required by the release. Both close gaps this migration exposed.

### Emit only diagnostics the lexer or `Parser2` would raise

flix-spec's pipeline stops after `Parser2`: `ProjectionExtractor` collects
`lexerErrors ++ parserErrors` and nothing else, and `docs/CONFORMANCE.md` calls `Weeder2` errors
"out of scope by construction, not a gap".

So `diagnostic_conformance` compares against a **parse-phase-only** set. A spaced `::` reported as
`Malformed` is fine, because `Parser2` raises it. But every validation-level check you later write
into the projection output — duplicate modifiers, arity rules, anything `Weeder2` would own — adds a
diagnostic the canonical side does not have, and breaks `kind`/`line` agreement on exactly the
negative fixtures the lane is there to measure.

Tag each check with the phase that owns it: parse-phase diagnostics go into the projection,
validation-only diagnostics go to your CLI and stay out of it.

### Assert the vocabulary digests, not just the pin commit

`law` and `lawful` stopped being keywords at Flix v0.75.2 and went stale here without anyone
noticing, because a commit SHA moving tells you *that* the vocabulary changed, never *what*
changed — and nothing compared the names.

Record `treeKindDigest` and `tokenKindDigest` from `pin.json` alongside the pin you already track,
and fail on a mismatch. It costs two fields and forces a review at the next vocabulary change
instead of after it.

Two cheap follow-ons, now that `ast/retired.json` exists:

- assert that nothing in your keyword or token table matches a `Keyword*` entry in
  `ast/retired.json` — that pins the `law`/`lawful` class of staleness as a regression test;
- remember the digest cannot see an existing kind's *extension* being re-partitioned. It caught
  `ColonColonTight` only because a **new name** appeared. When a name is added, ask what it took
  from; the answer belongs in a fixture.
