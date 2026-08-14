# Syntax highlighting

Two layers paint a `.flix` buffer, and they are not interchangeable.

| Layer | Where | Needs | Knows |
| --- | --- | --- | --- |
| `FlixSyntaxHighlighter` | `language` | nothing | one token at a time |
| `FlixControlFlowAnnotator` | `language` | the parser | which production a token belongs to |
| LSP semantic tokens | `backend`, via LSP4IJ | the language server | what a *symbol* means |

The first two must work in an IDE with no LSP4IJ, and before the server has answered anything. That
is the whole reason the `language` module exists, and it is the case that was broken.

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
an en-dash for exactly this reason. Getting it wrong makes the descriptor unparseable, which fails
seven tests in `FlixPluginDescriptorTest` and cascades into every test that assembles the plugin.

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
