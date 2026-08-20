# Phase 8 — verification coverage

What the plan's three verification matrices ask for, and what is actually established today.

Written to be read before claiming the milestone complete. A row marked **not run** is not a row
that will probably pass; it is a row nobody has measured. Where a row is unreachable, the evidence
for that is recorded rather than the conclusion alone.

Status as of 2026-07-28, at `4e7b8a8`. 230 automated tests, 0 failures.

> **2026-08-20.** Section 2's rows were measured against a compiler in which `flix run` compiled and
> ran the program in one JVM. `flix-fork@a282efce0` (2026-08-12) made `flix run` fork the program
> into a JVM of its own, which silently detached every debug session from the program it was meant
> to be debugging; the plugin's debug launch is now two phases and starts the program itself. See
> the [gate](native-debugger-gate.md)'s note and rows 15–16.
>
> Section 2's rows are about the position manager and the platform, which that change does not
> touch, and are left as they stand. What has been **re-measured** is only that a `.flix` line binds
> under the new launch (gate row 15). Rows 13–14 below record what the change added and what it
> still does not establish.

| | Meaning |
| --- | --- |
| ✅ | Established, with the test or gate row named |
| ⚠️ | Partly established — what is missing is stated |
| ⬜ | Not run |
| ✖ | Not reachable, with evidence |

---

## 1. Language and integration

| # | Requirement | Status |
| --- | --- | --- |
| 1 | Adopted parser, recovery, fuzz, incremental-edit, editor, folding and gutter tests pass | ✅ 92 tests in `:language` — `FlixParsingTest`, `FlixErrorRecoveryTest`, `FlixRareSyntaxTest`, `FlixRareSyntaxRecoveryTest`, `FlixRareSyntaxFuzzTest`, `FlixIncrementalEditTest`, `FlixEditorBasicsTest`, `FlixFoldingTest`, `FlixRunLineMarkerContributorTest` |
| 2 | The 428-file corpus is lossless, error-free, and matches the declaration/`def main` oracle | ✅ **427 of 427 (100%)**, up from 211 (49.3%) at adoption; one documented exclusion. `FlixCorpusTest`, see [parser evaluation](intellij-flix-parser-evaluation.md) |
| 3 | Exactly one language, file type, parser, highlighter, gutter marker, LSP session and run configuration | ✅ `FlixPluginDescriptorTest` (8 checks) and `checkIntegrationGlue` statically; `FlixAssembledPluginTest` at runtime |
| 4 | LSP completion, hover, diagnostics and `flix.runMain` work after `languageMapping` | ✅ all four. `flix.runMain`'s argument handling is unit-tested (`FlixRunMainActionTest`, 7 cases); the rest are exercised by [`scripts/flix-lsp-probe.py`](../scripts/flix-lsp-probe.py) against the real server — hover returns `def println(x: a): Unit \ IO` with docs, completion returns 6 items while typing, diagnostics report 0 errors on a clean file and reported 5 before the fork LSP fix |
| 5 | `checkIntegrationGlue`, build and plugin verification pass | ✅ all three, and `verifyPlugin` now actually checks something. It had a CI job on every push and pull request, which `releaseDraft` depends on, but **no IDE targets were configured** — so it compared against nothing and reported success. With `IC-2026.1.3` and `IU-2026.1.3` declared, its first real run found **8 violations**: three uses of `SteppingListener`, which is `@ApiStatus.Internal`, and five deprecated APIs. Both are fixed — the stepping hook moved to the public `JvmSteppingCommandProvider` — and both targets now report **Compatible**. This also corrected a claim made earlier in this document's own history, that every extension point in use was public |
| 6 | Root-plugin integration tests: every content-module descriptor loads in the assembled plugin, exactly one Flix language stack, backend-only extensions absent from the frontend | ✅ `FlixAssembledPluginTest` — asserted through platform lookups, so it fails if a module drops out of `<content>` or a registration lands in the wrong module. Fault-injected: a second `runLineMarkerContributor` registration fails it |

---

## 2. Native mixed-debug matrix

Rows map to the [native-debugger gate](native-debugger-gate.md), which is **Green**.

