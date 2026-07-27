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
  breakpoints resolve and hit, `evaluate` (dotted-path field access, method calls with literal
  arguments, and array indexing) works, and the shared `FlixDebugAdapter.java` pretty-prints Flix
  records/tagged unions instead of showing raw JVM identities. Getting here required finding and
  fixing two real bugs in LSP4IJ's dispatch model
  (not just wiring mistakes) -- see `FlixDebugAdapterDescriptor`'s javadoc and
  `isDebuggableFile()`'s javadoc for the specifics: LSP4IJ picks the literal DAP command
  (`launch`/`attach`) from `getDebugMode()` alone, and the Mappings-tab file association is never
  consulted unless a descriptor explicitly checks `DAPRunConfigurationOptions`.
- **`flix.runMain` CodeLens**: live-verified -- clicking "Run" above an entry point runs it and
  shows output in a console, instead of failing with "Missing 'flix.runMain' command... needs to be
  contributed by an IntelliJ plugin". The lens's symbol argument is honoured and passed as
  `--entrypoint`, so the lens above `demo()` runs `demo`, not the project default.
- **Flix language support (PSI)**: a real `Language("Flix")`, file type, Grammar-Kit/JFlex parser
  and PSI, syntax highlighter, brace matcher, commenter, quote handler and folding, adopted from
  [`intellij-flix`][intellij-flix] per [ADR 0001][adr1]. The adopted grammar parses 427 of 427
  compilable files in the upstream Flix corpus; see the
  [parser corpus evaluation](docs/intellij-flix-parser-evaluation.md). This replaced the bundled
  TextMate fallback grammar, which a real file type deactivates.
- **Gutter run arrow beside `def main`**: anchored on the declaration's name leaf, delegating to
  the platform's generic `ExecutorAction`.
- **Split Mode**: live-verified running as an actual split frontend+backend process pair
  (`./gradlew runIdeSplitMode`) -- all of the above (LSP, DAP debugging, `flix.runMain`) confirmed
  working with everything registered in the backend module and the frontend acting as a thin
  client. One real bug found and fixed getting here: an XML comment containing `--` in
  `flix.jetbrains.plugin.frontend.xml` made the *entire plugin* fail to load on both sides ("Cannot
  load ... contains invalid plugin descriptor") -- `verifyPluginProjectConfiguration`/`buildPlugin`
  both passed anyway; `./gradlew verifyPluginStructure` does catch it though (confirmed by
  deliberately reintroducing one and rerunning it), it just doesn't fail the Gradle build over it,
  so its output still needs to be read, not only its exit code.
- **Launch-mode debugging**: live-verified -- clicking "Debug" on `Main.flix` with no existing run
  configuration auto-created a "Flix (--Xdebug attach)" configuration named after the file, in
  Launch mode, with no manual Mappings-tab step; the console showed it spawning
  `flix run --Xdebug --yes` on a freshly-picked JDWP port, listening, and disconnecting cleanly
  (exit code 0) once the program (no breakpoint set that run) ran to completion. Confirms both
  halves: the `fileNamePatternMapping`/`prepareConfiguration` auto-configuration wiring, and
  `FlixDebugAdapter.java`'s own launch capability, already live-verified independently from
  `flix-lab`'s side (spawns `flix run --Xdebug`, streams output, attaches, hits breakpoints,
  disconnect kills the process).

## Launch-mode debugging ("click Debug on this file")

Clicking the gutter/toolbar "Debug" icon on a `.flix` file auto-creates a Debug Adapter Protocol
run configuration in Launch mode -- no manual "Edit Configurations" step needed. This works via
two pieces, both required:

- A second `fileNamePatternMapping` in `flix.jetbrains.plugin.backend.xml` (`*.flix` ->
  `flixDebugAdapter`, alongside the existing one pointing at the language server) makes `.flix`
  files discoverable by LSP4IJ's built-in `DAPRunConfigurationProvider` in the first place --
  without it, `.flix` files are invisible to that auto-creation step entirely (confirmed by
  decompiling `DebugAdapterManager`/`DebugAdapterDescriptorFactory`; LSP4IJ 0.20.1 ships no sources
  jar). `FlixDebugAdapterDescriptorFactory#prepareConfiguration` then fills in the resulting
  configuration's own Mappings tab data too, which the base implementation does not do on its own.
- `FlixDebugAdapter.java` gained real launch semantics: given a `program` argument it spawns
  `flix run --Xdebug` itself (on a freshly-picked free JDWP port) instead of only ever attaching to
  a JVM someone else already started. `FlixDebugAdapterDescriptor#getDapParameters` sends
  launch-shaped arguments (`program`/`cwd`) when the run configuration's own mode is Launch, and
  the original attach-shaped ones (`hostName`/`port`) otherwise -- see that class's javadoc.

