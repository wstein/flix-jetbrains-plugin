# Refactoring support

What Flix refactoring works in IntelliJ, what does not, and why. Written after measuring each
claim below against LSP4IJ's bytecode and a running language server, because the interesting
answers were all counter-intuitive.

Read this before adding a refactoring. Three of the four obvious designs do not work, and the
reasons are not discoverable from the API.

## Where refactorings can come from

| Source | Reaches | Interactive? |
| --- | --- | --- |
| `textDocument/rename` | The **Refactor** menu, via LSP4IJ's `renameHandler` | Modal dialog only |
| `textDocument/codeAction` | **Alt+Enter** only — *never* the Refactor menu | No |
| A plugin `RefactoringSupportProvider` | The Refactor menu | Yes — dialogs, previews, in-place |

The middle row is the one that surprises. LSP4IJ turns every code action into an
`LSPIntentionAction`/`LSPLazyCodeActionIntentionAction`, which the platform shows under Alt+Enter.
**No `refactor.*` code action will ever appear in the Refactor menu.** A server-side refactoring is
therefore reachable and useful, but invisible where a user looks for it, until the plugin also
contributes a handler.

## What exists today

- **Rename — works for every symbol.** `RenameProvider` once matched four kinds (type parameters,
  type variables, local variables in two forms) and answered `None` for everything else, which
  `LspServer.rename` turned into an empty `WorkspaceEdit` and the IDE reported as *"The element
  can't be renamed"*. It now reuses `FindReferencesProvider`, which already located all fifteen
  kinds — which is why Find Usages worked on a def while renaming the same def did not. A symbol
  with any occurrence outside the project is refused rather than half-renamed.
- **Code actions — quick fixes only.** Every action `CodeActionProvider` builds is
  `CodeActionKind.QuickFix`. There is not one `Refactor*` kind.
- **The capability names its kinds.** `codeActionProvider` carries `["quickfix"]` rather than a bare
  `true`. The list grows *only* together with a provider that emits the kind: announcing
  `refactor.extract` first would give a client an always-empty refactoring menu, which is the
  unannounced-handler defect seen from the other side.

## What is missing, and what it would take

### In-place rename

**LSP4IJ has no in-place rename** — no class, no setting. `LSPRenameHandler` always shows a modal
dialog.

The platform's `VariableInplaceRenameHandler` wants a `PsiNamedElement` and `PsiReference`s, and the
Flix PSI has neither: it is a parse tree with no semantic model, deliberately (ADR 0001 — the
compiler is the only semantic authority).

The way through does not need one. The server already knows every occurrence range, so a plugin
handler can ask for them (`textDocument/references`), start an IntelliJ template over those ranges,
and apply `textDocument/rename` on commit. Interaction in the IDE, semantics in the compiler.

### Extract, inline, change signature

None exist. They belong in the compiler, where the typed AST is:

1. **Extract Variable** first — binding a selection to a `let` needs no free-variable analysis, so it
   proves the `refactor.extract` pipeline end to end cheaply. Add `refactor.extract` to
   `LspServer.CodeActionKinds` *in that same commit*.
2. **Extract Function** — needs free variables of the selected range, computed from the typed AST.
3. **Inline** — the inverse, and `refactor.inline`.
4. **`RefactoringSupportProvider`** in the plugin, so the above reach the Refactor menu rather than
   only Alt+Enter. Reuse the `LanguageServerManager` path from `FlixShowDiagramAction`, which avoids
   `CommandExecutor`'s null-`LanguageServerItem` NPE.
5. **Change Signature** last: it needs both a dialog and call-site rewriting.

### What will never be offered

`Pull Members Up`, `Push Members Down`, `Extract Superclass`, `Extract Interface`,
`Encapsulate Fields`, `Replace Inheritance with Delegation`, `Make Static`.

Flix has no inheritance and no mutable field, so these have nothing to mean. They are absent today
because the plugin registers no refactorings at all; when a `RefactoringSupportProvider` is added it
must not enable them. **Absent is correct — do not add them greyed out.**

## Why not a PSI semantic model

It is the obvious answer and it is the wrong one. Resolution and type inference in the plugin would
be a second Flix front-end, and it would disagree with the compiler silently. This repository has
already paid for that failure mode several times over: rename against find-references, two diagram
lookups, two class loaders, a constant duplicating a descriptor id. Every one presented as a feature
that looked implemented and was not.

Keep one semantic authority. The IDE contributes interaction.
