# Runner adoption review

## Flix 0.80 migration

The parser recognizes module `val` declarations and reserves `val` as a keyword;
`valve` remains an identifier and local `let` remains unchanged. This is a parser
claim: the Flix 0.80 compiler reports an internal error for a reference to a module
value past kinding, so parsing the declaration does not establish end-to-end support.
The 0.80 subeffecting default changes type checking and does not change this grammar.

The optional test-event wire check targets the pinned `wstein/flix-fork` compiler,
not the official release jar. The official 0.80 jar was tested and rejected
`--events-json` and `--filter`; the official 0.79 jar rejects the same options.
Both runs failed and do not qualify the fork protocol. CI builds fork commit `494e63251202c8d8999459132d17e9183d4a67a2`
and runs the integration test against that assembly.

The invalid `def val(): Int32 = 42` is rejected by both parsers. Their recovery
trees differ: the reference retains incomplete definition and value declarations,
while this parser rolls back the definition. This adds exactly one structural and
two recovery identities in the full 168-fixture comparison. The full comparison
measures 64%/65% structure/recovery depth and retains exact identities at floors
63%/65%. A required separate comparison of the 167 other fixtures, including
the valid value declaration, retains the previous three/two identities and
63%/67% floors; it measured 1548/2424 and 236/351 nodes respectively. Both
comparisons are fixture-revision-bound and must pass. The exception is limited
to recovery shape and does not make the reserved name valid.

Against Flix commit `0c641c73c4635dee992d01c4364f224ea56d9a46`, the library
and examples corpus has 420 files: all 420 parse without errors or text loss,
with no crashes or exclusions. It excludes intentionally invalid test sources.

## Flix 0.79 migration — 2026-11-09

The 0.79 data/runner/plugin pin was the exact, SHA-256-qualified published
`0.79.0-20261109.125843-3` snapshot documented in the 0.79 migration.
It is not a claim that stable 0.79 artifacts are available.

The grammar accepts full type expressions in all three schema/extensible tails.
Missing tails retain their type alias through recovery. Match/ematch without a
body retain the match node and scrutinee, including before the next declaration.
The producer reconstructs comment runs from real AST leaves and offsets, groups
leading documentation into the next documentable declaration, and retains every
real lexer leaf in source order. It never reads expected trees to produce output.

The real 166-fixture pilot improves structure from the old allowance 4 to 3
remaining differences and recovery from 6 to 2. Depth floors tighten from 36%/40%
to 63%/67%. Source token accounting and token positions pass. Exact remaining
identities in `accepted.json` are bound to the new fixture revision:

- Structure: retired-law recovery, operator-named trait signature, trailing
  semicolon after a use/import declaration (3).
- Recovery: retired-law recovery and unterminated string interpolation (2).

These are preserved known gaps, not new free allowances. Diagnostic conformance
remains explicitly not applicable because this producer emits no parser/lexer
diagnostics; neither diagnostic kind/line fidelity nor lexical equivalence is
claimed. Tree structure and source-fidelity checks remain separate.

The following 0.75/0.77 migration record is historical.


Data stays at **0.75.8 / Flix v0.75.2**, runner/plugin **0.77.4**, report schema 9.
The adapter emits real AST nodes and lexer leaves, not canonicalized/synthetic
trees. Token accounting and token positions pass on all 138 fixtures. Native
lexer vocabulary and lexical correctness are not independently validated.

## Structural adapter corrections

The pilot's 13 differences return to **4**, with 1271 nodes compared (previously
1264), without raising the existing structural allowance:

- `PsiElement.children` discarded lexer leaves. Walking the AST preserves text,
  offsets and token arity, including `TRAILING_DOT`'s dot; `QName` now agrees.
- Debug interpolation's native wrapper maps to `Expr.Expr` at this data pin.
  Its real `d` token keeps a two-child node from being elided as a single child.
- Canonical `CommentList` is explicitly flattened. Grammar-Kit attaches comments
  as trivia, not comment-group composites (Flix.bnf simplification #1). Comment
  text/ranges remain measured, and the depth denominator is not reduced.

The real-token pilot also exposed a runner bug: an end-exclusive position after
a final newline was rejected. flix-spec commit `411bd32` fixes it and tests both
LF and CRLF boundaries, including invalid columns/lines beyond EOF.

## Reviewed differences

`language/src/test/resources/conformance/accepted.json` binds all ten identities
to this consumer and fixture revision
`21d3006f7299c0fe2cd97a4dd48f8d231299b662ca977e9414f1157ffa4ca49d`.
These are retained limitations, not claims of equivalence to the reference.

| Fixture | Structure / recovery identities | Decision |
| --- | --- | --- |
| `declarations__doc-comment-misplaced-before-paren` | 1 / 4 | Comments are parser trivia here. The reference rejects the misplaced doc comment and constructs a different condition with recovery nodes; PSI parses the parenthesized condition normally. Keep this documented architectural difference, rather than manufacture reference errors in the adapter. |
| `declarations__law-declarations-and-lawful-modifier-are-rejected` | 1 / 1 | Both parsers reject the removed syntax. Reference recovery retains a declaration-shaped subtree; PSI's top-level recovery leaves error/trivia tokens. No reference-shaped declaration is synthesized. |
| `lexical__unterminated-string-interpolation` | 0 / 1 | The reference adds a top-level ErrorTree containing a synthetic Err at EOF. The native lexer/parser has no corresponding top-level node; preserve its actual recovery instead of adding a phantom tree. |
| `declarations__trait-and-instance-with-an-operator-signature` | 1 / 0 | Retain the pre-existing structural allowance for malformed signature/member recovery. This is a known parser limitation, not a new allowance granted by runner migration. |
| `declarations__uses-and-imports` | 1 / 0 | Retain the pre-existing defect: optional semicolons terminate the reference's use declaration but cause PSI recovery. The separate exact parse-failure set still tracks this positive fixture. |

Recovery's initial count ceiling is 6, but only these six exact identities are
allowed; structure remains capped at 4 and likewise identity-gated. Depth floors
are now 36% structure and 40% recovery, measured against the full reference tree.
The map remains partial; lower disagreement counts cannot excuse shallower output.
Diagnostics remain explicitly unmodeled. Resolving an accepted entry must be
reviewed and removed in its fixing commit; the runner never removes it automatically.
