# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An IntelliJ Platform plugin giving Flix language support and breakpoint debugging, against the
[wstein/flix-fork](https://github.com/wstein/flix-fork) compiler build. Two decisions shape
everything and are recorded as ADRs in `docs/adr/`:

- **[ADR 0001](docs/adr/0001-single-language-owner.md)** — exactly one `Language("Flix")`, adopted
  from `intellij-flix` rather than reimplemented, and one LSP client (LSP4IJ).
- **[ADR 0002](docs/adr/0002-native-jvm-debugger.md)** — IntelliJ's own Java debugger is the **sole
  JDWP owner**. Debugging is not a debug adapter; the plugin picks a port, launches the compiler
  with a JDWP agent, and the platform attaches. A DAP path existed and was retired; do not
  reintroduce a second debugger.

## Commands

```console
./gradlew build                     # compile, test, and run checkIntegrationGlue
./gradlew check                     # tests + the glue contract check
./gradlew :debugger:test            # one module
./gradlew :language:test --tests '*FlixCorpusTest*'   # one test class
./gradlew runIdeSplitMode           # sandbox IDE as a frontend+backend pair (the realistic mode)
./gradlew runIde                    # sandbox IDE, single process
./gradlew generateIntegrationGlue   # regenerate docs/flix-integration-matrix.md after editing the manifest
```

Sandbox IDEs come up with this plugin's own debug logging already on
(`idea.log.debug.categories`, wired in the root build). Read it with:

```console
tail -f .intellijPlatform/sandbox/*/IU-*/system*/log/idea.log | grep dev.wstein
```

## Module layout and why it is split

Content modules, declared in `src/main/resources/META-INF/plugin.xml`. The split is by
**dependency**, not by feature — each boundary exists because something must stay loadable without
the thing on the other side:

| Module | Holds | Constraint |
| --- | --- | --- |
| `shared` | `FlixJar` (compiler resolution), `FlixLaunchCommand` (the invocation) | **No IntelliJ Platform dependency at all.** Loaded in every process. |
| `language` | `Language("Flix")`, Grammar-Kit lexer/parser/PSI, editor support | Must load in IDEs with no Java plugin |
| `backend` | LSP4IJ server registration, `flix.runMain`, the gutter marker | Needs LSP4IJ |
| `debugger` | Position manager, stepping policy, run/debug configuration | Needs the Java plugin; **not** `loading="required"` |
| `frontend` | placeholder | — |

Two consequences worth knowing before moving code:

- `backend` and `debugger` cannot depend on each other, which is why the compiler-jar rule lives in
  `shared` rather than being duplicated. Two copies would drift, and the failure — a debug session
  running a different compiler than the editor was analysed with — is invisible until behaviour
  disagrees.
- Registering one class in two module descriptors makes the extension run twice. That produced a
  duplicated gutter arrow once; `checkIntegrationGlue` now fails the build on it.

## The integration-glue contract

`flix-integration.yaml` declares which class plays which role and which module registers it.
`./gradlew checkIntegrationGlue` (part of `check`) fails on drift **in both directions** — declared
but unregistered, and registered but undeclared. It also enforces the ADR invariants: no
`debugAdapterServer` while the backend is `intellijJvm`, no `setDefaultStratum` call, `.flix` only.

`docs/flix-integration-matrix.md` is generated from it and committed; a stale one fails the check.
Logic lives in `buildSrc/` because Gradle's configuration cache cannot serialize script references.

Adding an extension means editing **both** the module descriptor and the manifest. That is
deliberate: the descriptors carry per-registration rationale in comments that no generator could
reproduce, so they are verified rather than generated.

## Debugging architecture

`FlixPositionManager` maps JDI locations to `.flix` source and back. Three things about it are
counterintuitive and were each learned from a failure:

1. **`NoDataException` means "ask the next manager", not "no".** `CompoundPositionManager` stops at
   the first manager that returns *without throwing*, and the platform's `PositionManagerImpl`
   answers unconditionally for any file type. So for a `.flix` position, returning empty is the
   final answer; throwing delegates it to the Java manager, which will happily bind a breakpoint in
   an unrelated class.
2. **Ownership is asymmetric.** `getSourcePosition` is keyed on a *location* and must keep throwing
   for foreign ones. `locationsOfLine`/`getAllClasses`/`createPrepareRequests` are keyed on a
   *position*; once it is Flix, every answer is final.
3. **Class-prepare filtering is per line, not per file.** One `.flix` file compiles to dozens of
   classes each covering part of it. A class lacking the line reports "no executable code", and
   `RequestManagerImpl.setInvalid` makes that stick if it lands first.

`FlixSteppingListener` + `FlixSteppingFilter` implement Step Over. CPS invokes every continuation
from one trampoline loop, so successive Flix lines sit at the same JVM depth — JDI's depth-based
Step Over cannot express "stay in this function", so the definition is captured at step start and
carried.

The run configuration must satisfy **two** `GenericDebuggerRunner` gates on **different objects**:
`ModuleRunProfile` on the configuration (`canRun`) and `RemoteConnectionCreator` on the *state*
(`createContentDescriptor`). Failing either produces the same silent nothing. `FlixDebuggerRunnerGatesTest`
pins both.

## Working with the platform

When platform behaviour is in question, **decompile it** rather than reasoning from the API or from
GitHub `master`, which differs from the shipped build:

```console
IDE=.intellijPlatform/ides/IU-2026.1.3
java -cp "$IDE/plugins/java-decompiler/lib/java-decompiler.jar" \
  org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler -dgs=true <classes-dir> <out-dir>
```

Debugger classes live in `$IDE/plugins/java/lib/modules/intellij.java.debugger.impl.jar`.

For questions about what a debugger can actually do with a class, **ask a live VM**. `javap` showing
a line in the `LineNumberTable` does *not* mean a breakpoint can bind to it — only
`ReferenceType.locationsOfLine` answers that, and the two have disagreed here in a way that cost
four rounds of class-file inspection. `scripts/FlixLineProbe.java` settles it in one run:

```console
java scripts/FlixLineProbe.java <jdwp-port> Main.flix <first-line> <last-line>
```

Reach for it before reading bytecode, not after.

## The Flix compiler

Resolved as `$FLIX_FORK_JAR`, else the newest `flix-vendor-*.jar` in the project root. Non-obvious
rules, all encoded in `FlixLaunchCommand` and pinned by tests:

- Options go **after** the subcommand. `flix --Xdebug run` fails with an error naming neither.
- `--Xdebug` is not only a JDWP switch: `Let`, `ApplyDef`, `ApplyClo`, `IfThenElse` and `Stm` emit
  line numbers *only* under it. Without it most statements have no breakpointable line.
- The JDWP agent goes before `-jar`; everything after it is the compiler's own argument list.

## Testing

- `debugger` and `shared` use JDI/plain stubs — no platform fixture, so they run fast.
- `language` uses `ParsingTestCase` against real parsed PSI.
- The **corpus gate** (`FlixCorpusTest`) parses the upstream Flix corpus and is a ratchet at 427/427.
  It skips when no checkout is found locally, but **fails when `CI` is set**, because CI clones the
  pinned revision and JUnit 3 has no skip state to distinguish a skip from a pass.
- Point it at a checkout with `-PflixCorpusDir=/path/to/flix` or `FLIX_DIR`.

This suite has a history of tests that passed while proving nothing. When adding one, check it fails
against the broken code — mutating the implementation and rerunning is cheap and has caught real
gaps here.

## Where things are recorded

- `docs/native-debugger-gate.md` — the Phase-3 proof, its results table, and a runbook for when a
  breakpoint does not bind.
- `docs/phase-8-verification.md` — which verification rows are established and, deliberately, which
  are **not measured**. Read before claiming anything works.
- `docs/intellij-flix-parser-evaluation.md` — the corpus gate's evidence.