| # | Requirement | Status |
| --- | --- | --- |
| 1 | A Flix breakpoint set before class loading verifies and stops | ✅ gate row 1 |
| 2 | A Java breakpoint set before class loading verifies and stops in the same session | ✅ gate row 2 |
| 3 | Step Into moves Flix → project Java | ✅ gate row 3 |
| 4 | Step Over remains in Java | ✅ gate row 4 |
| 5 | Step Out returns to the correct Flix source line | ✅ gate row 5 |
| 6 | The stack contains navigable Flix and Java frames with correct files and lines | ✅ gate row 6 |
| 7 | Java locals, watches/evaluation, **and exception breakpoints** work in Java frames | ✅ locals and evaluation (gate row 7); exception breakpoints now too. `FlixDebugProbe --exception java.lang.IllegalStateException` stopped at the throw in `Greeter.java:45`, reported the catch location as `Greeter.java:38`, and showed the Java frames over `Clo$main$399986 Main.flix:52`. Caught *and* uncaught were requested: a breakpoint that only saw uncaught throws would miss every recovered failure. Flix `Clo$` frames show no variables — not a defect, see the gate's *Why some Flix frames show no variables* |
| 8 | Attached project/library/JDK Java sources resolve through the platform debugger | ⚠️ project sources ✅ — stepping into `Greeter.java` lands on the right lines, and the stacks above navigate. JDK and library source *attachment* ⬜: it is the IDE matching a `-sources.jar` to a frame, which happens above JDI and therefore cannot be probed. It is also stock platform behaviour this plugin neither extends nor intercepts |
| 9 | **Multiple generated classes for one Flix source do not duplicate or miss breakpoints** | ✅ — the cause was a compiler defect, not a plugin one; see below |
| 10 | Pause, continue, terminate and detach have correct lifecycle semantics | ✅ gate row 11, and detach measured separately below: attaching and then disposing resumes the debuggee, which runs to completion and exits by itself |
| 11 | A launch failure or missing Flix jar produces an actionable error and no orphan process | ✅ **both halves.** *Error:* `FlixRunConfigurationTest` asserts `checkConfiguration` refuses through the assembled plugin's own configuration type and that the message names both the file to supply and the `FLIX_JAR` override — "not found" is not actionable. Fault-injected: silencing the check fails it. `FlixJarTest` (7 cases) covers resolution. *Orphan:* measured on a live debuggee, see below |
| — | *(added by review)* The two `GenericDebuggerRunner` gates are satisfied | ✅ `FlixDebuggerRunnerGatesTest` — both were failing, which is why Debug could never have attached |
| — | *(added by review)* The attach port matches the port on the debuggee's command line | ✅ `FlixLaunchTest` |
| 12 | No DAP process or second JDI/JDWP client starts | ✅ gate row 12, and now structurally enforced: `checkIntegrationGlue` fails the build if a `debugAdapterServer` is registered while the backend is `intellijJvm` |
| 13 | *(added 2026-08-20)* **The debuggee is the program, not the compiler** | ✅ `FlixDebuggeeIdentityTest` statically — the agent and the program's main class must be on one command line, and the compiler jar must not appear on it. Fault-injected: replacing `-cp` with `-jar` fails two of its assertions. Live: gate row 15, 3 classes from `Main.flix` prepared and lines 5–8 `can bind`, against **0** and `absent` under the old launch |
| 14 | *(added 2026-08-20)* **Test debugging** | ⚠️ **binding measured, gesture not.** `flix test` runs the tests in the compiler's own JVM (`Bootstrap.testWith` reflects them), so an agent there is on the right process: gate row 16 prepared 2 classes from `TestMain.flix` with lines 6–8 `can bind`, from a terminal launch. ⬜ **Not measured, and not implemented:** pressing Debug on a test in the IDE. There is no test debug configuration — the test task is a plain `flix test --events-json` run with no agent and no `RemoteConnectionCreator` — so today the answer is that a user cannot do it at all |
| 15 | *(added 2026-08-20)* **A breakpoint stops once per line, not once per handled effect** | ✅ gate row 17. `Lowering.wrapInHandler` gave every default-handler frame the body's location, so five classes reported `Main.flix:18` and Continue stopped there four times before reaching the statement. Fixed in the compiler; pinned by `TestLineNumberTable`. Whole-program measurement on `flix-proc-invaders`: classes holding that line 5 → **1**, project and library source lines made unreachable **0** |
| 16 | *(added 2026-08-20)* **Flix values render as values, not as their compiled shape** | ✅ `FlixValuesTest`. Both renderers were registered under unqualified names (`Record$`, `Tagged$`) while JDI reports `dev.flix.gen.Record$`, and `DebuggerUtils.typeEquals` compares qualified names verbatim -- so neither ever applied and the variables view showed `{RecordExtend$Obj@1234}` and a `label`/`value`/`rest` chain. Matching now happens on the simple name anywhere in the hierarchy, so the package moving again cannot repeat it. Measured on `flix-proc-invaders`: 202 classes extend `Tagged$`, **156** of which carry a real tag name |
| 17 | *(added 2026-08-20)* **A Flix value expands to its own shape, not the compiled one** | ✅ `FlixValueTreeTest` over JDI stubs. A record expanded through its `label`/`value`/`rest` chain, so reading the third field meant opening three nested nodes named after the chain. Both renderers now supply children: a record flattens to one node per field, named by its label, and a tagged value to its payload — with `ordinal` kept only for the shared representations, where the label *is* the ordinal. Fault-injected twice: truncating the tree to the label's limit, and reading the label through `renderScalar`, each fail it |
| 18 | *(added 2026-08-20)* **A watch can be written in Flix** | ⚠️ **navigation only, by design.** `FlixCodeFragmentFactory` registers against `com.intellij.debugger.codeFragmentFactory` and answers a variable plus record projections — `at`, `at#dir`, `at#dir#name` — by *reading* the paused frame; no code runs in the debuggee, so no effect policy applies. `FlixNavigationTest` and `FlixEvaluatorTest` pin the grammar and every refusal message. ⬜ **Not implemented:** calls, operators, interpolation, pattern matching — each refused by naming the limit, since those need the compiler to resolve, type and lower the expression and a runtime bridge to execute it. Depends on the compiler naming a frame's captures and parameters (gate row 18); before that, no name in an effectful frame resolved at all |
| 19 | *(added 2026-08-20)* **A Flix value shows no compiled class name** | ✅ `FlixValueTreeTest`. The platform composes `"{" + idLabel + "}" + valueText`, so a rendered record read `{RecordExtend$Obj@1127} { dir = … }`. The renderers now clear the id label, which names a class the programmer never wrote — and only when they produced a label, since an empty one means the renderer failed and the identity is all that is left. ⚠️ **Superseded for the frame line** (`applyFrame:180, Tuning$Clo$path$…`): the *label* is indeed unreachable — `StackFrameDescriptorImpl.calcRepresentation` builds it from `myMethod.name()` and `refType.name()`, and `JavaFramesListRenderer` is package-private — but the **frame** is not. See row 21 |
| 20 | *(added 2026-08-20)* **The Flix call chain is visible** | ✅ `FlixContinuationsTest` over JDI stubs built to shapes read off a live stop, plus the compiler's own field names. Stopped at `Tuning.flix:180` in `flix-proc-invaders` the frames view showed **1** Flix frame and *35 hidden*, and unhiding them showed only `Handler$.installHandler`, `ResumptionCons$$Lambda.invoke` and trampoline wrappers: under CPS one Flix frame is live at a time, so `main → readTuning → path → location` is not on the JVM stack at all and no frames-view setting could reveal it. `FlixAsyncStackTraceProvider` reconstructs it from the heap and the platform draws it under an *Async stack trace* separator, as it does for coroutines. Measured with `FlixChainProbe.java`: the chain is held as `Frames$` cons lists in the arguments of the nested `installHandler` frames, **4** entries at the outermost against 3 and 2 within — nested suffixes, which is why the longest is the complete one. Fault-injected seven ways (first chain instead of longest, resumptions ignored, suspension prefix ignored, cycle guard removed, first line instead of lowest, empty list instead of null, `.flix` check dropped); each fails it. Answered only for the topmost frame, established by decompiling `JavaExecutionStack`: it schedules what a provider returns with a **null** frame iterator, so the reconstruction *replaces* the frames below rather than following them. ⬜ **Not measured:** the rendering itself — that an entry navigates on click, and where the separator is drawn, are platform plumbing reached only through the IDE |
| 21 | *(added 2026-08-20)* **Frames are named in Flix** | ✅ `FlixFrameNamesTest`, 11 cases. A frame in `readTuning` read `applyFrame:88, Def$readTuning (dev.flix.gen)` — the continuation's method, the lowered class and the back end's package, none of them written by anyone. It now reads `readTuning(), Main.flix:88`, live frames and reconstructed ones alike. The definition is recovered by inverting the compiler's own naming (`JvmName.mkNamespacedClassName`, `mangle`, `Symbol.generatedDefnSym`), so `Tuning$Def$path` → `Tuning.path`, `Def$$plus` → `+`, and a lifted lambda's eleven-character Base58 hash is dropped. Fault-injected five ways; four failed it and the fifth **did not** — the claim that unmangling must precede hash-stripping was wrong (`exclamation` is eleven characters but Base58 excludes `l`), and both the comment and the test now say what is actually true. The route is `PositionManagerWithMultipleStackFrames.createStackFramesAsync`, which `JavaExecutionStack.createFrames` consults before building a `JavaStackFrame` itself — supplying the frame, not intercepting the label, is what row 19 had missed. ⬜ **Not measured:** the rendering, as in row 20 |
| 22 | *(added 2026-08-20)* **A breakpoint binds and hits in a real session** | ✅ `FlixDebugSessionTest`, over JDWP with no IDE in it: compiles a four-statement fixture with the real compiler, launches it through `FlixLaunchCommand.debugProgram` at `suspend=y`, attaches, arms through a class-prepare watch (nothing of the program is loaded while it waits, which is why enumeration cannot work), hits, and asks the plugin's own rules about the location: **1** class holds the line with **1** location in it, `isFlixLocation` accepts it, the line maps back, and the frame reads `main(), Main.flix:5`. It also measures something previously only asserted in stubs — with no SMAP the class exposes an **absolute** `SourceFile`, so the source name is matched by suffix. Skips without a compiler jar (`FLIX_JAR`, or `flix.jar` in the root); it does not fail under CI the way the corpus gate does, because nothing here builds a compiler. ⬜ **Not covered, and proved so:** the SMAP branch — no class in a whole build declares `SourceDebugExtension`, and renaming the `Flix` stratum constant leaves this test green. Also not covered: effects/the trampoline, and the gesture (rows 20–21) |
| 23 | *(added 2026-08-20)* **A tagged value says which case it is** | ✅ `FlixValuesTest`, `FlixValueTreeTest`, and live in `FlixDebugSessionTest`. A case *with terms* is compiled to a class shared by every case of its erased shape — `Tag$Obj` is `Some`, `Ok` and `Cons` at once — so the class name says nothing and only `ordinal` separates them, which is why the variables view read `#1("/home/x")`. The enum the ordinal indexes into is erased with the type, so no reader could resolve it: the compiler now writes the case name into the value under `--Xdebug` (`BackendObjType.Tagged.NameField`, pinned by the fork's `TestTagName`), and the renderer prefers it over the class name. Proved end to end rather than by inspection — reverting the plugin's preference turns the live assertion back into `#1("/home/x")`. An **optimized build carries neither the field nor the write**, and falls back to the ordinal exactly as before |
| 24 | *(added 2026-08-20)* **A chain entry shows where its frame is, and the chain is the current one** | ✅ `FlixContinuationsTest` and two live sessions in `FlixDebugSessionTest`. Every entry used to point at its definition's *first* line, which for a chain of calls in flight is wrong for every frame: the position is the continuation's `pc`, a `tableswitch` key that only a disassembler could follow. `--Xdebug` now records the answer as a `pcLines` constant (fork `TestResumeLines`, which recomputes it from the switch and the line table rather than restating it), and `FlixResumePoints` reads it — measured live, `main` on the chain reads `main(), Main.flix:16`, its call to `both`, not `Main.flix:14`, its declaration. The **selection rule changed with it**: three lists are reachable at that stop — `[main]`, `[both, main]`, `[one, both, main]` — and the longest begins at a call that had already returned, so the chain is chosen by its head (the definition executing now) and that head is then dropped, since the frames view already shows it. Nothing matching means nothing to show. Fault-injected three ways; each fails it |
| 25 | *(added 2026-08-20)* **A list reads as a list** | ✅ `FlixValuesTest`, `FlixValueTreeTest`, and live in `FlixDebugSessionTest`. A `List` is a chain of `Cons` cells, so the variables view showed `Cons(-96.0, Obj)` with one nested node per element, each named `v1` after the field holding the rest — reading the fourth element took four clicks and named none of them. It now reads `-96.0f32 :: -64.0f32 :: -36.0f32 :: Nil` and expands to `[0]`, `[1]`, `[2]`. Recognised by the **recorded case name**, never by shape: a two-term case is compiled into a class shared with every other two-term case, so a structural rule would walk any nested pair as a list — asserted, with a `Shape.Pair` that must stay a tag. That required the compiler to qualify the recorded name by its enum, and the enum arrives **monomorphised** (`List$Vv4NSpVAmjE.Cons`, measured), so the plugin removes the stable-hash suffix with the same rule that undoes a lifted lambda's. Scalars are now written as Flix literals — `-96.0f32`, since an unsuffixed `-96.0` would be a Float64 if typed back in. Both cycle guards (list and record) are now genuinely covered: the JDI stub reported fields captured at construction, so the back-reference of a cyclic fixture was invisible and **both cycle tests passed with the guards removed**; the stub now reads the field map on every call, and each guard's removal fails its test. Fault-injected five ways in total |
| 26 | *(added 2026-08-21)* **The SMAP branch is covered, and only reachable one way** | ✅ `FlixDebugSessionTest`, live. A class carries `SourceDebugExtension` only when it holds code from more than one file, which only inlining produces — and `--Xdebug` turns the optimizer off, so **a build the plugin launches never has any**: measured, one class with SMAP in an optimized two-file build and **zero** in the same build with `--Xdebug`. The branch is therefore reachable only when attaching to a program built normally, and the test now does exactly that: it builds without `--Xdebug`, attaches, waits for a class declaring the `Flix` stratum, and asserts that the raw line (`ToString.flix` code appears at output line 231, which exists in no file) and the raw source (the class's own `SourceFile`) both differ from what the plugin resolves — `ToString.flix:69`. Fault-injected: renaming the stratum constant fails it. One thing the measurement corrected: an SMAP names its own default stratum and the compiler writes `Flix` there, so JDI resolves such a location without being asked — the plugin's stratum handling is a **dispatcher** (a class without SMAP has no `Flix` stratum, and asking for one throws) rather than a translator |
| 27 | *(added 2026-08-21)* **A module can load the platform classes it uses** | ✅ `FlixPlatformDependenciesTest`. Pressing Debug in an installed 2026.2 IDE threw `NoClassDefFoundError: com/intellij/execution/configurations/RemoteConnection` at the first line of `FlixLaunch` that mentions it. A plugin dependency does **not** bring the depended-on plugin's content modules with it — each has its own classloader — and `RemoteConnection` lives in `intellij.java.execution`, which the debugger descriptor never asked for. Nothing caught it: the compiler resolves against the whole distribution, the sandbox resolved it too, and the Plugin Verifier reported **Compatible** both before and after the fix, because the class is in the distribution either way. The check asks the JVM which jar each imported class came from — `…/modules/<id>.jar` names the module — and compares that against every module descriptor. Fault-injected: removing the declaration reproduces the failure by name. ⬜ **Measured but not acted on:** in **2026.2** `JBCefApp` (used by `backend`) moves into `intellij.platform.ui.jcef` and the test-console classes (used by `language`) into `intellij.platform.testRunner`; neither module is declared by anything in 2026.1, so a dependency on them cannot be added while 2026.1 is supported. `intellij.platform.smRunner` exists in both. The check judges against the platform the build compiles against and will report these when it moves |

### Rows 10 and 11 — what an orphan actually requires

Both were measured against a live debuggee launched with the command line `FlixLaunchCommand`
builds, since the question is about process lifetime rather than about any code this plugin runs.

| Situation | Result |
| --- | --- |
| Launched, **nobody ever attaches** | Alive indefinitely and making no progress — 25 s later the output had not grown by a byte. This is the orphan, and nothing in the JVM resolves it |
| Launched, attached, then **detached** (`VirtualMachine.dispose`) | Resumed, ran to completion, printed its output and exited on its own. No orphan |

The first row is the reason `startProcess` returns a `KillableColoredProcessHandler` rather than a
plain one: `suspend=y` is required so a breakpoint on the first line can bind before the program
moves, and its cost is a process that waits forever if the session never starts. The platform
killing that handler is the only thing that cleans it up — so the two `GenericDebuggerRunner` gates
fixed in `e18ec1b` were not merely "no session starts", they were "no session starts *and* a
compiler JVM is left waiting".

The second row is the reassuring one, and it is why Detach needs no special handling.

### Row 9 — resolved, and it was not the plugin

Breakpoints on `Main.flix` lines 102–105 never bound while 101, 106 and 107 did. Artifact
inspection had ruled out every plugin-side explanation, correctly: the classes, line tables, SMAP
and reachability were identical for the failing and working lines.

The cause was in the compiler. `LineNumbers.emit` dropped a *repeated* line but not a *different*
line arriving at an offset that already had an entry — and when a single-expression function is
inlined into a call site in the same file, the call site's line and the inlined body's line are
emitted at one instruction. The second entry replaces the first, and the replaced line becomes
invisible to the debugger even though `javap` still shows it.

Fixed in `flix-fork` as `acde240`; verified with a JDI probe against a live VM, which is the check
that should have been run first. `javap` showing a line in the table does not mean a debugger can
bind to it — only `locationsOfLine` answers that.

Two further compiler defects of the same family were found the same way and fixed since. Both are
described in full under
[the native-debugger gate](native-debugger-gate.md#the-first-statement-of-every-function--the-same-defect-one-layer-up):

- **The first statement of every function was unbindable.** A method records its declaration line
  before any instruction, claiming bytecode offset 0 — where the body's first statement also begins.
  The declaration won. Declaration entries are now provisional, so the statement takes the offset;
  the consequence is that a breakpoint on a bare `def` line no longer verifies, which is correct
  because it never described an instruction of its own.
- **Single-expression functions were inlined away.** A folded-in function gets no class, and its
  line does not survive at the call site either, so it existed nowhere in the program. `--Xdebug`
  now turns the optimizer off.

### Focused SMAP tests

The plan asks for these specifically. 49 tests in `:debugger` cover most:

| Case | Status |
| --- | --- |
| Path normalization | ✅ `FlixSourceLocationsTest` |
| Duplicate base names | ✅ `FlixForwardResolutionTest` — resolves to **neither**, which is also why gate row 10 is unreachable live |
| Index-independent source lookup | ✅ `FlixSourceFilesTest` — an absolute source attribute resolves straight from the VFS. It has to: a class-prepare event fires once per class per VM, so a lookup that transiently answered "no" while the project reindexed disabled that breakpoint for the whole session. The Flix compiler writes its output *inside* the project, so a re-index on every run is guaranteed rather than incidental |
| Absent information | ✅ `FlixSourceLocationsTest` — `AbsentInformationException`, absent line tables, unknown lines |
| Multiple locations per line | ✅ `FlixSourceLocationsTest`, and the row-9 work above |
| Class prepare | ✅ `FlixClassSelectionTest` drives `FlixLineOnly.processClassPrepare` directly — only a class holding the line reaches the breakpoint |
| Stepping-filter wiring | ✅ `FlixSteppingFilterTest` — added after review; mutation-checked (inverting the arrived-check, dropping the budget, stepping out instead of in, or weakening the return-to-caller depth guard all fail it) |
| Step Over returning to its caller | ✅ `FlixSteppingFilterTest` — stepping over a function's *last* line used to walk past the caller and run on to the next breakpoint, because the caller is a different definition. The caller and the stack depth are now recorded at step start; both are required, so mutual recursion is stepped over rather than stopped in |
| **Class redefinition** | ✅ `FlixSourceCacheTest` — a hot swap keeps the same `ReferenceType`, so only invalidation can yield the new sources |
| **Stale cache invalidation** | ✅ `FlixSourceCacheTest` — cleared on resume, which is when a redefinition can have happened |

---

## 3. JVM-language interoperability matrix

`flix-lab` now carries a `Greeter` in **five** languages — Java, Kotlin, Scala, Groovy and JRuby —
each in its own jar, all called from `main`. That is the fixture this matrix needs, and it makes
most of these rows runnable for the first time. JRuby is beyond what the plan asks for.

Four of them have now been stopped in from a single Flix-launched session, each frame over its own
Flix caller. That part needs no language plugin at all: binding a breakpoint and reading a frame's
source uses the JVM's own metadata, which is exactly why non-interference is a property this plugin
can guarantee rather than merely hope for. What the plugins add — source navigation, per-frame
evaluators, language-aware stepping — sits above JDI and is where an IDE becomes necessary.

| # | Requirement | Status |
| --- | --- | --- |
| 1 | **Required:** Flix → Java → Flix-return, with breakpoints, stepping, stack, locals, evaluation, exceptions, source lookup | ✅ breakpoints, stepping, stack, locals, evaluation (gate rows 3–7, `Greeter.java`) and exceptions (matrix row 2.7). ⬜ library/JDK source *attachment* only — see row 2.8 for why that one is not probeable |
| 2 | **Required:** Flix → Kotlin/JVM → Java → Flix-return, `.kt` and `.java` breakpoints, frame-specific evaluators | ⚠️ **breakpoints and mixed stack proven; the evaluator is not.** [`scripts/FlixDebugProbe.java`](../scripts/FlixDebugProbe.java) armed `Greeter.kt:18` in one session launched from Flix: bound in 1 class, hit, and the stack read `Greeter.kt:18` over `Clo$main$400234 Main.flix:102` over `Def$main Exit.flix:45`. Each frame resolved through its own stratum. Which *evaluator* the IDE offers per frame is an IDE-side choice a JDI probe cannot observe |
| 3 | **Required:** Kotlin inline-function and lambda frames do not steal or mis-map Flix positions | ✅ `Greeter.kt` now has `private inline fun decorate(...)` taking a lambda, which emits both `*S Kotlin` and `*S KotlinDebug` strata. Line 18 (the call site) and 17, 22 are addressable; line 42 (the inline body) is not, because inlined bodies map to synthetic lines under `KotlinDebug` — the Kotlin plugin's stratum to resolve, not ours. The mis-mapping this row guards against cannot occur: the position manager declines every non-`.flix` file type (row 9), and the stack above shows the Flix frames under an inlining Kotlin frame keeping their correct lines. Worth knowing that Scala 3 differs here — see row 6 |
| 4 | **Conditional:** coroutine parity, if claimed | ✖ **not claimed.** The plan forbids claiming it without proving the Kotlin debugger's coroutine agent is injected into an externally launched JVM. It is not, and no such claim is made |
| 5 | **Optional Scala profile:** Flix → Scala 3 → Java → Flix-return | ⚠️ **breakpoints and stack proven; navigation and the Scala evaluator are not.** `FlixDebugProbe` armed `Greeter.scala:19`, bound in `dev.wstein.flixlab.scala.Greeter$`, hit, and the stack read `Greeter.scala:19` over `Clo$main$400242 Main.flix:103`. That much needs no Scala plugin — it is the JVM's own metadata. Source *navigation* and the Scala evaluator do need the plugin and an IDE |
| 6 | **Optional Scala profile:** inline, extension and given-generated frames | ✅ for what this row actually asks — that such frames do not corrupt the Flix positions beneath them. `Greeter.scala` now carries an `inline def`, an `extension` method and a `given`; all three bind. Stopping in the inline body (`:50`) and in the extension body (`:43`) both left the Flix frames underneath correctly attributed — `Clo$main Main.flix:103` over `Sys/Env.flix:124` in each. The extension case is the sharper one: its caller frame reports `Greeter.scala:19`, the inlined call site, and the Flix frames below it are unaffected by that re-attribution |
| 7 | **Optional Groovy smoke:** a Groovy breakpoint and frame work; Flix declines its source | ✅ `FlixDebugProbe` armed `Greeter.groovy:15`, bound in `dev.wstein.flixlab.groovy.Greeter`, hit, and the stack read `Greeter.groovy:15` directly over `Clo$main$400113 Main.flix:104`. Flix declining `.groovy` is asserted in `FlixSteppingPolicyTest` and `FlixSourceLocationsTest` — which is the whole of what this row asks for |
| 8 | **Plugin-absent:** Flix/Java debugging and plugin loading work without Scala or other optional plugins | ⬜ not run. The structure supports it — the debugger module is not `loading="required"` and scopes its Java dependency (`FlixPluginDescriptorTest`) — but no IDE without those plugins has been tried |
| 9 | **Ownership:** the position manager and breakpoint type decline Java/Kotlin/Scala/Groovy locations | ✅ **automated, in three places**: `FlixSourceLocationsTest` declines those classes; `FlixSteppingPolicyTest` leaves `.kt`, `.scala` and `.groovy` frames alone — including a Java class compiled without `-g`, so "no line info" alone never triggers Flix behaviour; `FlixPositionManagerDelegationTest` pins why `.flix` positions must not be delegated |

Row 9 being automated matters more than its position in the list suggests: it is the guarantee this
plugin owes every other JVM language, and it is the one row that cannot regress silently.

---

## 3a. Automated UI smoke test

`./gradlew testIdeUi` — `src/integrationTest`, JetBrains' Starter framework driving a real IDE
against a hermetic fixture. Outside `check` on purpose: it is not headless, it takes over the cursor
on macOS, and on Linux it needs `xvfb` **and** a window manager.

| Plan item | Status |
| --- | --- |
| 1. One, not two, `def main` gutter actions | ⚠️ **runs and passes**, but proves less than the wording suggests — see below |
| 2. Breakpoint placeable on an executable line; refused on a non-executable one | ⬜ written, `@Disabled` with its blockers recorded in the annotation |
| 3. Debug creates a native Java-debug session, never a DAP process | ⬜ written, `@Disabled`; `driver-sdk` has no session API |
| 4. Stop, Step Into Java, Step Out | ⬜ not attempted |
| 5. Terminate closes process and session | ⬜ not attempted |

**Item 1 does not catch the duplicate arrow.** The "exactly one" assertion looked like the
regression test this project has wanted since the duplicated gutter marker, so it was fault-injected
both ways — a repeated registration inside one descriptor, and the historical cross-module pair.
Both still rendered a single icon: the platform merges markers at one offset.
`checkIntegrationGlue` remains the only thing that detects a duplicate registration, structurally.
What the UI test does catch is the marker going **missing**, injected by narrowing the contributor's
name match, and observed for real when the `backend` module failed to load.

Three findings came out of getting this far, none of which any other test could have produced:

- **The plugin zip alone does not bring LSP4IJ**, and the failure is quiet. The IDE logs
  `Module flix.jetbrains.plugin.backend is not enabled because dependency ... is not available`
  and carries on. The language layer still works — file type, highlighting, parsing all fine — so
  the IDE looks healthy while the gutter marker and the entire LSP session are absent.
- **Starter ignores the Gradle sandbox.** It builds its own IDE under `out/ide-tests`, so
  `testIdeUi { plugins { ... } }` configures something the run never reads; the dependency has to be
  installed through `PluginConfigurator`. Fetching it from the Marketplace at test time 404s, since
  0.20.1 has no build-261 artifact — hence resolving the same zip the plugin compiles against.
- **Without a compiler jar the language server cannot start**, and the IDE then never finishes code
  analysis, so `openFile` times out on a daemon that will never settle. The test now fails early
  naming the missing jar instead.

---

## 4. Manual smoke test

| Requirement | Status |
| --- | --- |
| Adopted PSI and highlighting without TextMate | ✅ TextMate removed; highlighting confirmed in use |
| One green arrow beside `def main` | ✅ confirmed live, and now automated — `FlixUiSmokeTest` asserts it in a real IDE on every `testIdeUi` run |
| Run uses the canonical Flix configuration | ⬜ not run — the configuration landed in `7451ba4` |
| Debug opens the native JVM debugger | ⬜ **not run**, and three defects were fixed on the way to it, none confirmed live: a stale `DAPConfiguration` hijacking the gutter arrow (cleared), a producer building an unregistered configuration type (`fa7f2df`), and — the substantive one — both `GenericDebuggerRunner` conditions unsatisfied, so no debug session could ever have started (`e18ec1b`). What *is* now proven is everything downstream of the button: `FlixDebugProbe` attaches to the exact command line `FlixLaunchCommand` builds, binds, hits and walks mixed stacks. That narrows a failure here to the gesture and its wiring |
| Flix, Java and Kotlin breakpoints coexist | ⚠️ Flix + Java ✅. Kotlin binds and hits at JDI level in a Flix-launched session (interop row 2); coexistence *as IDE breakpoints* is unrun |
| Stepping crosses Flix↔Java and Flix↔Kotlin | ⚠️ Flix↔Java ✅ (gate rows 3–5). Flix↔Kotlin: the call crossing is proven by the mixed stack; stepping across it in an IDE is unrun |
| With the Scala plugin, a run crosses Flix↔Scala 3 | ⚠️ the crossing itself is proven by the mixed stack (interop row 5); doing it in an IDE with the plugin installed is unrun |
| Mixed stack frames navigate correctly | ✅ gate row 6 |
| LSP behaviour unchanged | ✅ and improved — the fork's Plain-LSP never loaded workspace jars; fixed and verified, 5 errors → 0 |
| Incomplete Flix code does not hang or hide later declarations | ✅ `FlixErrorRecoveryTest`, `FlixRareSyntaxFuzzTest`, and the corpus gate |

---

## What this leaves

Ordered by what blocks the milestone rather than by matrix position.

1. **The manual smoke test's Run and Debug rows.** The run configuration has never been exercised in
   a session, and three defects were found in it by review rather than by use — including two that
   made a debug session impossible. With DAP retired there is no fallback, so this is the highest
   risk item and the one that unblocks the most. It is now also the *narrowest*: everything the IDE
   would do after pressing the arrow — launch, attach, bind, hit, walk mixed stacks, catch
   exceptions, detach — has been exercised over JDWP against the same command line. What remains
   unproven is the gesture and its wiring.
2. **The per-frame evaluator**, interop row 2's last clause. Which evaluator the IDE offers when
   stopped in a Kotlin frame is decided by the IDE, so no JDI probe can observe it. Breakpoints,
   binding and the mixed stack are proven; this one clause needs the IDE.
3. **JDK and library source attachment** (row 2.8). Matching a `-sources.jar` to a frame happens
   above JDI, so no probe can reach it; it is also stock platform behaviour this plugin neither
   extends nor intercepts. Everything else in rows 2.7 and 2.8 is now evidenced.
4. **Optional profiles.** Groovy (row 7) and Scala row 6 are closed. Row 5's breakpoint and stack
   halves are proven; its navigation and evaluator halves need an IDE with the Scala plugin.
5. **Debugging a test from the IDE** (row 2.14). The compiler side is measured and works; the plugin
   side does not exist. A test debug configuration would need the same two `GenericDebuggerRunner`
   gates the program one has, and — unlike the program launch — it needs no build manifest, because
   `flix test` does not fork. Recorded here rather than left implied: "test debugging works" is true
   of the compiler and false of the plugin, and the two are easy to confuse.
6. **Rows 2.1–2.12 under the two-phase launch.** They were measured through the one-process launch
   and are carried forward on the argument that the change is upstream of everything they test.
   That argument is sound and it is still an argument. Re-running the gate's fixture set once
   against the new launch would replace it with a measurement.

### The two inliners do not behave alike

Worth carrying forward, because it decides where to look when an inlined breakpoint does not bind.
Kotlin's inline body is **not** addressable at its own line — those lines move to synthetics under
the `KotlinDebug` stratum, and only the Kotlin plugin resolves them. Scala 3's inline body **is**
addressable at its own line, in the class that inlined it. Same language feature, opposite answer
from `locationsOfLine`, and neither is this plugin's to arbitrate: both files are declined by file
type before any of it matters.
5. **Flix values in CPS frames.** A `Clo$` frame's state lives in fields `l0`…`l8` plus `pc`, not
   in locals, so the variables view is empty for those frames. Record and tagged-union *values* now
   render (`FlixRecordRenderer`, `FlixTaggedRenderer`), which is the half worth having. Surfacing the
   fields at the top level was considered and declined: `ExtraDebugNodesProvider` would do it, but
   building the nodes needs `NodeManagerImpl` from an `impl` package, and the payoff is one fewer
   expand while the names stay `l0`…`l8`. Recovering real names would need the compiler to emit them.

Nothing here is blocked on a decision. Every item is a measurement someone has to take, or a test
someone has to find a safe fixture for.
