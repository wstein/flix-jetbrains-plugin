# Phase 8 — verification coverage

What the plan's three verification matrices ask for, and what is actually established today.

Written to be read before claiming the milestone complete. A row marked **not run** is not a row
that will probably pass; it is a row nobody has measured. Where a row is unreachable, the evidence
for that is recorded rather than the conclusion alone.

Status as of 2026-07-28, at `4160531`. 203 automated tests, 0 failures.

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
| 3 | Exactly one language, file type, parser, highlighter, gutter marker, LSP session and run configuration | ✅ `FlixPluginDescriptorTest` (8 checks) plus `checkIntegrationGlue`, which now fails the build on a class registered in two modules |
| 4 | LSP completion, hover, diagnostics and `flix.runMain` work after `languageMapping` | ⚠️ `flix.runMain`'s argument handling is unit-tested (`FlixRunMainActionTest`, 7 cases). Diagnostics verified live during the fork LSP fix — 5 resolution errors before, 0 after, through a scripted LSP handshake — and confirmed in the IDE. **Completion and hover have never been exercised in a test or a recorded session.** |
| 5 | `checkIntegrationGlue`, build and plugin verification pass | ⚠️ `checkIntegrationGlue` and `build` pass and run in `check`. **`verifyPlugin` (the JetBrains Plugin Verifier) has never been run** — the task exists but is not wired into any pipeline, and there is no CI. |

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
| 7 | Java locals, watches/evaluation, **and exception breakpoints** work in Java frames | ⚠️ locals and evaluation ✅ (gate row 7). **Exception breakpoints have not been tested at all.** Flix `Clo$` frames show no variables — not a defect, see the gate's *Why some Flix frames show no variables* |
| 8 | Attached project/library/JDK Java sources resolve through the platform debugger | ⬜ not run. Stepping into `Greeter.java` works, but JDK and library source attachment was never exercised |
| 9 | **Multiple generated classes for one Flix source do not duplicate or miss breakpoints** | ✅ — the cause was a compiler defect, not a plugin one; see below |
| 10 | Pause, continue, terminate and detach have correct lifecycle semantics | ✅ gate row 11 |
| 11 | A launch failure or missing Flix jar produces an actionable error and no orphan process | ⚠️ `FlixRunConfiguration.checkConfiguration` resolves the jar in the dialog so the failure is reported where it can be acted on, and `FlixJar` names the remedy (`FlixJarTest`, 7 cases). **The orphan-process half is untested.** |
| — | *(added by review)* The two `GenericDebuggerRunner` gates are satisfied | ✅ `FlixDebuggerRunnerGatesTest` — both were failing, which is why Debug could never have attached |
| — | *(added by review)* The attach port matches the port on the debuggee's command line | ✅ `FlixLaunchTest` |
| 12 | No DAP process or second JDI/JDWP client starts | ✅ gate row 12, and now structurally enforced: `checkIntegrationGlue` fails the build if a `debugAdapterServer` is registered while the backend is `intellijJvm` |

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
| Class prepare | ⚠️ the requestor's filter rule is exercised indirectly; there is no test that drives a prepare event |
| Stepping-filter wiring | ✅ `FlixSteppingFilterTest` — added after review; mutation-checked (inverting the arrived-check, dropping the budget, or stepping out instead of in all fail it) |
| **Class redefinition** | ⬜ not covered |
| **Stale cache invalidation** | ⬜ not covered |

---

## 3. JVM-language interoperability matrix

`flix-lab` now carries a `Greeter` in **five** languages — Java, Kotlin, Scala, Groovy and JRuby —
each in its own jar, all called from `main`. That is the fixture this matrix needs, and it makes
most of these rows runnable for the first time. JRuby is beyond what the plan asks for.

| # | Requirement | Status |
| --- | --- | --- |
| 1 | **Required:** Flix → Java → Flix-return, with breakpoints, stepping, stack, locals, evaluation, exceptions, source lookup | ✅ for breakpoints, stepping, stack, locals and evaluation (gate rows 3–7, `Greeter.java`). ⬜ exceptions and library/JDK source lookup |
| 2 | **Required:** Flix → Kotlin/JVM → Java → Flix-return, `.kt` and `.java` breakpoints, frame-specific evaluators | ⬜ **now testable** — `vendor/kotlinlib`, called from `Main.flix:102` |
| 3 | **Required:** Kotlin inline-function and lambda frames do not steal or mis-map Flix positions | ⬜ needs a fixture with an inline function; the current Kotlin `Greeter` has none |
| 4 | **Conditional:** coroutine parity, if claimed | ✖ **not claimed.** The plan forbids claiming it without proving the Kotlin debugger's coroutine agent is injected into an externally launched JVM. It is not, and no such claim is made |
| 5 | **Optional Scala profile:** Flix → Scala 3 → Java → Flix-return | ⬜ **now testable** — `vendor/scalalib`, called from `Main.flix:103`. Requires the Scala plugin installed |
| 6 | **Optional Scala profile:** inline, extension and given-generated frames | ⬜ needs a fixture using those constructs |
| 7 | **Optional Groovy smoke:** a Groovy breakpoint and frame work; Flix declines its source | ⬜ **now testable** — `vendor/groovylib`, called from `Main.flix:104` |
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
| Debug opens the native JVM debugger | ⬜ **not run**, and three defects were fixed on the way to it, none confirmed live: a stale `DAPConfiguration` hijacking the gutter arrow (cleared), a producer building an unregistered configuration type (`fa7f2df`), and — the substantive one — both `GenericDebuggerRunner` conditions unsatisfied, so no debug session could ever have started (`e18ec1b`) |
| Flix, Java and Kotlin breakpoints coexist | ⚠️ Flix + Java ✅; Kotlin ⬜ |
| Stepping crosses Flix↔Java and Flix↔Kotlin | ⚠️ Flix↔Java ✅ (gate rows 3–5); Flix↔Kotlin ⬜ |
| With the Scala plugin, a run crosses Flix↔Scala 3 | ⬜ |
| Mixed stack frames navigate correctly | ✅ gate row 6 |
| LSP behaviour unchanged | ✅ and improved — the fork's Plain-LSP never loaded workspace jars; fixed and verified, 5 errors → 0 |
| Incomplete Flix code does not hang or hide later declarations | ✅ `FlixErrorRecoveryTest`, `FlixRareSyntaxFuzzTest`, and the corpus gate |

---

## What this leaves

Ordered by what blocks the milestone rather than by matrix position.

1. **The manual smoke test's Run and Debug rows.** The run configuration has never been exercised in
   a session, and three defects were found in it by review rather than by use — including two that
   made a debug session impossible. With DAP retired there is no fallback, so this is the highest
   risk item and the one that unblocks the most.
2. **Required interop rows 2 and 3** — Kotlin. The fixture exists; row 3 additionally needs an
   inline function, which the current Kotlin `Greeter` does not have.
3. **`verifyPlugin`** — never run, and the cheapest of these.
4. **Optional profiles** — Scala (rows 5, 6) and Groovy (row 7). Fixtures exist; each needs an IDE
   with the respective plugin installed.
5. **Class redefinition and stale-cache SMAP tests** — no coverage, and no known failure either.
6. **`FlixPositionManager.getAllClasses` and the class-prepare filter** have no direct test. Both
   need a real `Project` for `SourcePosition`, and this module already mixes `ParsingTestCase` with a
   mock application in a way that has caused cross-test interference. The rules they compose are
   covered; the composition is not.

Nothing here is blocked on a decision. Every item is a measurement someone has to take, or a test
someone has to find a safe fixture for.
