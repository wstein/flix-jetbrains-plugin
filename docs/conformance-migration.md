# Runner adoption review

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
