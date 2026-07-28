# Phase 8 — verification coverage

What the plan's three verification matrices ask for, and what is actually established today.

Written to be read before claiming the milestone complete. A row marked **not run** is not a row
that will probably pass; it is a row nobody has measured. Where a row is unreachable, the evidence
for that is recorded rather than the conclusion alone.

Status as of 2026-07-28, at `4e7b8a8`. 230 automated tests, 0 failures.

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
| 11 | A launch failure or missing Flix jar produces an actionable error and no orphan process | ✅ **both halves.** *Error:* `FlixRunConfigurationTest` asserts `checkConfiguration` refuses through the assembled plugin's own configuration type and that the message names both the file to supply and the `FLIX_FORK_JAR` override — "not found" is not actionable. Fault-injected: silencing the check fails it. `FlixJarTest` (7 cases) covers resolution. *Orphan:* measured on a live debuggee, see below |
| — | *(added by review)* The two `GenericDebuggerRunner` gates are satisfied | ✅ `FlixDebuggerRunnerGatesTest` — both were failing, which is why Debug could never have attached |
| — | *(added by review)* The attach port matches the port on the debuggee's command line | ✅ `FlixLaunchTest` |
| 12 | No DAP process or second JDI/JDWP client starts | ✅ gate row 12, and now structurally enforced: `checkIntegrationGlue` fails the build if a `debugAdapterServer` is registered while the backend is `intellijJvm` |

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

### Focused SMAP tests

The plan asks for these specifically. 49 tests in `:debugger` cover most:

| Case | Status |
| --- | --- |
| Path normalization | ✅ `FlixSourceLocationsTest` |
| Duplicate base names | ✅ `FlixForwardResolutionTest` — resolves to **neither**, which is also why gate row 10 is unreachable live |
| Absent information | ✅ `FlixSourceLocationsTest` — `AbsentInformationException`, absent line tables, unknown lines |
| Multiple locations per line | ✅ `FlixSourceLocationsTest`, and the row-9 work above |
| Class prepare | ✅ `FlixClassSelectionTest` drives `FlixLineOnly.processClassPrepare` directly — only a class holding the line reaches the breakpoint |
| Stepping-filter wiring | ✅ `FlixSteppingFilterTest` — added after review; mutation-checked (inverting the arrived-check, dropping the budget, or stepping out instead of in all fail it) |
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
| 3 | **Required:** Kotlin inline-function and lambda frames do not steal or mis-map Flix positions | ✅ `Greeter.kt` now has `private inline fun decorate(...)` taking a lambda, which emits both `*S Kotlin` and `*S KotlinDebug` strata. Line 18 (the call site) and 17, 22 are addressable; line 42 (the inline body) is not, because inlined bodies map to synthetic lines under `KotlinDebug` — the Kotlin plugin's stratum to resolve, not ours. The mis-mapping this row guards against cannot occur: the position manager declines every non-`.flix` file type (row 9), and the stack above shows the Flix frames under an inlining Kotlin frame keeping their correct lines |
| 4 | **Conditional:** coroutine parity, if claimed | ✖ **not claimed.** The plan forbids claiming it without proving the Kotlin debugger's coroutine agent is injected into an externally launched JVM. It is not, and no such claim is made |
| 5 | **Optional Scala profile:** Flix → Scala 3 → Java → Flix-return | ⚠️ **breakpoints and stack proven; navigation and the Scala evaluator are not.** `FlixDebugProbe` armed `Greeter.scala:19`, bound in `dev.wstein.flixlab.scala.Greeter$`, hit, and the stack read `Greeter.scala:19` over `Clo$main$400242 Main.flix:103`. That much needs no Scala plugin — it is the JVM's own metadata. Source *navigation* and the Scala evaluator do need the plugin and an IDE |
| 6 | **Optional Scala profile:** inline, extension and given-generated frames | ⬜ needs a fixture using those constructs |
| 7 | **Optional Groovy smoke:** a Groovy breakpoint and frame work; Flix declines its source | ✅ `FlixDebugProbe` armed `Greeter.groovy:15`, bound in `dev.wstein.flixlab.groovy.Greeter`, hit, and the stack read `Greeter.groovy:15` directly over `Clo$main$400113 Main.flix:104`. Flix declining `.groovy` is asserted in `FlixSteppingPolicyTest` and `FlixSourceLocationsTest` — which is the whole of what this row asks for |
| 8 | **Plugin-absent:** Flix/Java debugging and plugin loading work without Scala or other optional plugins | ⬜ not run. The structure supports it — the debugger module is not `loading="required"` and scopes its Java dependency (`FlixPluginDescriptorTest`) — but no IDE without those plugins has been tried |
| 9 | **Ownership:** the position manager and breakpoint type decline Java/Kotlin/Scala/Groovy locations | ✅ **automated, in three places**: `FlixSourceLocationsTest` declines those classes; `FlixSteppingPolicyTest` leaves `.kt`, `.scala` and `.groovy` frames alone — including a Java class compiled without `-g`, so "no line info" alone never triggers Flix behaviour; `FlixPositionManagerDelegationTest` pins why `.flix` positions must not be delegated |

Row 9 being automated matters more than its position in the list suggests: it is the guarantee this
plugin owes every other JVM language, and it is the one row that cannot regress silently.

---

## 4. Manual smoke test

| Requirement | Status |
| --- | --- |
| Adopted PSI and highlighting without TextMate | ✅ TextMate removed; highlighting confirmed in use |
| One green arrow beside `def main` | ✅ confirmed live; the earlier duplicate was fixed by backend-only registration |
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
4. **Optional profiles.** Groovy (row 7) is closed. Scala row 5's breakpoint and stack halves are
   proven; its navigation and evaluator halves need an IDE with the Scala plugin. Row 6 needs a
   fixture using `inline`, extension methods and `given`, which the current `Greeter.scala` does not.
5. **Flix values in CPS frames.** A `Clo$` frame's state lives in fields `l0`…`l8` plus `pc`, not
   in locals, so the variables view is empty for those frames. Record and tagged-union *values* now
   render (`FlixRecordRenderer`, `FlixTaggedRenderer`), which is the half worth having. Surfacing the
   fields at the top level was considered and declined: `ExtraDebugNodesProvider` would do it, but
   building the nodes needs `NodeManagerImpl` from an `impl` package, and the payoff is one fewer
   expand while the names stay `l0`…`l8`. Recovering real names would need the compiler to emit them.

Nothing here is blocked on a decision. Every item is a measurement someone has to take, or a test
someone has to find a safe fixture for.
