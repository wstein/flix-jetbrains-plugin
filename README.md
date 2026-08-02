# Flix (IntelliJ Plugin)

Flix language support and breakpoint debugging for the
[wstein/flix-fork](https://github.com/wstein/flix-fork) Flix compiler build, for IntelliJ-based
IDEs.

Completion, diagnostics and hover come from the same `flix lsp` language server the official VS
Code extension downloads -- no reimplementation -- via [LSP4IJ][lsp4ij]'s LSP client. Debugging runs
on IntelliJ's **own** JVM debugger rather than a separate debug protocol, so one session covers Flix
and every other JVM language in the same process: step from Flix into Java or Kotlin and back, with
each language's own breakpoints, source navigation and evaluator.

## Why LSP4IJ, not the native LSP API

IntelliJ's own LSP Client API covers language features, but is available only to plugins in
IntelliJ-based IDEs under conditions LSP4IJ does not impose, and running both clients against one
server would mean two clients for one language. LSP4IJ is therefore the sole LSP client here.

LSP4IJ also ships a generic DAP client. This plugin **does not use it**: debugging goes through
IntelliJ's own JVM debugger instead ([ADR 0002][adr2]), which is what lets one session cover Flix
and every other JVM language in the same process.

## What works, and what's still unverified

- **Language features (LSP)**: live-verified -- syntax highlighting, diagnostics and completion via
  `flix lsp`, through LSP4IJ.
- **Flix language support (PSI)**: a real `Language("Flix")`, file type, Grammar-Kit/JFlex parser
  and PSI, syntax highlighter, brace matcher, commenter, quote handler and folding, adopted from
  [`intellij-flix`][intellij-flix] per [ADR 0001][adr1]. The adopted grammar parses **427 of 427**
  compilable files in the upstream Flix corpus, up from 211 at adoption; see the
  [parser corpus evaluation](docs/intellij-flix-parser-evaluation.md). This replaced the bundled
  TextMate fallback, which a real file type deactivates.
- **Debugging**: IntelliJ's own JVM debugger, per [ADR 0002][adr2]. The
  [gate](docs/native-debugger-gate.md) is **Green** -- Flix and Java breakpoints in one session,
  stepping in both directions, mixed stack navigation, Java locals and evaluation, and a Step Over
  that stops on the next line of the *same Flix definition* rather than descending into everything
  the line calls.
- **`flix.runMain` CodeLens**: live-verified. The lens's symbol argument is honoured and passed as
  `--entrypoint`, so the lens above `demo()` runs `demo`, not the project default.
- **Gutter run arrow beside `def main`**: anchored on the declaration's name leaf, delegating to the
  platform's generic `ExecutorAction`.
- **Split Mode**: live-verified as an actual frontend+backend process pair
  (`./gradlew runIdeSplitMode`), with everything registered backend-side and the frontend a thin
  client. One real bug found getting here: an XML comment containing `--` made the *entire plugin*
  fail to load on both sides, while `verifyPluginProjectConfiguration` and `buildPlugin` both
  passed. `verifyPluginStructure` does detect it, but does not fail the build over it -- its output
  has to be read, not just its exit code.

### In an IDE without the Java plugin

Language support works: syntax, parsing, folding, the LSP features, the gutter marker. Only the
debugger module needs the Java plugin, and it is declared optional, so an IDE without one **skips
it and loads the rest** rather than failing. Debugging is the single feature that is unavailable
there — not the plugin.

**Not yet verified** -- see [verification coverage](docs/phase-8-verification.md) for the full
matrix, which is explicit about what has been measured and what has not. The two that matter most:
the native run configuration has never been exercised in a live session, and breakpoints on some
Flix lines do not bind.

## Debugging

Press **Debug** on a `.flix` file, or use the gutter arrow beside `def main`. There is no setup
step: the Flix run configuration is created from the declaration under the caret.

What happens is deliberately unremarkable. The configuration launches the Flix compiler with
`run --Xdebug --yes` and a JDWP agent on a free port, and IntelliJ's own Java debugger attaches to
it. This plugin speaks no debug protocol; it decides which port to listen on and hands that to the
platform.

`--Xdebug` is not optional, and is not only a JDWP switch. The compiler emits line numbers for
`let`, calls, `if` and statement sequences **only** under it, and stops the inliner discarding
programmer-written bindings. Without it most statements have no breakpointable line at all, and a
breakpoint on one can never verify no matter what the IDE does.

### Which compiler is used

`$FLIX_JAR` if set, otherwise `flix.jar` in the project root. Every process the plugin starts
-- the language server and the debuggee -- resolves it the
same way, so a debug session cannot run a different compiler than the editor was analysed with.

**The build matters, not just the flag.** Two fixes in
[wstein/flix-fork](https://github.com/wstein/flix-fork) are load-bearing for debugging, and a jar
predating either behaves as though the plugin is at fault:

| Fix | Without it |
| --- | --- |
| One `LineNumberTable` entry per bytecode offset | A line whose call site had a same-file function inlined into it cannot take a breakpoint. Its neighbours can, and `javap` still shows the line, so nothing looks wrong. |
| Workspace jars loaded from the folder URI | The language server resolves no `[jar-dependencies]`, so every Java import reports *"Undefined Java class"* while the compiler builds the same project without complaint. |

If breakpoints on some lines refuse to bind while adjacent ones work, rebuild the fork before
looking anywhere else.

### When a breakpoint does not bind

Check the class before suspecting the plugin:

```console
javap -l -p 'build/class/Def$yourFunction.class' | grep -A20 LineNumberTable
```

A line absent from the table is a compiler-invocation problem, not an IDE one. If the line *is*
there, turn on the plugin's own logging -- sandbox IDEs launched by `runIde`/`runIdeSplitMode`
already have it enabled -- and read which step declined:

```console
tail -f .intellijPlatform/sandbox/*/IU-*/system*/log/idea.log | grep dev.wstein
```

The [gate runbook](docs/native-debugger-gate.md) has a table mapping each log line to its cause.

## Relationship to flix-lab

[flix-lab](https://github.com/wstein/flix-lab) is the companion project: a Flix workspace with VS
Code tooling, and the fixture this plugin is developed against. It carries a `Greeter` in Java,
Kotlin, Scala, Groovy and JRuby, all called from `Main.flix`, which is what makes mixed-language
debugging testable.

The two projects no longer share code. They did: a `FlixDebugAdapter.java` DAP&#8596;JDI bridge was
vendored here and kept in sync by a script. That path was retired once IntelliJ's own JVM debugger
proved out ([ADR 0002][adr2]), so the adapter now lives only in `flix-lab`, serving its VS Code
client. What both projects still share is the compiler: the same `flix-vendor-*.jar` build of
[wstein/flix-fork](https://github.com/wstein/flix-fork).

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
│       ├── main/kotlin/dev/wstein/flixplugin/
│       │   ├── debugger/   PositionManager, stepping policy, source lookup
│       │   └── run/        the Flix run/debug configuration and its context producer
│       └── test/kotlin/    JDI-stub tests for the dual-mode SMAP mapping rules
├── backend/                Backend module -- LSP4IJ server registration, flix.runMain, gutter marker
│   ├── build.gradle.kts    LSP4IJ dependency
│   └── src/
│       ├── main/
│       │   ├── java/dev/wstein/flixplugin/   Flix*.java (LSP factory, run action, jar resolution)
│       │   └── resources/flix.jetbrains.plugin.backend.xml  module descriptor
│       └── test/java/dev/wstein/flixplugin/  FlixForkTest
├── frontend/                Frontend module -- placeholder, no genuinely frontend-only UI yet
├── shared/                  Shared module -- compiler-jar resolution and launch command
├── src/
│   ├── main/resources/META-INF/plugin.xml   Root descriptor, declares the content modules
│   └── test/kotlin/        FlixPluginDescriptorTest -- registration-wiring invariants
├── buildSrc/               Build logic -- the integration-glue contract checker
├── flix-integration.yaml   Cross-module wiring contract; checked by `./gradlew checkIntegrationGlue`
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

Two architecture decisions shape the plugin, both recorded in [`docs/adr/`](docs/adr/):

- [**ADR 0001**][adr1] -- one `Language("Flix")`, adopted from [`intellij-flix`][intellij-flix]
  rather than reimplemented. Two language owners means two parsers and two file types, and which
  one wins depends on load order.
- [**ADR 0002**][adr2] -- IntelliJ's own Java debugger is the sole JDWP owner. Two debuggers cannot
  share one debuggee: they compete for suspension, breakpoints and lifecycle.

**Status.** Both are implemented. The [native debugger gate](docs/native-debugger-gate.md) is
**Green**: Flix and Java breakpoints in one session, stepping in both directions, mixed stack
navigation, Java evaluation, and Flix-aware Step Over, with one JDWP owner throughout. The DAP path
has been removed. What remains is verification rather than construction -- see
[verification coverage](docs/phase-8-verification.md), which is explicit about which rows have been
measured and which have not.

The property this buys, which a Flix-only debug adapter could not: a Flix frame can step into Java,
Kotlin or Scala and back, with each language's own plugin owning its breakpoints, source positions
and evaluator inside the same debug process.

## Known gaps

- **The native run/debug configuration has never been exercised in a live session.** It was built
  after the debugger gate, which ran entirely through a hand-made Remote JVM Debug configuration.
  With the DAP path removed there is no fallback if it misbehaves. Two blocking defects in it were
  found by review and fixed (`GenericDebuggerRunner` requires `ModuleRunProfile` on the
  configuration *and* `RemoteConnectionCreator` on the state); neither fix is confirmed live.
- **Breakpoints on some Flix lines do not bind.** In `flix-lab`'s `Main.flix`, lines calling into
  Kotlin/Scala/Groovy/JRuby do not bind while adjacent lines do. Artifact inspection has ruled out
  bytecode presence, class coverage, SMAP mapping and reachability -- the failing and working lines
  are indistinguishable in the class files. Tracked as row 9 of the
  [verification coverage](docs/phase-8-verification.md).
- **Flix values in CPS frames are not presented.** A `Clo$` continuation keeps its state in fields
  (`l0`..`l8`, `pc`) rather than locals, because the frame must survive suspension and resumption,
  so the variables view is empty for those frames. The values are present and reachable; reading
  them needs Flix-aware renderers, which the plan places after this milestone. Direct `Def$` frames
  show variables normally.
- **`verifyPlugin` has never been run.** The task exists; nothing invokes it.
- **Exception breakpoints, JDK/library source attachment, class redefinition and stale-cache
  invalidation** have no coverage -- and no known failure either.
- **Kotlin, Scala and Groovy interop is unmeasured.** `flix-lab` now carries a `Greeter` in five
  languages, which is the fixture for it, but no session has exercised them. Kotlin coroutine
  debugging is explicitly **not** claimed: it needs the Kotlin debugger's agent injected into an
  externally launched JVM, which has not been proven.

## License

Apache License 2.0 -- see [`LICENSE`](LICENSE).

[`NOTICE`](NOTICE) records the provenance of every derived component: the imported
`intellij-flix` revision, the upstream Flix revision the grammar and token inventory are derived
from (pinned as `flixCorpusCommit` in `gradle.properties`), and the Flix revision the grammar derives from.

## Useful links

- [Architecture decision records](docs/adr/README.md)
- [Parser corpus evaluation](docs/intellij-flix-parser-evaluation.md) -- the gate evidence behind
  ADR 0001
- [Native JVM debugger gate](docs/native-debugger-gate.md) -- the runbook for proving ADR 0002
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
