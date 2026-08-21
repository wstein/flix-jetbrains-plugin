# Syntax highlighting

Two layers paint a `.flix` buffer, and they are not interchangeable.

| Layer | Where | Needs | Knows |
| --- | --- | --- | --- |
| `FlixSyntaxHighlighter` | `language` | nothing | one token at a time |
| `FlixControlFlowAnnotator` | `language` | the parser | which production a token belongs to |
| `FlixSemanticFallbackAnnotator` | `language` | the parser | which names a file *declares* |
| LSP semantic tokens | `backend`, via LSP4IJ | the language server | what a *symbol* means |

The first three must work in an IDE with no LSP4IJ, and before the server has answered anything.
That is the whole reason the `language` module exists, and it is the case that was broken.

## Roles the lexer cannot see

A token has a kind; a name has a *role*. `greet` is a token of kind `NAME_LOWERCASE` whether it is a
function, a parameter or a local, so the lexical layer leaves every name the colour of plain text.
`FlixSemanticFallbackAnnotator` colours the ones the **parser** already knows: the name a `def`
declares, a parameter, a `let` or `match` binding, an `enum`, its cases, a `struct` and its fields,
a `trait`, an `eff`, a type alias and a type parameter.

Declarations, and one kind of use: a **call**. The grammar settles that one without resolution —
`postfixExpr` is a head followed by suffixes, and an argument list is one of them, so a call is a
head whose *next sibling* is an argument list. The next-sibling part is load-bearing: `r#fn(1)`
selects a field and then calls it, and the argument list does not belong to `r`.

Two calls are deliberately left uncoloured, because the server disagrees and it is right:

| Call | Server | Here |
| --- | --- | --- |
| `helper(n)`, `List.length(xs)`, `g(n)` where `g` is a local | `Function` | the same |
| `Some(4)`, `Colour.Shade(3)` | `EnumMember` | nothing |
| `f(n)` where `f` is a parameter | `Parameter` | nothing |

An enum case is told apart by its capital, since Flix names cases `Some` and definitions `length`.
A parameter is found in the enclosing parameter lists, which the PSI has — including a lambda's,
since `lambdaExpr` is a parameter list too. Both are left uncoloured rather than coloured
differently: a wrong colour is read as information, an absent one as "not yet".

A **record label** is the second use, and the grammar settles it even more cleanly: `#` appears in
exactly one production, `postfixSuffix ::= HASH NAME_LOWERCASE`. Every other `#` in Flix is a token
of its own — `#{`, `#(`, `#|`, `Array#` — so a `HASH` followed by a name is a label whatever the
receiver turns out to be. It is read off the **leaves**, because that suffix is a private rule and
`inv#pos#x` is two labels under one postfix expression; a rule matching the expression would colour
one. Labels being *written* (`{ a = 1, +b = 2, -c | r }`) come from `recordOp`, which is a node.

Two label positions are deliberately left alone:

- a **record pattern**'s. The server emits `Property` there too, but over `RecordLabelPattern`'s own
  location, which `Weeder2` sets to the whole `x = p` — the sub-pattern included. Colouring the
  label alone would be right and would still not match; imitating the span would spread a field
  colour across a binding.
- a **record type**'s (`{x = Int32}`). The server emits nothing, so colouring it would be this layer
  inventing a role rather than arriving early with one.

Every other use — a bare name, a type in a signature — still needs resolution and stays the
server's.

Its keys are not chosen: `SemanticTokensProvider` was asked what it emits for one of each
declaration, and each key carries the platform attribute LSP4IJ maps that answer to
(`SemanticTokensHighlightingColors`). The handover from this layer to the server's is meant to be
invisible; a fallback with a palette of its own would make every file flicker through a second
colour scheme on open. The table is in the annotator's docs and pinned by
`FlixSemanticFallbackAnnotatorTest`.

One row is Flix's own: `effect` is a semantic token type the standard does not have, and LSP4IJ's
default colours provider returns `null` for anything outside the standard set — a `null` key means
the span is not painted at all. So the one thing the server took the trouble to identify was the one
thing no layer coloured. `FlixSemanticTokensFeature` claims that type and nothing else, mapping it
to the same `FLIX_EFFECT_NAME` the fallback annotator gives an `eff` declaration, so one entry in
the scheme governs both layers. Everything else is delegated to `super` rather than to the default
provider, which keeps a user-registered `semanticTokensColorsProvider` in charge of the standard
types.

The wire value is lowercase `effect`. The compiler's constant is `Effect` and the prose here calls
it that, so a mapping written against the capitalised name compiles, runs, matches nothing and
colours nothing — the same shape as the token-name bug above.

One row is invisible in the other direction. LSP4IJ ships colour schemes
(`colorSchemes/SemanticTokens*.xml` in lsp4ij-0.20.1) and `LSP_TYPE_PARAMETER` is the only key in
them with an explicit foreground — teal in dark and high contrast, blue in light. So a **type
parameter does change colour when the server answers**, and the handover is invisible for every
other row but not that one. Matching it would mean this plugin shipping a colour, which it does not
do anywhere else.

