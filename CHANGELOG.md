<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Flix-jetbrains-plugin Changelog

## [Unreleased]

### Added

- `LICENSE` (Apache 2.0) and `NOTICE`, recording the provenance of every derived component: the
  imported `intellij-flix` revision, the upstream Flix revision the grammar derives from, and
  `flix-lab`'s `FlixDebugAdapter.java`. The repository previously carried no license at all.
- `docs/adr/` architecture decision records: [0001][adr1] adopts `intellij-flix`'s language layer as
  the single `Language("Flix")` owner instead of hand-porting the Flix compiler's parser, and
  [0002][adr2] makes IntelliJ's native Java debugger the sole JDWP owner for Flix debug sessions.
- `flixCorpusCommit` in `gradle.properties`, pinning the upstream Flix revision used to derive and
  validate the adopted grammar to a commit reachable from `flix/flix` `origin/master`, so the
  parser corpus gate is reproducible off this machine.
- [`docs/intellij-flix-parser-evaluation.md`](docs/intellij-flix-parser-evaluation.md), the gate
  evidence for ADR 0001. Measured against 428 real `.flix` files from the pinned Flix revision, the
  adopted grammar went from 211 clean (49.3%) to **427 of 427 (100%)** once six localized defect
  families were fixed, with zero crashes and zero non-lossless parses throughout, and addressable
  `def main` entry points rising from 114 to 137.

- Flix language server integration via LSP4IJ (`flix lsp`, resolved from `flix-vendor-*.jar`).
- `--Xdebug` JDWP breakpoint debugging via LSP4IJ's DAP client, reusing `flix-lab`'s
  `FlixDebugAdapter.java` (vendored copy).
- `flix.runMain` action, wiring up the Flix language server's "Run" CodeLens above `def main()`.
- Bundled TextMate fallback grammar for `.flix` syntax highlighting, derived from `flix-fork`'s
  lexer, for when the language server hasn't analyzed a file yet.
- `FlixForkTest`, covering `flix-vendor-*.jar` resolution.
- Launch-mode debugging: clicking "Debug" on a `.flix` file now auto-creates a working DAP run
  configuration (a second `fileNamePatternMapping` makes `.flix` files discoverable by LSP4IJ's
  built-in run configuration producer; `FlixDebugAdapterDescriptorFactory#prepareConfiguration`
  fills in its Mappings tab data), backed by `FlixDebugAdapter.java`'s new ability to spawn
  `flix run --Xdebug` itself instead of only ever attaching to an already-running target.

### Changed

- Ported from a single-module prototype (in `flix-lab/jetbrains-plugin/`) into this repo's
  frontend/backend/shared split-mode content-module layout; removed the generator's sample RPC
  chat-demo code.
- `evaluate` (via the vendored `FlixDebugAdapter.java`, re-synced from `flix-lab`) now supports
  method calls with literal arguments and array indexing in addition to dotted-path field access.
- `scripts/sync-debug-adapter.sh` plus the `checkDebugAdapterSync`/`syncDebugAdapter` Gradle tasks
  replace manually copying the vendored `FlixDebugAdapter.java` from `flix-lab`.
- Launch mode now honors the `FLIX_DEBUG_COMMAND` environment variable as the flix command to run
  (re-synced from `flix-lab`), since LSP4IJ's generic DAP run configuration UI has no field to set
  it per-configuration -- export it before launching the IDE to debug a project (like this one)
  whose plain `flix` on `PATH` doesn't support `--Xdebug`.

### Fixed

- An XML comment containing `--` in `flix.jetbrains.plugin.frontend.xml` made the entire plugin
  fail to load ("Cannot load ... contains invalid plugin descriptor") in both backend and frontend
  processes under Split Mode. `verifyPluginProjectConfiguration`/`buildPlugin` do not catch this
  class of error; `verifyPluginStructure` does (confirmed by deliberately reintroducing one), it
  just doesn't fail the Gradle build over it.

### Verified

- Language features, `--Xdebug` DAP debugging, and `flix.runMain` all confirmed working end-to-end
  under `./gradlew runIdeSplitMode` (real split frontend + backend processes, not just a
  single-process `runIde`).
- Launch-mode debugging confirmed end-to-end, including by clicking "Debug" on `Main.flix` in a
  live IDE session: a "Flix (--Xdebug attach)" configuration was auto-created in Launch mode with
  no manual Mappings-tab step, `flix run --Xdebug --yes` was spawned on a fresh JDWP port, and the
  session disconnected cleanly once the program ran to completion.

[adr1]: docs/adr/0001-single-language-owner.md
[adr2]: docs/adr/0002-native-jvm-debugger.md
