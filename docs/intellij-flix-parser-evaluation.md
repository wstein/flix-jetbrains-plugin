# Parser corpus evaluation — `intellij-flix` Grammar-Kit/JFlex layer

Gate evidence for [ADR 0001](adr/0001-single-language-owner.md), which adopts `intellij-flix`'s
language layer instead of hand-porting the Flix compiler's `Lexer.scala` and `Parser2.scala`.

- **Adopted revision:** `intellij-flix` @ `b0f93e3ccd94c7583f8bd85961cd2b3bf581d028`
  (imported tree `375dcc6`, plus the grammar fixes below)
- **Corpus revision:** `flix/flix` @ `cc7c54950c4de214aa5777fd23ba53a59781c608` (`flixCorpusCommit`)
- **Harness:** `FlixCorpusTest`, which also writes `build/reports/flix-parser-corpus.json`

## Result

| | Before | After |
| --- | ---: | ---: |
| Files evaluated | 428 | 428 |
| Parse cleanly (no `PsiErrorElement`) | 211 (49.3%) | **427 of 427 (100%)** |
| Crashes, hangs, assertions | 0 | 0 |
| Non-lossless parses | 0 | 0 |
| Addressable `def main` entry points | 114 | **137** |
| Excluded as uncompilable upstream | — | 1 |

**Verdict: green.** Six localized defect families accounted for every one of the 217 initial
failures. None was structural, and fixing them required no change to the parser's architecture.

## Why this corpus is the right oracle

Every file under `main/src/library` (243) and `examples` (185) is source the Flix compiler accepts.
A `PsiErrorElement` on one of them is therefore a defect in the grammar by construction, with no
appeal to a second implementation. `main/test/flix` is deliberately excluded: it contains
intentionally-invalid fixtures whose parse errors are the point.

This matters because the pre-existing snippet tests could not make that claim. They were written
against the grammar, so they could only demonstrate self-consistency — and they did: 125 tests
passed while the grammar rejected half of all real Flix code. `FlixParsingTest.testEnumAndMatch`
even writes enum cases with separating commas, a form **no** multi-case enum in the Flix standard
library uses, because that is what the grammar required.

Three properties are checked per file. Losslessness and crash-freedom are absolute; the error-free
ratio is the ratchet.

- **No crash** — parsing must not throw, assert, or hang.
- **Lossless** — concatenating every leaf must reproduce the input byte for byte. Without this a
  parser that silently drops text would score as clean.
- **Error-free** — zero `PsiErrorElement`s.

Round-tripping alone would not be sufficient evidence: a parser that emitted every token as a direct
child of the root would satisfy it. `testEntryPointsAreAddressable` supplies the structural check
that matters for this plugin, asserting that every `def main` resolves to a `FlixDefDecl` whose name
leaf lies inside the declaration — the exact anchor `FlixRunLineMarkerContributor` uses to place the
gutter icon.

## The six defects

| Files | Trigger | Cause | Fix |
| ---: | --- | --- | --- |
| 103 | `\` | An effect binds to an arrow's *result*, but `BACKSLASH` was accepted only in `typeAndEffect`, the declaration-return position. `List.map`'s signature (`f: a -> b \ ef`) failed. | `arrowType` accepts a trailing `(BACKSLASH type)?` |
| 68 | `case` | `enumCaseList` required a separating `COMMA`. Not one multi-case enum in the standard library writes it. | separator made optional |
| 37 | `<-` | `foreach`/`forM`/`forA` fragment lists could not be parenthesized, which is how essentially all real code writes them. | optional parenthesized form |
| 5 | `=` | `counter->state = x` was swallowed by `unaryLambdaExpr` matching `counter -> state`, which then choked on `=`. | more specific `structPutExpr` ordered ahead of it |
| 3 | `{` | `match node->next { … }` was swallowed by the match-lambda alternative. | braced alternative ordered first |
| 1 | `ff` | `123ff`, `0ff`, `1f64` — `acceptNumber` sets `mustBeFloat` only on `.`/`e` but accepts float suffixes regardless, while `FLOAT_CORE` required a fractional or exponent part. | integer-shaped float-suffix rules |

The JFlex lexer needed almost nothing: it maps 158 of 158 upstream `TokenKind` case objects exactly,
including nested block comments, nested `${}` interpolation and the tight-versus-whitespace `->`
split. Only the numeric-suffix rule was wrong.

## The one exclusion

`examples/apps/langcensus/src/Analyse.flix` uses `foreach (…) yield e`. **The Flix compiler does not
implement that form.** Verified against pristine `origin/master`: `Parser2.foreachExpr` is
`foreach forFragments expression` with no `yield`, and no `ForEachYield` node exists anywhere in the
compiler. It is the only occurrence in all 898 tracked `.flix` files.

Teaching the grammar to accept it would make the IDE endorse code the compiler rejects, which is the
opposite of what this gate is for. It is therefore excluded by name, with the reason recorded in the
harness — and the harness asserts it *still fails*, so if upstream implements the syntax or fixes the
example, the test fails and the exclusion is removed rather than quietly outliving its justification.

## Known limitation

The plan called for a second oracle: running the real `Lexer`/`Parser2` and diffing token streams and
normalized declaration projections. That is **not implemented**. Building it requires a Scala/Mill
harness inside the Flix checkout, and the corpus already supplies the property that matters — a file
the compiler accepts must parse without errors — without a second implementation to keep in sync.

What a differential oracle would add is detection of trees that are *error-free but mis-shaped*.
`testEntryPointsAreAddressable` covers that for the one projection this plugin currently consumes.
It should be revisited if the plugin grows features that depend on finer-grained tree shape, such as
a structure view or find-usages.

## Reproducing

The corpus lives in a sibling Flix checkout that CI does not have, so the harness **skips** when it
is absent rather than failing.

```bash
./gradlew test --tests '*FlixCorpusTest*' -DflixCorpusDir=/path/to/flix
```

`FLIX_DIR` works too, and `~/github.com/flix/flix` is tried by default. Results land in
`build/reports/flix-parser-corpus.json`, which groups failures by the token the first error sits on
so a regression names the syntax family it broke instead of only a count.

## Residual risk

`Flix.bnf` uses no `pin` or `recoverWhile`, so a file yields exactly one error node and everything
after it becomes a single unparsed block. On a clean corpus this is invisible — the measurements
above are all on files that parse completely — but it degrades the editing experience on
half-written code, which is most code most of the time. Error recovery is tracked separately from
adoption; it does not affect this gate.
