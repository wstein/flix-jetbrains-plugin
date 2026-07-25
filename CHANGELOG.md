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

### Changed

- Ported from a single-module prototype (in `flix-lab/jetbrains-plugin/`) into this repo's
  frontend/backend/shared split-mode content-module layout; removed the generator's sample RPC
  chat-demo code.

### Fixed

- An XML comment containing `--` in `flix.jetbrains.plugin.frontend.xml` made the entire plugin
  fail to load ("Cannot load ... contains invalid plugin descriptor") in both backend and frontend
  processes under Split Mode. `verifyPluginProjectConfiguration`/`buildPlugin` do not catch this
  class of error; only an actual `runIdeSplitMode` session did.

### Verified

- Language features, `--Xdebug` DAP debugging, and `flix.runMain` all confirmed working end-to-end
  under `./gradlew runIdeSplitMode` (real split frontend + backend processes, not just a
  single-process `runIde`).
