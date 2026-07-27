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
- **Real Flix language support.** A new `language` content module supplies a `Language("Flix")`,
  file type, Grammar-Kit/JFlex lexer and parser, PSI, syntax highlighter, brace matcher, commenter,
  quote handler and folding builder, adopted from `intellij-flix` per ADR 0001.
- **A gutter run arrow next to `def main`** — the gap that started this work. It anchors on the
  declaration's name leaf and delegates to the platform's generic `ExecutorAction`, so the existing
  LSP4IJ DAP producer supplies the Run and Debug entries.
- `FlixPluginDescriptorTest`, asserting the registration wiring the behavioural tests cannot: every
  content module is declared, every extension names a class that exists and implements its
  extension point's interface, every language extension targets `Flix`, and exactly one file type
  claims `*.flix`. It caught an invalid `--` sequence inside an XML comment on its first run — the
  same class of defect that previously made the whole plugin fail to load.
- `FlixLaunchCommand` in the `shared` module, building the Flix run and debug invocations in one
  place for the CodeLens action and the forthcoming native JVM debug configuration. It pins the
  ordering rules that are easy to get wrong and hard to diagnose: options must follow the `run`
  subcommand or the compiler demotes `run` to a positional argument and reports
  `Unrecognized file extension: 'run'`; `--Xdebug` must appear exactly once or it is rejected as
  an unknown option; and `JAVA_TOOL_OPTIONS` is appended to rather than replaced, so a debug
  session does not silently drop the user's heap or encoding settings.
- **Flix source positions in IntelliJ's own JVM debugger.** A new optional `debugger` content
  module registers a `PositionManagerFactory`, so IntelliJ's stock *Remote JVM Debug* configuration
  attached to a `flix run --Xdebug` process now resolves `.flix` frames to real source and lines,
  alongside Java, Kotlin and Scala frames in the same session (ADR 0002). Resolution is dual-mode:
  the fork emits an SMAP `"Flix"` stratum only for classes that inline across files, so ownership is
  decided by source *name* and the stratum only selects which JDI overload to ask. The module scopes
  its Java-plugin dependency and stays optional, so an IDE without Java support still gets the
  language layer.
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
  lexer, for when the language server hasn't analyzed a file yet. *(Removed once the language
  module landed: registering a real file type deactivates TextMate, so it became dead code.)*
- `FlixForkTest`, covering `flix-vendor-*.jar` resolution.
- Launch-mode debugging: clicking "Debug" on a `.flix` file now auto-creates a working DAP run
  configuration (a second `fileNamePatternMapping` makes `.flix` files discoverable by LSP4IJ's
  built-in run configuration producer; `FlixDebugAdapterDescriptorFactory#prepareConfiguration`
  fills in its Mappings tab data), backed by `FlixDebugAdapter.java`'s new ability to spawn
  `flix run --Xdebug` itself instead of only ever attaching to an already-running target.

### Changed

- The LSP4IJ language-server mapping moved from `fileNamePatternMapping` to `languageMapping` now
  that a real `Language("Flix")` exists, carrying `languageId="flix"` across explicitly. The DAP
  mapping stays filename-based: `DebugAdapterManager.findDebugAdapterServerFor()` consults it to
  recognise a file as debuggable before any run configuration exists.
- Three LSP4IJ features that were bound to `language="textmate"` are re-registered for
  `language="Flix"`, since registering a real language would otherwise silently remove them:
  structure view, code-block navigation and parameter info. Four others are deliberately left
  unregistered -- the semantic-token file view provider is the *structureless* one and would
  destroy the new PSI, LSP folding would duplicate the syntactic folding builder, and `flix lsp`
  advertises neither call nor type hierarchy.
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

### Removed

- The bundled TextMate grammar and all five of its attachment points: `FlixTextMateBundleProvider`,
  its extension registration, `backend/src/main/resources/textmate-bundle/`, the
  `bundledPlugin("org.jetbrains.plugins.textmate")` dependency, and the backend module's TextMate
  plugin dependency. Registering a real file type for `*.flix` deactivates TextMate, so keeping it
  would have left an inactive grammar to maintain.

### Fixed

