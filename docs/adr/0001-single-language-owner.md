# ADR 0001 — One language owner and one LSP client

- **Status:** Accepted
- **Date:** 2026-07-27

## Context

Two IntelliJ plugins for Flix existed side by side:

| | `flix-jetbrains-plugin` (this repo) | `intellij-flix` |
| --- | --- | --- |
| Plugin id | `dev.wstein.flix-jetbrains-plugin` | `org.flixlang.intellij` |
| Language / PSI | none — TextMate coloring only | `Language("Flix")`, Grammar-Kit + JFlex, full PSI |
| Gutter run marker | none | `FlixRunLineMarkerContributor` |
| LSP client | LSP4IJ | native `com.intellij.platform.lsp` (optional) |
| Debugging | `--Xdebug` JDWP↔DAP bridge via LSP4IJ | none |

The immediate trigger was a missing gutter run arrow next to `def main`. A
`RunLineMarkerContributor` is handed a `PsiElement` to anchor on, and TextMate's
`EmptyLexer` collapses a whole `.flix` file into a single PSI element, so there is no
per-line element to attach an icon to. The gap was the absence of PSI, not a missing
contributor.

Two ways to obtain PSI were considered.

### Option A — port the Flix compiler's lexer and parser

Translate `Lexer.scala` and `Parser2.scala` (plus `TokenKind`, `SyntaxTree`, the error
model and support types) to Kotlin driving IntelliJ's `PsiBuilder`. `Parser2` is a
resilient LL parser whose `open`/`close`/`advance` event model maps almost one-to-one
onto `PsiBuilder.mark()`/`done()`/`advanceLexer()`, and its error recovery is a
first-class design goal.

Measured cost: roughly 7,200 hand-written lines, of which `Lexer.scala` (980) and
`Parser2.scala` (4,057) are genuine imperative control flow that cannot be generated.
It would still require the entire editor layer to be written from scratch.

### Option B — adopt `intellij-flix`'s language layer

Measured by running its generated parser over 781 real `.flix` files from the Flix
repository:

- 51.6% of files parse with zero `PsiErrorElement`;
- all failures trace to nine localized root causes;
- fixing the top four raises the pass rate to approximately 90%;
- the JFlex lexer maps 158 of 158 upstream `TokenKind` case objects exactly;
- feature probes for Datalog, effect handlers, restrictable enums, structs, extensible
  variants, regions and string interpolation all pass.

This is a real re-derivation with localized defects, not a shallow subset.

## Decision

Adopt `intellij-flix`'s language layer. This repository is the integration host.

1. Exactly one `Language("Flix")` exists: `org.flixlang.intellij.lang.FlixLanguage`.
2. Exactly one file type, parser definition, syntax highlighter, brace matcher,
   commenter, quote handler, folding builder and run-line-marker contributor are
   registered.
3. Exactly one LSP client is used: LSP4IJ. `intellij-flix`'s native
   `com.intellij.platform.lsp` integration is **not** imported — two clients would start
   two `flix lsp` processes against the same files.
4. Imported sources keep their `org.flixlang.intellij` package names so the layer stays
   reviewable against, and re-synchronizable with, its origin.
5. `intellij-flix`'s compiler downloader, settings and service are not imported;
   `FlixFork` remains the single compiler resolver.
6. The adoption is gated on a parser corpus evaluation
   (`docs/intellij-flix-parser-evaluation.md`). Below the gate threshold the decision is
   revisited rather than silently accepted.

## Consequences

- The ~7,200-line parser port is avoided. The cost of reviewing and maintaining the
  adopted grammar is not avoided.
- Registering a real `FileType` disables TextMate for `.flix`, so the adopted syntax
  highlighter must land in the same change. See Phase 7 of the implementation plan.
- Registering a real `Language` also removes seven LSP4IJ extension points that are bound
  to `language="TEXT"`/`"textmate"` — structure view, folding, code-block provider,
  parameter info, semantic-token file view provider, and type/call hierarchy. These must
  be re-registered for `language="Flix"` or the adoption is a net regression.
- `Flix.bnf` uses no `pin`/`recoverWhile`, so a syntax error currently truncates the rest
  of the file. This is tracked separately from adoption.
- Two plugin ids both named "Flix" could be installed together. Neither is published, so
  one is retired now rather than later.