To see what the server actually sent rather than what the buffer looks like, use LSP4IJ's **Semantic
Tokens Inspector** (Tools → LSP → …). It prints one line per span, `<colour key> - <type>.<modifier>`,
and prints `null` where a token type maps to no key at all — which is how the `Effect` gap above was
established. In a file whose whole point is that a layer can be dead while looking healthy, that is
the instrument to reach for first.

## The bug the tests exist for

`getTokenHighlights` bucketed on `IElementType.toString()` and tested it for `_KW`, `COMMENT_` and
`LITERAL_` prefixes. Grammar-Kit builds each token from the **display text** in the grammar rather
than the rule name — `Flix.bnf` says `DEF_KW='def'`, the generated line is
`new FlixTokenType("def")`, and `FlixTokenType.toString()` prefixes its own class. The string
actually tested was `"FlixTokenType.def"`. Nothing matched. **Every token fell through to the empty
array, and the highlighter coloured nothing at all.**

It survived because LSP semantic tokens paint the same buffer, so any IDE with `backend` loaded
looked right. Read that as the general warning it is: *the layer below LSP can be entirely dead and
still look healthy.*

The field names in `FlixTypes` are the real names, so they are read reflectively once. Any test here
must run the real lexer and read the key back — asserting on `toString()` is what hid this.

## The four `if`s

`IF_KW` is one token in four productions, and only one of them is a conditional:

| Production | Shape | Meaning | Key |
| --- | --- | --- | --- |
| `ifExpr` (`Flix.bnf:790`) | `if ( e ) e [else e]` | choose a branch | `FLIX_CONTROL_FLOW_KEYWORD` |
| `matchRule` (`:825`) | `case p if e =>` | filter a match arm | `FLIX_GUARD_KEYWORD` |
| `guardFragment` (`:845`) | `if e` | filter a comprehension | `FLIX_GUARD_KEYWORD` |
| `guard` (`:1024`) | `if ( e )` | constrain a Datalog rule | `FLIX_DATALOG_GUARD_KEYWORD` |

The last is the one that matters: it is spelled identically to a conditional and is not one. Both
appear in the UI fixture, at `Main.flix:25` and `:88`.

Note that the second and third take **no parentheses**. Any design keyed on "the condition's
delimiters" reaches only half the roles, which is why the span below is defined on the *expression*
and merely widened to the parentheses where they exist.

### The condition span

The condition gets `FLIX_CONDITION` as **one span**: parentheses included where the grammar has
them, and the guard expression alone where it does not.

Where there are parentheses the span runs from the element's own `(` to its own `)`, found with
`findChildByType`, which looks at *direct children only*. That is load-bearing rather than
incidental. On `Main.flix:88` --
`if (m2 > m1) (h2 - h1, m2 - m1) else ((h2 - h1) - 1, (60 + m2) - m1)` -- the branches are
parenthesised too, and a subtree search taking the first `(` and the last `)` emphasises the entire
line. `testTheSpanStopsAtTheConditionAndDoesNotSwallowTheBranches` is that fault, injected.

A `matchRule` with no guard has no condition to mark, and `getExpr()` is null for it.

### What the lexer deliberately does not colour

- `CASE_KW` — a match arm (`:825`), a catch arm (`:882`) and a select arm (`:933`), but also an
  `enum` member (`:449`), which is a declaration. Colouring it as control flow lights up every enum.
- `SELECT_KW` — the channel select, and also the projection in `query … select`.

Both need the PSI. This is the boundary between the two layers, not an oversight.

## Defaults

**No key ships a colour.** A colour that reads well in Darcula can vanish in the high-contrast
scheme, and shipping per-theme attribute files means shipping contrast nobody has looked at.

The annotator instead applies one rule at paint time, to every role it draws — keywords and
condition spans alike: **bold italic, and nothing else.** Weight and slant are orthogonal to the
palette, so the same rule reads correctly in the light, dark and high-contrast schemes.

That "nothing else" is the load-bearing part, and it rests on how the platform composes layered
highlighters (`TextAttributes.merge`):

```java
if (above.getForegroundColor() != null) attrs.setForegroundColor(above.getForegroundColor());
attrs.setFontType(above.getFontType() | under.getFontType());
```

Colours override **only when set**, and font types are **or-ed**. So an overlay carrying only a
font style leaves every colour underneath intact: inside one condition the string stays string-
coloured, the number number-coloured, the operator its own — each merely gains weight and slant. Set
any colour, or clone some inherited attributes, and the whole span flattens to one colour.

`testTheDefaultSetsOnlyAFontStyle` pins the overlay, and
`testAStyleOnlyOverlayKeepsTheColoursUnderneath` pins the merge contract itself, so a platform
change breaks the build rather than silently draining the colour out of every condition.

It is applied in the annotator rather than through `additionalTextAttributes`, and that is not
stylistic: **a scheme entry replaces a key's attributes rather than adding to them**, so the
fallback would stop applying and a guard keyword would render bold italic in the colour of ordinary
text — losing exactly what this is careful to keep.

