# Phase 8 — verification coverage

What the plan's three verification matrices ask for, and what is actually established today.

Written to be read before claiming the milestone complete. A row marked **not run** is not a row
that will probably pass; it is a row nobody has measured. Where a row is unreachable, the evidence
for that is recorded rather than the conclusion alone.

Status as of 2026-07-28, at `e18ec1b`. 202 automated tests, 0 failures.

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
| 4 | LSP completion, hover, diagnostics and `flix.runMain` work after `languageMapping` | ⚠️ `flix.runMain`'s argument handling is unit-tested (`FlixRunMainActionTest`, 7 cases). Diagnostics were verified live during the fork LSP fix — 5 resolution errors before, 0 after, via a scripted LSP handshake. **Completion and hover have never been exercised in a test or a recorded session.** |
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
| 9 | **Multiple generated classes for one Flix source do not duplicate or miss breakpoints** | ❌ **currently failing** — see below |
| 10 | Pause, continue, terminate and detach have correct lifecycle semantics | ✅ gate row 11 |
| 11 | A launch failure or missing Flix jar produces an actionable error and no orphan process | ⚠️ `FlixRunConfiguration.checkConfiguration` resolves the jar in the dialog so the failure is reported where it can be acted on, and `FlixJar` names the remedy (`FlixJarTest`, 7 cases). **The orphan-process half is untested.** |
| — | *(added by review)* The two `GenericDebuggerRunner` gates are satisfied | ✅ `FlixDebuggerRunnerGatesTest` — both were failing, which is why Debug could never have attached |
| — | *(added by review)* The attach port matches the port on the debuggee's command line | ✅ `FlixLaunchTest` |
| 12 | No DAP process or second JDI/JDWP client starts | ✅ gate row 12, and now structurally enforced: `checkIntegrationGlue` fails the build if a `debugAdapterServer` is registered while the backend is `intellijJvm` |

### Row 9 is the open defect

This row is not a formality. It names exactly the symptom under investigation: breakpoints on
`Main.flix` lines 102–105 do not bind, while 101, 106 and 107 do.

Everything measurable about the artifacts says those lines are equivalent — same 8 classes hold
each of lines 102–107, the executing class `Clo$main$400199` holds all of them in one line table,
its SMAP is `1#1,119:1` (identity for lines 1–119), and offset 208 for line 102 sits in the same
straight-line region as offset 528 for line 106. The bytecode does not distinguish them.

One cause was found and fixed — `FlixLineOnly` and `getAllClasses` now test the *line* rather than
the file, because a class lacking the line reported "no executable code" and
`RequestManagerImpl.setInvalid` makes that stick if it lands before a class that does have it. That
fix did not resolve the symptom, so the remaining cause is elsewhere and unidentified.

**Do not mark this row green from artifact inspection.** It needs the position manager's own log:
`getAllClasses` and `locationsOfLine` counts for lines 102 and 106 in the same session.

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
| Debug opens the native JVM debugger | ⬜ **not run.** A stale `DAPConfiguration` in the sandbox was hijacking the gutter arrow; cleared, and a producer factory bug fixed in `fa7f2df`. Neither fix is confirmed |
| Flix, Java and Kotlin breakpoints coexist | ⚠️ Flix + Java ✅; Kotlin ⬜ |
| Stepping crosses Flix↔Java and Flix↔Kotlin | ⚠️ Flix↔Java ✅ (gate rows 3–5); Flix↔Kotlin ⬜ |
| With the Scala plugin, a run crosses Flix↔Scala 3 | ⬜ |
| Mixed stack frames navigate correctly | ✅ gate row 6 |
| LSP behaviour unchanged | ✅ and improved — the fork's Plain-LSP never loaded workspace jars; fixed and verified, 5 errors → 0 |
| Incomplete Flix code does not hang or hide later declarations | ✅ `FlixErrorRecoveryTest`, `FlixRareSyntaxFuzzTest`, and the corpus gate |

---

## What this leaves

Ordered by what blocks the milestone rather than by matrix position.

1. **Row 9 of the debug matrix** — breakpoints 102–105. The only known *defect*; everything else
   outstanding is unmeasured rather than broken.
2. **The manual smoke test's Run and Debug rows** — the run configuration and DAP retirement have
   never been exercised in a session. With DAP gone there is no fallback, so this is the highest
   risk item.
3. **Required interop rows 2 and 3** — Kotlin. The fixture now exists; row 3 needs an inline
   function added to it.
4. **`verifyPlugin`** — never run, and the cheapest of these to fix.
5. **Optional profiles** — Scala (rows 5, 6) and Groovy (row 7). Fixtures exist; both need an IDE
   with the respective plugin.
6. **Class redefinition and stale-cache SMAP tests** — no coverage, and no known failure either.

Nothing here is blocked on a decision. Every item is a measurement someone has to take.