The entry point always defaults to `main()` -- LSP4IJ's generic DAP run configuration UI has no
field to override it, and there's no environment-variable escape hatch for this one since a launch
needs a *different* value per file, not one fixed value for the whole IDE process. The flix command
itself does have that escape hatch: it defaults to the `FLIX_DEBUG_COMMAND` environment variable if
set, then `flix` on `PATH`. Export `FLIX_DEBUG_COMMAND=/path/to/scripts/flix-fork` (or wherever a
project's `--Xdebug`-capable build lives) **before** launching the sandbox IDE -- e.g. before running
`./gradlew runIdeSplitMode` in the same shell -- since it's read once when the adapter process
starts, not on every launch. This repo's own `flix` on `PATH` isn't `--Xdebug`-capable at all (see
Troubleshooting below), so debugging any `.flix` file in *this* repo via Launch mode needs this set.

### Troubleshooting: Debug fails near-instantly with no useful message

Symptom (live-verified against this repo itself): clicking Debug shows
`Listening for DAP client on port <n>` and `[flix-debug-adapter] launching: flix run --Xdebug ...`,
then almost immediately `Unset error message.` / `Disconnected successfully from the debug server.`
-- no breakpoint hit, no `flix run` output ever shown. LSP4IJ's Console here only renders the DAP
*server* process's raw stdout/stderr, not the DAP protocol responses themselves, so a real failure
inside the adapter (a bad `sendErrorResponse`) shows up as this same generic placeholder text rather
than anything actionable. Two independent causes produce this exact symptom:

- **Neither `FLIX_DEBUG_COMMAND` nor `flix` on `PATH` is a `--Xdebug`-capable build.**
  `flix run --Xdebug` exits almost instantly if the resolved command doesn't understand `--Xdebug`
  at all (e.g. an official Flix release rather than `wstein/flix-fork`) -- check with `which flix` /
  `flix --version` in the same shell that launched the sandbox IDE. This repo's own `flix` isn't
  `--Xdebug`-capable at all (only `scripts/flix-fork`'s vendored jar is), so debugging its `.flix`
  files needs `export FLIX_DEBUG_COMMAND=/absolute/path/to/scripts/flix-fork` before launching the
  IDE (see above) -- or the manual Attach flow below instead.
- **An existing run configuration already matching the file gets reused as-is**, mode and all.
  LSP4IJ's producer prefers an existing `DAPRunConfiguration` whose Mappings already cover the
  clicked file over creating a fresh one (`DebugAdapterManager.findExistingConfigurationFor`) --
  so a configuration you (or an earlier auto-creation) left in Attach mode with nothing listening on
  its configured port gets silently reused and fails the same way. Check **Run \| Edit
  Configurations** for a stale entry and either fix its Debug Mode or remove it so a fresh one gets
  auto-created.

## One-time setup per project (manual attach configuration)

Launch mode (above) needs no setup. For attach mode -- connecting to a `--Xdebug` JVM you start
yourself, e.g. via a shell script or a run task -- or to override the entry point/flix command
launch mode can't:

1. **Language features**: open a `.flix` file; LSP4IJ should offer to start the "Flix Language
   Server" automatically. Requires a `flix-vendor-*.jar` in the project root (or `$FLIX_FORK_JAR`
   set), the same convention `flix-lab/scripts/flix-fork` uses.
2. **Debugging**: Run → Edit Configurations → + → Debug Adapter Protocol → Server tab, select
   "Flix (--Xdebug attach)" → **Mappings tab, add `*.flix`** (required; LSP4IJ has no
   plugin.xml-level file mapping for DAP servers, only this per-run-configuration UI step) →
   Configuration tab, set Debug mode to Attach with the JDWP host/port your `--Xdebug` process is
   listening on (defaults to `localhost:5005`).

## Relationship to flix-lab

The embedded DAP server (`backend/src/main/resources/dap/FlixDebugAdapter.java`) is a **vendored
copy** of `flix-lab/debug-adapter/src/FlixDebugAdapter.java` -- the same file the VS Code extension
uses, not a fork -- rather than referenced by relative path, since this plugin lives in its own repo
instead of as a subdirectory of `flix-lab`. The language layer has no equivalent second copy to
drift from: this repo is its only home (the frozen `flix-lab/jetbrains-plugin/` prototype's copy is
historical, not maintained).

```console
./gradlew checkDebugAdapterSync   # fails if the vendored copy has drifted
./gradlew syncDebugAdapter        # re-syncs it from flix-lab
```

Both assume `flix-lab` is checked out as a sibling directory (`FLIX_LAB_DIR` env var to override) --
see [scripts/sync-debug-adapter.sh](scripts/sync-debug-adapter.sh). Neither task is wired into the
default `check`/`build` lifecycle: neither repo has a git remote configured yet, so there's no CI
runner that could check out both and run it. Once `flix-lab` is pushed, the `--check` mode is ready
to drop into a GitHub Actions job that checks out both repos.

## Plugin structure

This repository implements a modular IntelliJ Platform plugin using content modules:

```
.
├── .github/                GitHub Workflows, issue templates, and Dependabot configuration
├── .qodana/profiles/       Qodana plugin inspections profile
├── .run/                   Predefined Run/Debug Configurations
├── language/               Language module -- Flix Language, FileType, parser, PSI, editor support
│   ├── build.gradle.kts    Grammar-Kit: generates the lexer/parser/PSI from src/main/grammar
│   └── src/
│       ├── main/
│       │   ├── grammar/    Flix.bnf, _Flix.flex, Flix.tokens.txt (adopted; see NOTICE)
│       │   ├── kotlin/org/flixlang/intellij/   language, editor, highlighting, gutter marker
│       │   └── resources/flix.jetbrains.plugin.language.xml
│       └── test/kotlin/    parser corpus gate, parsing, recovery, folding, gutter anchoring
├── debugger/               Debugger module -- Flix source positions for IntelliJ's JVM debugger
│   ├── build.gradle.kts    depends on the Java plugin; optional, so non-Java IDEs still load
│   └── src/
│       ├── main/kotlin/dev/wstein/flixplugin/debugger/   PositionManager + source lookup
│       └── test/kotlin/    JDI-stub tests for the dual-mode SMAP mapping rules
├── backend/                Backend module -- LSP4IJ server + DAP registrations, flix.runMain
│   ├── build.gradle.kts    LSP4IJ dependency
│   └── src/
│       ├── main/
│       │   ├── java/dev/wstein/flixplugin/   Flix*.java (LSP factory, DAP descriptor, run action, ...)
│       │   └── resources/
│       │       ├── dap/FlixDebugAdapter.java         vendored DAP server
│       │       └── flix.jetbrains.plugin.backend.xml module descriptor
│       └── test/java/dev/wstein/flixplugin/  FlixForkTest
├── frontend/                Frontend module -- placeholder, no genuinely frontend-only UI yet
├── shared/                  Shared module -- empty, no cross-boundary RPC contracts needed
├── src/
│   ├── main/resources/META-INF/plugin.xml   Root descriptor, declares the content modules
│   └── test/kotlin/        FlixPluginDescriptorTest -- registration-wiring invariants
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

### How the suite is split, and why

A content-module descriptor is inert on its own: the platform reads it only when the root
`plugin.xml` names it in `<content>`. A module-local test fixture therefore never loads our
`<extensions>`, and every `LanguageBraceMatching.forLanguage`-style lookup resolves `null`. Rather
than assert wiring through a fixture that cannot represent it, the suite splits along that line:

- **Behaviour** is asserted against the implementations directly, on real parsed PSI -- folding
  regions, brace pairs, commenter prefixes, gutter anchoring, incremental reparse.
- **Wiring** is asserted by `FlixPluginDescriptorTest` against the descriptors themselves -- every
  content module declared, every extension naming a class that exists and implements its extension
  point's interface, exactly one file type claiming `*.flix`, and every descriptor well-formed.

Each failure then names one cause instead of two. The well-formedness check earns its place: `--`
inside an XML comment is invalid, is not caught by `verifyPluginProjectConfiguration` or
`buildPlugin`, and makes the entire plugin fail to load with only "contains invalid plugin
descriptor" to go on.

### Parser corpus gate

`FlixCorpusTest` parses every `.flix` file the Flix compiler accepts -- 243 under
`main/src/library` and 185 under `examples` -- and requires all of them to parse cleanly,
losslessly and without crashing. It needs a Flix checkout, and **skips** when there is none, so CI
without one still passes:

```console
./gradlew test -PflixCorpusDir=/path/to/flix
```

`FLIX_DIR` and `-DflixCorpusDir` work too, and `~/github.com/flix/flix` is tried by default. See
[the evaluation](docs/intellij-flix-parser-evaluation.md) for what it measured and the one file it
excludes.

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

## Direction: one language owner, native JVM debugging

Two architectural decisions are now recorded in [`docs/adr/`](docs/adr/README.md) and are being
implemented in phases. They change where language support and debugging come from:

- **[ADR 0001][adr1] -- one language owner and one LSP client.** Flix PSI is adopted from
  [`flix/intellij-flix`][intellij-flix]'s Grammar-Kit/JFlex language layer rather than hand-porting
  the Flix compiler's `Lexer.scala`/`Parser2.scala` (measured at ~7,200 lines). That layer already
  provides a `Language("Flix")`, a full parser and PSI, editor support, and the `def main` gutter
  marker this plugin lacks. LSP4IJ remains the single LSP client.
- **[ADR 0002][adr2] -- IntelliJ's Java debugger is the sole JDWP owner.** Flix compiles to JVM
  bytecode, and `flix-fork` emits a JSR-45/SMAP `"Flix"` stratum, so the platform Java debugger can
  debug Flix directly through a `PositionManager`. That yields mixed Flix/Java stacks, frame-specific
  expression evaluation, conditional and exception breakpoints, and source-JAR resolution without
  reimplementing each capability inside a debug adapter. The DAP path stays functional until the
  native path passes its gate.

**Status.** ADR 0001 has landed in full. ADR 0002 has landed its first step: a `debugger` content
module registers a `PositionManagerFactory`, so IntelliJ's stock **Remote JVM Debug** configuration
attached to a `flix run --Xdebug` process resolves `.flix` frames to real source and lines. A
dedicated Flix run/debug configuration and a Flix line-breakpoint type are not built yet, and the
mixed Flix/Java stepping matrix has not been exercised in a live IDE session -- so the DAP path
remains the supported way to debug, and is still registered.

## Known gaps

- Launch-mode debugging ("click Debug on this file") always uses `main()` as the entry point --
  there's no field in LSP4IJ's generic DAP run configuration UI to override it, and no
  environment-variable escape hatch either (a launch needs a different entry point per file, not
  one fixed value for the whole IDE process). A project needing `--entrypoint` still needs the
  manual Attach configuration. The flix command itself *is* overridable now, via the
  `FLIX_DEBUG_COMMAND` environment variable (see the Launch-mode debugging section above).
- Debugging by default still runs through LSP4IJ's DAP client. The native path ([ADR 0002][adr2])
  currently covers *source positions* only: attach a **Remote JVM Debug** configuration to a
  `flix run --Xdebug` process and Flix frames resolve, with full Java behaviour for Java frames in
  the same session. A Flix run/debug configuration and a Flix line-breakpoint type are still to
  come, and the mixed Flix/Java stepping matrix in ADR 0002 has not been exercised in a live IDE.
- An unfinished *expression* can absorb the following top-level declaration, because Flix permits a
  local `def` as an expression and the grammar has no positional way to decline one. Upstream's
  `Parser2` breaks out of an expression at a declaration keyword; this grammar does not. The
  declaration is not lost, only nested. See
  [the evaluation](docs/intellij-flix-parser-evaluation.md#error-recovery).
- `FlixDebugAdapter`'s `evaluate` DAP request supports dotted-path field access, method calls with
  literal arguments, and array indexing, but not arithmetic or nested expressions as call
  arguments -- a full expression evaluator would mean compiling arbitrary Flix source against the
  running program.
- No formatter or linter is currently configured for this repo (Qodana provides static analysis,
  but that's a separate, heavier tool, not a fast local lint/format step).

## License

Apache License 2.0 -- see [`LICENSE`](LICENSE).

[`NOTICE`](NOTICE) records the provenance of every derived component: the imported
`intellij-flix` revision, the upstream Flix revision the grammar and token inventory are derived
from (pinned as `flixCorpusCommit` in `gradle.properties`), and `flix-lab`'s `FlixDebugAdapter.java`.

## Useful links

- [Architecture decision records](docs/adr/README.md)
- [Parser corpus evaluation](docs/intellij-flix-parser-evaluation.md) -- the gate evidence behind
  ADR 0001
- [IntelliJ Platform SDK Plugin SDK][docs]
- [Modular Plugins (content modules)][docs:modular-plugins]
- [LSP4IJ][lsp4ij]
- [flix-lab][flix-lab] -- the VS Code side of this tooling
- [wstein/flix-fork][flix-fork] -- the Flix compiler build this targets

[adr1]: docs/adr/0001-single-language-owner.md
[adr2]: docs/adr/0002-native-jvm-debugger.md
[intellij-flix]: https://github.com/flix/intellij-flix
[docs]: https://plugins.jetbrains.com/docs/intellij
[docs:modular-plugins]: https://plugins.jetbrains.com/docs/intellij/modular-plugins.html
[lsp4ij]: https://plugins.jetbrains.com/plugin/23257-lsp4ij
[flix-lab]: https://github.com/wstein/flix-lab
[flix-fork]: https://github.com/wstein/flix-fork
