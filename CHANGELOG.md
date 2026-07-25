<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Flix-jetbrains-plugin Changelog

## [Unreleased]

### Added

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
- Launch-mode debugging's underlying `FlixDebugAdapter.java` capability confirmed end-to-end from
  `flix-lab`'s side; the plugin-side auto-configuration wiring confirmed statically (clean compile,
  clean `verifyPluginStructure`, LSP4IJ's mechanism confirmed by decompiling its bytecode) but not
  yet by clicking "Debug" in a live IDE session -- see the README's Launch-mode debugging section.
