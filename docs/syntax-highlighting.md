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

Note the second and third take **no parentheses**. A design that keys on "emphasise the condition
delimiters" only reaches half the roles.

### What the lexer deliberately does not colour

- `CASE_KW` — a match arm (`:825`), a catch arm (`:882`) and a select arm (`:933`), but also an
  `enum` member (`:449`), which is a declaration. Colouring it as control flow lights up every enum.
- `SELECT_KW` — the channel select, and also the projection in `query … select`.

Both need the PSI. This is the boundary between the two layers, not an oversight.

## Defaults

Every key falls back to one that already exists, so **no key ships a colour**. A colour that reads
well in Darcula can vanish in the high-contrast scheme, and shipping per-theme attribute files means
shipping contrast nobody has looked at.

The three annotator-driven roles are instead emphasised in **bold**, because weight is orthogonal to
the palette and reads the same in every scheme.

That bold is applied in the annotator, not through `additionalTextAttributes`, and the reason is
not stylistic: **a scheme entry replaces a key's attributes rather than adding to them**, so the
fallback would stop applying and a guard would render bold in the colour of ordinary text. The
annotator reads the resolved attributes and sets one bit, keeping whatever colour the active scheme
gives it.

An explicit value for a key in the colour scheme wins outright, including over the bold. That is
what makes the emphasis a default rather than a decree — and it is how to get the opposite
treatment of the condition parentheses: set `FLIX_CONDITION_PARENTHESES` to a dimmer foreground and
the required syntax recedes instead of standing out.

## What is not coloured, and why

Branch contents. A branch is an arbitrary expression that can be pages long and can nest, so a tint
becomes a wash across most of a file rather than a cue. There is no synthetic `then` inlay either;
Flix has no `then` keyword, and inventing one in the gutter would show a construct the grammar does
not have.

## Adding a key

1. Define it in `FlixSyntaxHighlighter` with a fallback to an existing key.
2. Add an `AttributesDescriptor` in `FlixColorSettingsPage` — a key with no descriptor is
   unreachable, since it renders via its fallback and has nowhere to be changed.
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