- `.flix` lines could not take a breakpoint at all. `DebuggerUtils.isBreakpointAware` returns true
  only when the file type reports `isJVMDebuggingSupported()` or a `JavaDebugAware` claims the file,
  and `JavaLineBreakpointTypeBase.canPutAtElement` refuses everything else -- so the position
  manager was never consulted, because nothing asked it to resolve anything. A `JavaDebugAware`
  now claims `.flix`, which also means no custom Flix breakpoint type is needed.
- The debugger module was not backend-scoped, so it registered debugger extensions on the frontend
  where nothing consumes them.
- Reading the `flix.runMain` argument could throw instead of falling back. `LSPCommand.getArgumentAt`
  bounds-checks with `index > size`, so index 0 against an empty list reaches `get(0)` and raises
  `IndexOutOfBoundsException` -- turning "the server sent no symbol, run the project default" into a
  failed CodeLens click. The argument list is now indexed directly via `getArguments()`, which
  returns an empty list rather than null and applies the same JSON conversion.
- The `flix.runMain` CodeLens ignored its argument, so the "Run" lens above any entry point ran the
  project default instead of the one it sat above. The server sends a lens for *every* entry point,
  each carrying its own symbol, so this was only visibly wrong once a file had more than one. The
  symbol is now passed through as `--entrypoint`; the round-trip is exact, since
  `Symbol.DefnSym.toString` renders what `Symbol.mkDefnSym` parses back.
- Two run arrows appeared beside `def main`, the left one reporting "Nothing here". The
  `runLineMarkerContributor` was registered in the always-loaded language module, so it loaded in
  both split-mode processes: the backend one resolved the DAP run-configuration producer and built
  the correct menu, while the frontend one had no producers available and offered nothing. It is
  now registered backend-only, where line markers and `ExecutorAction` belong.
- `getSourcePosition` derived the SMAP stratum three times per location -- once each for the source
  name, line and path -- costing up to six JDWP round-trips per frame where two suffice, on a path
  that runs for every frame of every stack. It is now resolved once and threaded through. This is
  the same defect shape as the `locationsOfLine` bug below: several independent derivations of one
  value, free to disagree.
- A breakpoint could verify against a class and then never bind to a location. `matchesSource`
  resolved using the source name JDI reported, but `locationsOfLine` queried with a base name
  derived from the breakpoint's file -- and a class without SMAP can report an absolute
  `SourceFile`, for which `locationsOfLine(stratum, "Main.flix", n)` yields nothing even though the
  line table holds line `n`. Both now share one selection rule and query with the reported name.
- A breakpoint could bind to the wrong class. Reverse navigation refused ambiguous duplicate base
  names, but forward binding compared source paths textually, and the suffix rule makes
  `Main.flix` match `/project/moduleA/Main.flix` -- so a class without SMAP, which reports only a
  bare name, bound to a breakpoint in *every* module's `Main.flix`. Forward resolution is now
  project-aware and uses the same ambiguity-refusing selection, behind a cheap base-name filter so
  it stays affordable across every loaded class.
- Native source lookup passed a possibly path-valued `SourceFile` straight to `FilenameIndex`,
  which keys on base names, so a path-valued attribute found nothing. It now reduces to a base name
  and keeps the full path for disambiguation, refusing ambiguous duplicate-base-name matches rather
  than binding to an arbitrary same-named file.
- A syntax error made the rest of a file unparseable: `declaration` had no `pin`/`recoverWhile`, so
  a single error turned everything after it into one `PsiErrorElement`, taking the gutter marker,
  folding and structure view with it. Declaration-level recovery is now in place, with the corpus
  still at 427 of 427.
- The recovery tests did not test recovery. They looked for a lexer token, which survives inside the
  error element that swallowed the file, so they passed against a parser with no recovery at all.
  They now assert a real declaration outside any error element -- and the cases that cannot make
  that claim assert the weaker guarantee explicitly, because Flix permits a local `def` as an
  expression and an unfinished expression legitimately absorbs the next declaration.
- The corpus gate's `def main` check passed vacuously: a parser producing no declarations never
  entered the loop. It now compares against an independent count taken from the corpus text.
- `-DflixCorpusDir` was silently overwritten with an empty value by the build, so the documented
  invocation skipped the gate instead of running it. The gate also now reports when the checkout is
  not on the pinned revision.
- `:language:test` runs one JVM per class. Three recovery assertions failed only once enough classes
  had shared a JVM -- state leaking through the platform's test fixtures, not a grammar defect,
  confirmed by parsing the same sources directly.

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
