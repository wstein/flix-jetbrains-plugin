# Flix (IntelliJ Plugin)

Flix language support and `--Xdebug` JDWP breakpoint debugging for the
[wstein/flix-fork](https://github.com/wstein/flix-fork) Flix compiler build, for IntelliJ-based
IDEs. Companion to [flix-lab](https://github.com/wstein/flix-lab)'s VS Code tooling: this plugin
reuses the exact same `flix lsp` language server the official VS Code extension downloads (no
reimplementation of completion/diagnostics/hover), and the exact same `FlixDebugAdapter.java`
DAP↔JDI bridge as flix-lab's VS Code debug adapter, via [LSP4IJ][lsp4ij]'s generic LSP and DAP
clients.

## Why LSP4IJ, not the native LSP API

IntelliJ's own native LSP Client API (opened up to all users in the 2025.3 unified distribution)
covers language features, but has no DAP equivalent. LSP4IJ's generic DAP client is the only
generic path into IntelliJ's debugger for a custom, non-JVM-native protocol like DAP today, so
LSP4IJ is used for both LSP and DAP here rather than mixing two different mechanisms.

## What works, and what's still unverified

- **Language features (LSP)**: live-verified against a real `runIde` session -- syntax
  highlighting, diagnostics, and completion via `flix lsp`, working through LSP4IJ.
- **Debugging (DAP)**: live-verified end-to-end -- attach to a `--Xdebug`-suspended target,
  breakpoints resolve and hit, `evaluate` (dotted-path expressions) works, and the shared
  `FlixDebugAdapter.java` pretty-prints Flix records/tagged unions instead of showing raw JVM
  identities. Getting here required finding and fixing two real bugs in LSP4IJ's dispatch model
  (not just wiring mistakes) -- see `FlixDebugAdapterDescriptor`'s javadoc and
  `isDebuggableFile()`'s javadoc for the specifics: LSP4IJ picks the literal DAP command
  (`launch`/`attach`) from `getDebugMode()` alone, and the Mappings-tab file association is never
  consulted unless a descriptor explicitly checks `DAPRunConfigurationOptions`.
- **`flix.runMain` CodeLens**: live-verified -- clicking "Run" above `def main()` in the editor now
  runs `flix run` and shows output in a console, instead of failing with "Missing 'flix.runMain'
  command... needs to be contributed by an IntelliJ plugin".
- **Bundled TextMate fallback grammar**: derived directly from `flix-fork`'s real lexer
  (`language/ast/TokenKind.scala`, `language/phase/Lexer.scala`), auto-registered via the real
  `com.intellij.textmate.bundleProvider` extension point. Live-verified rendering `.flix` syntax
  highlighting under `./gradlew runIdeSplitMode`.
- **Split Mode**: live-verified running as an actual split frontend+backend process pair
  (`./gradlew runIdeSplitMode`) -- all of the above (LSP, DAP debugging, `flix.runMain`) confirmed
  working with everything registered in the backend module and the frontend acting as a thin
  client. One real bug found and fixed getting here: an XML comment containing `--` in
  `flix.jetbrains.plugin.frontend.xml` made the *entire plugin* fail to load on both sides ("Cannot
  load ... contains invalid plugin descriptor") -- `verifyPluginProjectConfiguration`/`buildPlugin`
  both passed anyway, so this class of error is only caught by an actual IDE session, not the build.

## Known limitation: attach-only debugging

There's no "click Debug on this file" support -- the toolbar's generic "Current File" run/debug
widget does nothing useful for `.flix` files. `FlixDebugAdapterDescriptor` only implements DAP
*attach* semantics: it connects to a `--Xdebug` JVM you've already started suspended and waiting
(e.g. via a shell script or a run task). There's no `RunConfigurationProducer` recognizing `.flix`
files, and `FlixDebugAdapter.java` has no code path for actually launching a Flix program with JDWP
enabled itself. Building that would mean a real new feature -- a run-configuration producer plus
teaching the adapter to spawn `flix run` with the right JDWP flags itself, essentially merging
`flix.runMain`'s "run the program" logic with the attach flow -- not a quick fix.

## One-time setup per project

1. **Language features**: open a `.flix` file; LSP4IJ should offer to start the "Flix Language
   Server" automatically. Requires a `flix-vendor-*.jar` in the project root (or `$FLIX_FORK_JAR`
   set), the same convention `flix-lab/scripts/flix-fork` uses.
2. **Debugging**: Run → Edit Configurations → + → Debug Adapter Protocol → Server tab, select
   "Flix (--Xdebug attach)" → **Mappings tab, add `*.flix`** (required; LSP4IJ has no
   plugin.xml-level file mapping for DAP servers, only this per-run-configuration UI step) →
   Configuration tab, set Debug mode to Attach with the JDWP host/port your `--Xdebug` process is
   listening on (defaults to `localhost:5005`).

## Relationship to flix-lab

The embedded DAP server (`backend/src/main/resources/dap/FlixDebugAdapter.java`) and the TextMate
grammar are **vendored copies**, not referenced by relative path -- this plugin lives in its own
repo rather than as a subdirectory of `flix-lab`, so the "single source of truth via relative path"
arrangement the original single-module prototype used doesn't carry over. If `flix-lab`'s
`debug-adapter/src/FlixDebugAdapter.java` changes, this copy needs to be manually re-synced; there
is currently no automation for that.

## Plugin structure

This repository implements a modular IntelliJ Platform plugin using content modules:

```
.
├── .github/                GitHub Workflows, issue templates, and Dependabot configuration
├── .qodana/profiles/       Qodana plugin inspections profile
├── .run/                   Predefined Run/Debug Configurations
├── backend/                Backend module -- everything currently lives here
│   ├── build.gradle.kts    LSP4IJ + TextMate dependencies
│   └── src/
│       ├── main/
│       │   ├── java/dev/wstein/flixplugin/   Flix*.java (LSP factory, DAP descriptor, run action, ...)
│       │   └── resources/
│       │       ├── dap/FlixDebugAdapter.java         vendored DAP server
│       │       ├── textmate-bundle/                  vendored fallback grammar
│       │       └── flix.jetbrains.plugin.backend.xml module descriptor
│       └── test/java/dev/wstein/flixplugin/  FlixForkTest
├── frontend/                Frontend module -- placeholder, no genuinely frontend-only UI yet
├── shared/                  Shared module -- empty, no cross-boundary RPC contracts needed
├── src/main/resources/META-INF/plugin.xml   Root descriptor, declares the content modules
├── build.gradle.kts        Root build -- assembles the final plugin, splitMode = true
├── gradle.properties
└── settings.gradle.kts
```

### Module dependency syntax

Content module descriptors (`flix.jetbrains.plugin.backend.xml` etc.) use
`<dependencies><plugin id="..."/></dependencies>` for external-plugin dependencies -- the classic
`<depends>` tag from the root `plugin.xml` is explicitly disallowed inside a module descriptor
(confirmed against the [Modular Plugins][docs:modular-plugins] documentation).

## Build

```console
./gradlew buildPlugin
```

Produces `build/distributions/flix.jetbrains.plugin-<version>.zip`.

## Test

```console
./gradlew test
```

`FlixForkTest` uses `HeavyPlatformTestCase` (a project backed by real files on disk), not the
lighter `BasePlatformTestCase` (an in-memory VFS project) -- `FlixFork.resolveJar` does plain
`java.io`/`java.nio.file` calls against `project.getBasePath()`, which only resolve against a real
directory.

## Predefined Run/Debug configurations

| Configuration name               | Description                                                                 |
|-----------------------------------|------------------------------------------------------------------------------|
| Run IDE with Plugin (Frontend)   | Runs `:runIdeFrontend`. Use the *Debug* icon for plugin debugging.          |
| Run IDE with Plugin (Backend)    | Runs `:runIdeBackend`. Use the *Debug* icon for plugin debugging.           |
| Run IDE with Plugin (Split Mode) | Runs both simultaneously to launch the plugin in split mode.                |

## Install (sideload, not Marketplace-listed)

No JetBrains Marketplace listing currently -- consistent with `flix-lab`'s VS Code extension being
`"private": true` / sideloaded. Install the built zip manually via Settings/Preferences → Plugins →
gear icon → Install Plugin from Disk...

You'll also need [LSP4IJ][lsp4ij] installed from the Marketplace (it's a `<plugin>` dependency of
the backend module; IntelliJ should prompt for it).

## GitHub Actions / Qodana / Dependabot

Generator-provided scaffolding, unmodified: [Build](.github/workflows/build.yml) and
[Release](.github/workflows/release.yml) workflows, [issue templates](.github/ISSUE_TEMPLATE/),
[Dependabot config](.github/dependabot.yml), and a Qodana inspections profile
(`.qodana/profiles/plugin.yaml`, run locally via `./gradlew qodanaScan`, requires Docker). None of
these have been exercised yet (no CI run, no Qodana scan) -- they're present and should work per
the generator's defaults, but that's unverified.

## Known gaps

- No "click Debug on this file" (launch-mode) support -- see the dedicated section above.
- `FlixDebugAdapter`'s `evaluate` DAP request only supports dotted-path field/variable lookups, not
  arbitrary expressions.
- No automated re-sync mechanism for the vendored `FlixDebugAdapter.java`/TextMate grammar copies
  against `flix-lab`.
- No formatter or linter is currently configured for this repo (Qodana provides static analysis,
  but that's a separate, heavier tool, not a fast local lint/format step).

## Useful links

- [IntelliJ Platform SDK Plugin SDK][docs]
- [Modular Plugins (content modules)][docs:modular-plugins]
- [LSP4IJ][lsp4ij]
- [flix-lab][flix-lab] -- the VS Code side of this tooling
- [wstein/flix-fork][flix-fork] -- the Flix compiler build this targets

[docs]: https://plugins.jetbrains.com/docs/intellij
[docs:modular-plugins]: https://plugins.jetbrains.com/docs/intellij/modular-plugins.html
[lsp4ij]: https://plugins.jetbrains.com/plugin/23257-lsp4ij
[flix-lab]: https://github.com/wstein/flix-lab
[flix-fork]: https://github.com/wstein/flix-fork