An explicit value for a key in the colour scheme wins outright, including over this default. That is
what makes it a default rather than a decree — and it is how to get a different treatment: set
`FLIX_CONDITION` to a dim foreground and the condition recedes instead of standing out.

### The preview cannot show a computed default

`FlixColorSettingsPage` renders from what the *scheme* holds, so a default the annotator computes at
paint time does not appear in the preview until someone stores a value. The demo tags still show
*which* ranges each key governs, which is the part a reader needs in order to choose. Fixing this
properly means shipping `additionalTextAttributes` per theme, which is the contrast problem above.

## Comments, of which there are two kinds

`///` documents the declaration below it and `//` remarks on a line, so they get different keys —
`FLIX_DOC_COMMENT` falling back to the scheme's own doc-comment colour, which is what makes a Flix
doc comment read like Javadoc or KDoc in the same theme.

The lexer already told them apart (`COMMENT_DOC` against `COMMENT_LINE`); the highlighter bucketed
both under `COMMENT_` and lost the distinction. The rule for which is which is the **compiler's**,
and it is worth knowing before touching it: a doc comment leads with *exactly* three slashes.
`//// heading` is an ordinary comment, which matters because adding a slash is a common way to
comment out a doc line — colouring that as documentation would say the opposite of what the compiler
does with it. `Lexer.acceptLineOrDocComment` in the fork states it, `_Flix.flex` matches it, and
`FlixSyntaxHighlighterTest` pins it.

## Spelling is not a role

The two layers answer different questions, and operators are where the difference is easiest to see.

The lexer can say that `+`, `<=`, `|>` and `<*>` are **written as symbols**. That is a fact about
spelling, it needs no resolution, and it is what `FLIX_OPERATOR` colours — including every operator
Flix reserves no token for, which all arrive as one catch-all kind (`GENERIC_OPERATOR`) and are
otherwise the operators a Flix program actually reads by.

The server answers what a symbol **resolves to**, and repaints the ones that are calls: `Weeder2`
rewrites `+` to `Add.add` and `<=` to `Order.lessEqual` before anything sees them, so both come back
as `Method`. `|>` is `pub def |>` in the Prelude and comes back as `Operator` today — the one
category that is about spelling rather than role, which is why it is under review.

Where the server has no opinion at all this layer is the only colour there is: `::` desugars to a
vector literal before token collection, and `->`, `=>`, `=` and the `~`/`&`/`+` of an effect set are
syntax rather than calls.

A math identifier (`⊗`) is deliberately **not** in the set. It is a name — bound and used like one,
and reaching `FlixSemanticFallbackAnnotator` as a variable pattern — so colouring it by its spelling
would overrule what it is with what it looks like.

## What is not coloured, and why

Branch contents. A branch is an arbitrary expression that can be pages long and can nest, so a tint
becomes a wash across most of a file rather than a cue. There is no synthetic `then` inlay either;
Flix has no `then` keyword, and inventing one in the gutter would show a construct the grammar does
not have.

## Adding a key

1. Define it in `FlixSyntaxHighlighter`, with a fallback to an existing key unless the annotator
   computes the whole appearance itself — `FLIX_CONDITION` has none for that reason.
2. Add an `AttributesDescriptor` in `FlixColorSettingsPage` — a key with no descriptor is
   unreachable, since it still renders and has nowhere to be changed.
3. If the annotator assigns it, add a demo tag *and* the matching entry in
   `getAdditionalHighlightingTagToDescriptorMap`. The preview is lexed, not parsed, so an
   annotator-driven role is invisible there without a tag — and a tag with no entry breaks the whole
   settings panel at render time rather than losing one colour.
4. `FlixColorSettingsPageTest` checks 2 and 3 in both directions.

Registering an extension means editing the module descriptor **and** `flix-integration.yaml`, then
`./gradlew generateIntegrationGlue`. `FlixPluginDescriptorTest` keeps its own inventory of the
language module's extension points and must be updated too.

One trap in that XML: **`--` is not permitted inside an XML comment.** The surrounding comments use
an en-dash for exactly this reason. Getting it wrong makes the descriptor unparseable, and the
platform's report is one `WARN … Cannot load …/flix.jetbrains.plugin-0.1.0.jar` with the whole
plugin absent — no language, no run configuration type, no position manager. `checkIntegrationGlue`
now parses every descriptor, so this fails in milliseconds instead of via a full IDE fixture.

## Testing

`language` tests that need PSI must register the parser by hand:

```kotlin
LanguageParserDefinitions.INSTANCE.addExplicitExtension(FlixLanguage, FlixParserDefinition())
```

A module-local fixture never reads the content-module descriptor, so without this a `.flix` document
comes back as **plain text with no PSI**, and every assertion finds nothing and passes or fails for
a reason unrelated to what it is testing.

`FlixControlFlowAnnotator.rolesOf` is separated from `annotate` so the decision — which is the whole
of the class — is testable against real parsed PSI without standing up an `AnnotationHolder`. Note
that `enforcedTextAttributes` discards the key, so a test driving the full annotation path could not
assert on key names at all.
