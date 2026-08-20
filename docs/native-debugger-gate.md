# Native JVM debugger gate

The Phase-3 proof for [ADR 0002](adr/0002-native-jvm-debugger.md): does IntelliJ's own Java
debugger, plus `FlixPositionManager` and `FlixJavaDebugAware`, actually debug Flix?

Recorded outcome: **Green** — see the results table at the end.

> ### ⚠️ 2026-08-20 — the 2026-07-28 result was invalidated on 2026-08-12, and is green again
>
> Every row below was measured against a compiler in which `flix run` compiled **and ran** the
> program in one JVM. On **2026-08-12**, `flix-fork@a282efce0` ("feat: flix run starts the program a
> build left behind") changed that: `Bootstrap.run` now calls `runForked`, which starts the program
> in a JVM of its own through `ProgramRunner.command` — `java -cp <classpath> Main`, with no JVM
> options and therefore no way to pass an agent.
>
> The plugin went on putting `-agentlib:jdwp` on `flix run`. For eight days every debug session
> attached to the **compiler** while the program ran, unwatched, one process further down. Nothing
> failed: 16,000+ tests were green, `checkIntegrationGlue` passed, the position manager answered
> every question it was asked correctly — and the questions were all about the compiler's own
> classes. Breakpoints simply never bound. Row 15 below is the measurement, and
> `FlixDebuggeeIdentityTest` is the assertion that would have caught it on the day.
>
> The gate is green again as of **2026-08-20** by a different mechanism, not by reverting the fork:
> a debug launch is now two phases — `flix build --Xdebug`, then the plugin starts the program
> itself from the launch spec the build records in `build/development/build.json`
> (`BuildManifest.LaunchSpec`, format 4).
>
> Fixing the attachment exposed a second defect rather than curing everything: a breakpoint bound in
> *five* classes at once, because each default-effect handler frame had been given the body's source
> location. That is row 17, and it is fixed in the compiler's lowering phase.
>
> Rows 1–14 were measured through the old one-process launch and have **not** been re-measured
> through the new one; rows 15 and 17 establish that a `.flix` line binds, and binds in exactly one
> class. Treat rows 1–14 as evidence about the position manager and the platform, which neither
> change touches, and not as evidence about the current launch path.

> **Historical note on how this gate was run.** At the time, the gutter arrow and right-click Debug
> both routed to LSP4IJ's DAP producer, so every row here was measured through a hand-made
> **Remote JVM Debug** configuration with the debuggee started from a terminal. That workaround is
> no longer needed and no longer possible to get wrong: the DAP registration has since been removed
> (Phase 6) and the plugin's own Flix run configuration is the only producer for `.flix`. Pressing
> Debug now *is* the native path.
>
> The manual procedure is kept below because re-running the gate against a future IDE or compiler
> should not depend on the run configuration being correct — it isolates the debugger from the
> launcher. Use it when a row regresses and you need to know which of the two is at fault.

## Preconditions

**1. `scripts/flix-fork` must not inject `--Xdebug`.** Its committed form ends:

```bash
exec java -jar "$JAR" "$@"
```

An injected flag lands *before* the subcommand, where the parser treats it as global and stops
parsing commands — `run` is then demoted to a positional argument and rejected as
`Unrecognized file extension: 'run'`. Check with `git -C <flix-lab> diff scripts/flix-fork`.

**2. `--Xdebug` must reach the compiler.** It is not only a JDWP switch: `Let`, `ApplyDef`,
`ApplyClo`, `IfThenElse` and `Stm` emit line numbers only under it, and it turns the optimizer off
so that no function is folded into its caller. Without it, most statements have no breakpointable
line.

**3. A compiler build with the line-table fixes.** Three are load-bearing, and `javap` shows the
line whichever is missing -- only `locationsOfLine` distinguishes them, so any row below that sets a
breakpoint on an affected line will fail against an older jar for reasons that have nothing to do
with the plugin:

- **One entry per bytecode offset.** A second entry at one offset replaces the first rather than
  adding to it, so the replaced line stops existing for a debugger.
- **The declaration line yields that offset to the first statement.** A method records its own
  declaration before any instruction is written, claiming offset 0 -- where the body's first
  statement also begins. The declaration used to win, so the first statement of *every* function was
  absent from the table.
- **The optimizer is off under `--Xdebug`.** See "Inlining and `--Xdebug`" below.

**4. A fresh plugin build.** The sandbox keeps whatever was installed last; a stale one will not
have the current registrations.

## Running it

### 1. Launch the sandbox IDE with the plugin

```console
cd ~/github.com/wstein/flix-jetbrains-plugin
./gradlew runIdeSplitMode
```

Open the Flix project (`flix-lab`) in the sandbox. Repeat the whole gate with `runIde` for the
non-split case.

### 2. Start the debuggee, suspended, on a fixed port

In a terminal, from the Flix project root:

```console
JAVA_TOOL_OPTIONS='-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005' \
  ./scripts/flix-fork run --Xdebug --yes
```

Wait for `Listening for transport dt_socket at address: 5005`. `suspend=y` is what makes the
pending-breakpoint case testable: nothing has loaded yet when you attach.

### 3. Set breakpoints *before* attaching

In the sandbox IDE, click the gutter — the red breakpoint column, not the green arrow. Place them
in `.flix` and in a `.java` file the program calls.

### 4. Attach

**Run → Edit Configurations → + → Remote JVM Debug**, host `localhost`, port `5005`, mode *Attach
to remote JVM*. Run **that configuration** in Debug.

Attaching by hand keeps the debugger under test separate from the launcher: if a row regresses,
this says whether the fault is in the position manager or in the run configuration. For ordinary
use, press Debug on the file — since Phase 6 that is the same native path.

Row 12 asked whether a DAP process was running. It cannot be, now that nothing registers one:

```console
ps aux | grep -c '[F]lixDebugAdapter'
```

It is kept as a check rather than deleted because "exactly one debugger owns the debuggee" is the
invariant ADR 0002 rests on, and a future re-introduction of a second one is exactly what this
would catch.

It must print `0` while the session is live. Run after the program exits, it prints `0` regardless,
because the adapter is gone either way -- which is exactly how a DAP run can be mistaken for a
native one.

## Choosing fixtures deliberately

Two of the checks below only mean something against the right class, and the compiler decides which
you get. Inspect before relying on one:

```console
javap -v -p 'build/class/Clo$main$NNNNN.class' | grep -E 'SourceFile|SourceDebugExtension'
javap -l -p 'build/class/Clo$main$NNNNN.class' | grep -A30 LineNumberTable
```

- **SMAP class** — has `SourceDebugExtension`. Its `sourceNames("Flix")` are bare (`Main.flix`)
  while `SourceFile` is an absolute path. Exercises stratum selection.
- **No-SMAP class** — no `SourceDebugExtension`. Only the default stratum. Exercises the dual-mode
  fallback.
- **Ambiguity** needs *two* `Main.flix` in different modules whose classes carry **no** SMAP and
  report a **bare** `SourceFile`. If either reports a path, disambiguation resolves it and the
  refusal branch never runs — the check would pass without testing anything.

The survey below records what this project actually produces, so rows 8–10 can be set up rather
than guessed at.

Class shape is **test input, not incidental output**. Rows 10a–10c exist so the fixture's three
properties are recorded alongside the result: a green row 10 means nothing unless the class that
produced it was actually ambiguous.

### Fixture survey of `flix-lab`, 2026-07-28

Taken from `build/class` after a `--Xdebug` build, so these are facts about the fixture rather than
expectations of it.

| | Finding |
| --- | --- |
| Classes sourced from the project's `Main.flix` | **17, all with SMAP**, all reporting the absolute path `/Users/werner/github.com/wstein/flix-lab/src/flix/Main.flix` |
| Classes without SMAP | 295 among `*main*`, but all from *library* files — `Nec.flix`, `Sys/Env.flix` — which live inside the compiler jar and are not navigable project sources |
| `Main.flix` files in the project | **one** |

So **row 8 is ready** — use `Def$main` or any `Clo$main$…` from that file — while **rows 9 and 10
have no fixture as things stand**.

Why the split falls that way: `Smap.build()` emits nothing until a class draws on a second file, and
`Main.flix` calls into the standard library (`Env.getArgs`, `GetOpt.getOpt`, `List.memberOf`), so
inlining pulls foreign code in and SMAP always appears.

> **This survey predates the optimizer change and no longer describes a `--Xdebug` build.** With
> inlining off, no class draws on a second file, so `Smap.build()` emits nothing and *every* class
> reports the `Java` stratum — the reverse of the split above. Rows 8 and 9 have therefore swapped
> difficulty: the no-SMAP path is now the ordinary one and needs no special fixture, while a SMAP
> class cannot be produced under `--Xdebug` at all. Both paths remain implemented and unit-tested;
> what changed is which of them a live session exercises.

### Rows 3–7 — the Flix → Java → Flix path

`flix-lab` now carries plain Java for Flix to call, so the stepping checks have a target:

| | |
| --- | --- |
| Java | `src/javalib/dev/wstein/flixlab/Greeter.java`, compiled with `-g` by the `javalib` source set |
| Packaged by | `./gradlew javalibJar` → `vendor/javalib/flixlab-javalib.jar` |
| On the classpath via | `flix.toml` `[jar-dependencies]` — the Flix compiler does not compile `.java` itself |
| Called from | `Main.flix:48`, `Greeter.greeting()`, inside `helloFromJava()` |
| Entered from | `Main.flix:52`, `println(helloFromJava())`, the first statement of `main` |

Verified end to end: the program prints `Hello Java!` before its usual output.

A scripted run for rows 3–7:

| Step | Expect |
| --- | --- |
| Breakpoint `Main.flix:48`, then **Step Into** | `Greeter.java:22` — row 3 |
| **Step Into** again at line 22 | `Greeter.java:29`, inside `subject()` — row 3 |
| **Step Out**, then **Step Over** at line 23 | stays in Java, reaches line 24 — row 4 |
| Inspect `subject` and `message` at line 24 | real names and values, not `arg0` — row 7 |
| **Step Out** | back to `Main.flix:48`, then `:52` — row 5 |
| Look at the stack while stopped in `subject()` | Flix and Java frames, each navigating correctly — row 6 |

`Greeter` is deliberately three statements and a nested call rather than a one-line `return`. With a
one-liner, Step Into and Step Out land on the same line, Step Over has nothing to step over, and
there is no local to inspect — rows 3–7 would all pass without testing anything.

### Row 9 — the no-SMAP fixture, now in the project

`flix-lab`'s `src/flix/Main.flix` carries `noSmapFixture`, written for this row:

```flix
def noSmapFixture(n: Int32): Int32 =
    let doubled = n * 2;
    let shifted = doubled + 1;
    shifted
```

Compiled result, contrasted with a declaration from the same file that does reach the library:

| Class | `SourceDebugExtension` | `SourceFile` | Line entries |
| --- | --- | --- | --- |
| `Def$noSmapFixture` | **absent** | `…/flix-lab/src/flix/Main.flix` | 4 |
| `Def$main` | present | `…/flix-lab/src/flix/Main.flix` | — |

So a breakpoint inside it resolves through the **default stratum** while one in `main` resolves
through `"Flix"` — the two paths of the dual-mode position manager, in one file, one session.

It had to be written rather than found. Every other declaration in the project reaches the standard
library, so inlining pulls in a second source and SMAP always appears; the no-SMAP classes that do
exist all come from library files inside the compiler jar, which are not navigable project sources.
**Keep it free of library calls** — adding one silently converts it into another row-8 fixture and
this row stops testing anything.

Verify it is still the right shape before relying on it:

```console
javap -v -p 'build/class/Def$noSmapFixture.class' | grep -c SourceDebugExtension   # 0
javap -v -p 'build/class/Def$main.class'          | grep -c SourceDebugExtension   # 1
```

Note that both `let` bindings carry line numbers, which is worth knowing for the `let args` question
below.

### Row 10 — measured as unreachable, and covered elsewhere

The row needs 10a, 10b and 10c to hold at once. 10c is the one that cannot be arranged.

Every `.flix` source recorded by the compiler in `flix-lab`, extracted from the `*F` file tables of
all 4 218 SMAP classes — 22 distinct files, and **zero base-name collisions**:

| Source kind | Compiler input | Recorded as | Examples |
| --- | --- | --- | --- |
| Project | `Input.RealFile` | **absolute path** | `/Users/…/flix-lab/src/flix/Main.flix`, `…/test/TestMain.flix` |
| Library | `Input.FileInPackage` | bare or package-relative | `Nec.flix`, `Fixpoint3/Boxable.flix`, `Sys/Exit.flix` |

So a project source always carries a path that distinguishes it, in both class shapes — the SMAP
`*F` path line, and the bare `SourceFile` of a no-SMAP class such as `Def$noSmapFixture`, which
reads `/Users/…/src/flix/Main.flix` rather than `Main.flix`. Two modules each holding a `Main.flix`
would still be told apart, so the refusal branch never runs and a green row 10 would be measuring
nothing.

Bare names do occur — but only for `Input.FileInPackage` sources, which live inside the compiler jar
or a `.fpkg` and are not navigable project files. A breakpoint cannot be placed in one.

Reproduce the survey with:

```console
perl -0777 -ne 'while (/SMAP\n[^\n]*\n[^\n]*\n\*S Flix\n\*F\n(.*?)\*L/gs) {
  my $f = $1; while ($f =~ /\+ \d+ ([^\n]+)\n([^\n]+)\n/g) { print "$1\t$2\n" } }' \
  build/class/*.class | sort -u
```

**The behaviour the row describes is still tested**, just not in a live session.
`FlixForwardResolutionTest` drives exactly its shape — two modules' `Main.flix`, a class reporting
only the bare name — and asserts that `FlixSourceFiles.choose` resolves to **neither**, plus that a
recorded path does disambiguate. That is the guard row 10 exists to check; a live fixture would only
re-confirm it if the compiler ever starts emitting bare names for project sources.

## When a breakpoint does not bind

The gutter looks the same whichever step failed, so turn on the position manager's own log rather
than guessing. **Help → Diagnostic Tools → Debug Log Settings**, add:

```text
#dev.wstein.flixplugin.debugger
```

Then reproduce and read `idea.log`. Each line names one step:

| Line | Meaning |
| --- | --- |
| `createPrepareRequests(...): watching prepares outside java.*, …` | the breakpoint asked to be told about future classes |
| `prepared Clo$main$…: holds Main.flix:44, resolving` | a prepared class was accepted and handed to the breakpoint |
| `getAllClasses(Main.flix:44): 0 of N loaded classes hold that line` | no loaded class holds that line — expected before the class loads, a problem afterwards |
| `locationsOfLine(Clo$main$…): not compiled from Main.flix -- declares no Flix source at all` | the class was offered but is not Flix — normal and frequent |
| `locationsOfLine(Clo$main$…): not compiled from Main.flix -- declares Main.flix -> unresolved …` | the class **is** Flix but its source did not resolve to a project file — see below |
| `locationsOfLine(..., names=[...], line=44): 0 location(s)` | the class and file matched, but the line has no code — check `javap -l` |
| *(nothing at all)* | the manager was never consulted; the breakpoint is not reaching it |

The last row is the important one: it separates "my mapping is wrong" from "I am not being asked",
and those have entirely different causes.

The two `not compiled from` rows are deliberately distinct, because they are fixed in different
places and used to print identically. The first is about the *class* and is expected. The second is
about *resolution*: the class named a `.flix` source and nothing in the project answered to it.
`-> unresolved` means the file was not found; a path that is printed and still did not match means
two `VirtualFile` objects exist for one path, which identity comparison rejects.

A library source resolving to nothing is correct — `Prelude.flix` lives inside the compiler jar and
is not a project file. The same line naming a source that *is* in the project is a defect, and one
that does not recover on its own: see the next section.

### Why a transient lookup failure used to be permanent

A class-prepare event fires **once per class per VM**. `FlixLineOnly` asks whether the prepared
class holds the breakpoint's line and drops it when the answer is no, and there is no second event
to reconsider. So a source lookup that answered "no" for a moment did not delay a breakpoint, it
disabled that breakpoint for the rest of the session.

`FilenameIndex` answers nothing while the project is reindexing, and the Flix compiler guarantees
that happens: it writes its class output **inside** the project, so every run triggers a VFS refresh
and a re-index. A debug session that raced it lost its breakpoints permanently — observed as
"breakpoints work on the first run and on none after it", and cleared only by restarting the IDE.

`FlixSourceFiles` now resolves an absolute source attribute straight from the VFS and consults the
index only for bare names. An absolute path needs no index and admits no ambiguity, so the race is
removed rather than narrowed. Flix records exactly that for project sources
(`SourceFile: "/Users/…/Hello.flix"`); bare names are library sources, which resolve to nothing
whatever the index says.

### If it binds *too much* — stopping in unrelated Java

Fixed in `2026-07-28`, and recorded because the cause is invisible from this plugin's code and the
symptom looks nothing like its origin.

A breakpoint on `Main.flix:52` stopped in arbitrary JDK and library classes. The chain:

1. Flix class names encode the definition, not the file, so the class-prepare watch is unfiltered
   and sees every class the VM loads.
2. Each one reached `LineBreakpoint.createRequestForPreparedClass`, which asks
   `CompoundPositionManager.locationsOfLine(thatClass, Main.flix:52)`.
3. `FlixPositionManager` threw `NoDataException` — intending "not mine".
4. But `CompoundPositionManager` reads `NoDataException` as **"ask the next manager"**, not as
   "no". The next manager is the platform's `PositionManagerImpl`, which does not override
   `getAcceptedFileTypes()` — so it accepts `.flix` positions — and answers unconditionally with
   `locationsOfLine(type, "Java", null, 52)`.
5. Any class with code at line 52 returned a location, and a real breakpoint request was planted
   there.

Two fixes, both in `FlixPositionManager`:

- `locationsOfLine` returns an **empty list** for a Flix position in a non-Flix class. Nothing else
  can resolve a `.flix` position, so "not compiled from that file" is a final answer and must end
  the chain rather than delegate.
- the class-prepare requestor is wrapped so a foreign class never reaches the breakpoint at all —
  otherwise each one marks it invalid with *"no executable code at line 52 in `<class>`"*. This is the
  platform's own idiom; `PositionManagerImpl` wraps the requestor the same way for anonymous
  classes.

Both are pinned by `FlixPositionManagerDelegationTest`, which asserts the platform facts directly so
an IDE upgrade that changes them fails loudly.

## Results

Run of **2026-07-28**, `flix-lab` with the `Greeter.java` fixture, plugin at `a3012a5`.

| # | Check | Result |
| --- | --- | --- |
| 1 | A `.flix` breakpoint set before its class loads becomes verified and hits | ✅ after the over-binding fix below |
| 2 | A `.java` breakpoint verifies and hits in the same session | ✅ `Greeter.java:22` and `:29` both verified and hit |
| 3 | Step Into moves Flix → project Java | ✅ |
| 4 | Step Over stays in Java | ✅ |
| 5 | Step Out returns to the correct Flix line | ✅ returns to `Main.flix:53`, the line after the call |
| 6 | The stack shows both Flix and Java frames, each navigating to the right file and line | ✅ `applyFrame:53, Clo$main$400067` navigates to `Main.flix:53` |
| 7 | Java locals, watches and expression evaluation work in a Java frame | ✅ in Java frames, and in Flix `Def$` frames. ⚠️ Flix `Clo$` frames show *Variables debug info not available* — see below |
| 8 | A breakpoint in a **SMAP** class verifies and hits | ✅ — every class from the project's `Main.flix` carries SMAP, so row 1 exercised this path |
| 9 | A breakpoint in a **no-SMAP** class verifies and hits | ✅ `Def$noSmapFixture` at `Main.flix:63`, with `n = 20` shown |
| 10a | *Fixture:* two `Main.flix` files exist in different project modules — record both paths | constructible, but see 10c |
| 10b | *Fixture:* neither one's class has a `SourceDebugExtension` — record the `javap -v` line | constructible — `Def$noSmapFixture` proves the shape exists |
| 10c | *Fixture:* both report a bare `SourceFile: Main.flix`, not a path — record it | **impossible for a project source** — measured, see below |
| 10 | Duplicate bare-name `Main.flix` binds to **neither** rather than to the wrong one — or *not reachable*, with evidence | **not reachable live**; the behaviour is covered by unit tests instead — see below |
| 11 | Pause, continue, terminate and detach behave | ✅ |
| 12 | **No DAP process** — `ps aux \| grep -c '[F]lixDebugAdapter'` prints `0` **while suspended**, and the console shows `Connected to the target VM` rather than `[flix-debug-adapter]` | ✅ `0`, and the console shows `Connected to the target VM` |
| 13 | All of the above under `runIdeSplitMode` as well as `runIde` | ✅ identical on both |
| 14 | **Step Over inside Flix advances to the next Flix line** | ✅ confirmed live, 2026-07-28 |
| 15 | **The JVM the agent is on is the JVM the program runs in** | ✅ 2026-08-20 — see below |
| 17 | **One `.flix` line is held by one class, not by every handler frame** | ✅ 2026-08-20 — see below |

### Row 15 — the agent has to be on the program's JVM, and for eight days it was not

The row the gate did not have, added because its absence cost the debugger eight days (see the note
at the top). It is deliberately about the *launch* rather than about the debugger: everything rows
1–14 measure is downstream of a debuggee that contains the program.

Measured with `scripts/FlixLineProbe.java` against a four-statement fixture, same compiler, same
`--Xdebug` build, only the JVM carrying the agent differing:

| Agent on | Classes from `Main.flix` that prepared | Lines 5–8 |
| --- | --- | --- |
| `java -agentlib:jdwp … -jar flix.jar run --Xdebug` (the old launch) | **0** | `absent` |
| `java -agentlib:jdwp … -cp <build/development/class> Main` (the new one) | **3** | `can bind` |

The bytecode was never the problem. `javap -l` on `dev.flix.gen.Def$main` from the same build shows
`SourceFile` pointing at the absolute `Main.flix` path and a `LineNumberTable` of `5, 6, 7, 8` —
exactly the four `let`/`println` statements. `--Xdebug` was doing its job in a process nobody was
attached to.

Two corroborating measurements, both from 2026-08-20:

- **`JAVA_TOOL_OPTIONS` cannot rescue the one-process launch.** A child process inherits it, so both
  JVMs load the agent and the second loses the race for the port:
  `ERROR: transport error 202: bind failed: Address already in use`, then
  `JDWP exit error AGENT_ERROR_TRANSPORT_INIT(197)`. The compiler wins, so the failure mode is a
  program that silently never starts.
- **The forked child, captured live from a real IDE session** on `flix-proc-invaders`:
  `…/bin/java -cp …/build/development/class:…/lib/external/processing-core.jar Main` — no agent.

### Row 16 — test debugging, measured

`flix test` does **not** fork. `Bootstrap.testWith` reflects the compiled functions in this process,
so the agent on the `flix test` JVM is on the JVM the tests run in.

Measured 2026-08-20 on the same fixture: `java -agentlib:jdwp … -jar flix.jar test --Xdebug`
prepared **2** classes from `TestMain.flix`, and lines 6, 7 and 8 report `can bind`; the test itself
ran and passed in the same session.

What that establishes is the *binding*, from a terminal launch. It is not evidence that pressing
Debug on a test in the IDE works, because the plugin has no test debug configuration — the test task
is a plain `flix test --events-json` run. See `docs/phase-8-verification.md`.

### Row 17 — a breakpoint on one line must stop once, not once per handled effect

Row 15 made Debug attach to the right JVM, and that exposed the next defect rather than fixing it.
Breakpoints bound and hit — and Continue stopped on the *same* source line four more times before
moving on, each time in a different class, with the stack deeper and no variables to show:

```
staticApply:18, Def$main                  2 hidden frames
applyFrame:18, Clo$main$hxrGySH8ThV      10 hidden frames
applyFrame:18, Clo$main$TbJN4sJf8mG      13 hidden frames
applyFrame:18, Clo$main$DEBaibTRSAG      16 hidden frames
```

`main`'s effects each have a default handler, and `Lowering.wrapInHandler` wraps the body once per
effect. Lambda lifting gives each wrapper a class, and each was given the body's location — so all
of them reported `Main.flix:18`, the body's first line, for a frame that runs none of it. Five
classes held that line; four held no statement of it.

Two candidate fixes were **disproved by measurement** before the third was adopted, and both are
recorded because each looked obviously right:

| Attempted | Measurement that killed it |
| --- | --- |
| `LineNumbers.finish()` writes an undisplaced declaration entry | with `finish()` writing nothing at all, the phantom entries survived unchanged — they were never headers |
| the closure `applyFrame` should carry no header | the entry moved from offset 0 to 13 instead of disappearing, and the real body class lost its first 100 offsets |

The cause was one level up: the wrapper's *expression* carried the body's location, so
`GenExpression` emitted that line legitimately. The fix is in the lowering phase — the wrapper is
located nowhere, while the innermost lambda, which does hold the body, keeps the body's location.
That distinction is load-bearing: a lifted class takes its primary source from its own location, and
an unknown one there sent the whole body to foreign line numbers.

Marking the location synthetic is **not** sufficient, and that too was measured. `asSynthetic` keeps
the file and the line, and suppressing every synthetic location in the back end removed 308 lines
across 66 files — including 19 consecutive lines of one project file, which turned out to be string
interpolation: desugared, synthetic, and written by the programmer.

Verified on `flix-proc-invaders` by comparing every generated class's line table before and after:

| | before | after |
| --- | --- | --- |
| classes reporting `Main.flix:18` | 5 | **1** |
| project source lines made unreachable | — | **0** |
| library source lines made unreachable | — | **0** |

Pinned by `TestLineNumberTable` in the compiler: *a handled effect does not multiply the first
statement across handler frames*, alongside two tests that the body keeps every statement and that
no line of the program becomes unreachable. Fault-injected: restoring the wrapper's location fails
the first and leaves the other two passing, which is exactly the shape of the original defect.

### Some lines could not take a breakpoint — a compiler defect, now fixed

Breakpoints on `Main.flix` lines 102–105 never bound while 101, 106 and 107 did, with nothing in the
source distinguishing them. Four rounds of plugin-side investigation found nothing, because there
was nothing to find: every artifact said the lines were equivalent.

They were not. The `LineNumberTable` held two entries at one bytecode offset:

```text
line 102: 208     <- println(helloFromKotlin())
line  59: 208     <- KotlinGreeter.greeting(), inlined into it
line 103: 288
line  66: 288
```

An entry is keyed by its offset, so the second does not add a mapping — it **replaces** the first.
The replaced line then does not exist for a debugger: absent from `allLineLocations`,
`locationsOfLine` empty, no breakpoint can bind. `javap` shows both entries, which is why reading
the class file suggested the lines were fine.

Cross-file inlining escapes this because `Smap` gives the foreign line a synthetic number; same-file
inlining passes the line through unchanged and the two collide. Line 101 escaped by accident — its
entry sits at offset 0, the prologue, while the inlined body landed at 128.

Worth refuting a plausible reading of this, since it argues for the architecture we retired: that a
debug adapter could have remapped the offset back to the right line using compiler context the IDE
lacks. It could not. The line is gone from the `LineNumberTable` itself, which is the only record
either a DAP adapter or a position manager reads — an adapter would have had to carry a second,
compiler-produced source map that Flix does not emit. The defect was in what the compiler wrote, and
it had to be fixed there whichever debugger consumed it.

Fixed in `flix-fork` (`LineNumbers.emit` now keeps one entry per offset, the call site winning).
Proven with a JDI probe asking the live VM directly:

| Line | Addressable before | after |
| --- | --- | --- |
| 101 | 1 | 1 |
| 102–105 | **0** | **1** |
| 106, 107 | 1 | 1 |

**The lesson for future rows:** `javap` showing a line in the table does not mean a debugger can
bind to it. Ask the VM — `locationsOfLine` is the only authority.

That probe is now committed as [`scripts/FlixLineProbe.java`](../scripts/FlixLineProbe.java), so
the question is one command rather than an afternoon:

```console
JAVA_TOOL_OPTIONS='-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5099' \
  ./scripts/flix-fork run --Xdebug --yes &
java scripts/FlixLineProbe.java 5099 Main.flix 101 107
```

It reports, per line, whether the line is in the table and whether it is addressable — the two
facts that disagreed here. `UNADDRESSABLE` means present but invisible to the debugger, which is a
compiler problem; `absent` usually means `--Xdebug` did not reach the compiler. It exits non-zero
on the former, so it can gate a script. Single-file source execution: nothing to build.

### The first statement of every function — the same defect, one layer up

The offset rule above fixed collisions *between two body expressions*. It left a third case, which
was not noticed because it looks like a deliberate omission rather than a bug: a method opens by
recording its own **declaration** line, before a single instruction has been written, so that entry
claims bytecode offset 0 — and the body's first statement begins at offset 0 too. The declaration
won, and the first statement of every function was absent from the table.

Probed on a two-function fixture, the shape is unmistakable:

| Line | Source | Before | After |
| --- | --- | --- | --- |
| 1 | `def main()` | can bind | absent |
| 2 | `println("alpha")` — first statement | **absent** | **can bind** |
| 3–5 | rest of `main` | can bind | can bind |
| 7 | `def helper()` | can bind | absent |
| 8 | `println("helper-first")` — first statement | **absent** | **can bind** |

Declaration entries are now provisional: written only once it is known the body did not claim their
offset. Where the body does move first — a method that loads parameters before its first statement —
the offsets differ, both entries are real, and both bind.

**Breakpoints on a bare `def` line no longer verify.** That is the trade and it is the right way
round: the declaration describes no instruction the first statement does not already own, and only
one of the two can exist.

### Inlining and `--Xdebug`

A function whose body is folded into its caller gets no class of its own, so a breakpoint on it has
nothing to bind to. Its line does not survive at the call site either: the inlined body begins at
the call site's own bytecode offset, and by the rule above the call site wins. The line then exists
nowhere in the program.

That is the fate of every single-expression helper, which is most of them — measured on a fixture
where `@DontInline` was the only thing separating a bindable definition from an unbindable one:

| Definition | Verdict |
| --- | --- |
| `pub def maxDemo(): Int32 \ IO = Math.max(10, 20)` | **absent** — `Def$maxDemo` was never generated |
| `pub def plainDemo(): Int32 = 42` | **absent** — same |
| the same function with `@DontInline` | can bind |

`--Xdebug` therefore turns the optimizer off. This is the trade every other toolchain makes — a
debug build is not an optimized one — and `--Xdebug` is set only when launching a debug session, so
nothing anyone runs or ships is affected.

Two consequences are deliberate and worth stating rather than discovering:

- **Debug sessions run unoptimized.** The throughput cost is real and is *not yet measured*; a
  Datalog solve is the case to watch, for the same reason it is the case to watch under stepping.
- **Cross-file line remapping is unreachable in debug builds.** SMAP exists to give inlined foreign
  code a synthetic line of its own. With no inlining there is no foreign code in a class, so no SMAP
  is emitted and classes report the `Java` stratum. The position manager already handles no-SMAP
  classes (row 9), so this changes nothing a user sees.

Reverse mapping — JDI location → `.flix` file and line — is therefore **confirmed working**: rows
5 and 6 exercise exactly that path, and the stack navigates correctly. What rows 1 and 14 have in
common is the *forward* direction and stepping policy, neither of which row 5/6 touches.

### Row 8 — SMAP is emitted, and the fork needs no change

**Correction, 2026-07-28.** An earlier revision of this section reported "16 800 classes and 0 carry
a `SourceDebugExtension`" and concluded row 8 was unreachable. That measurement was wrong and the
conclusion with it. BSD `grep` silently suppresses matches in binary files without `-a`, and
`strings` misparses these class files as Mach-O and aborts — both returned zero for a string that is
plainly present. The correct count:

| | |
| --- | --- |
| Top-level classes | 16 800 |
| Carrying `SourceDebugExtension` | **4 218** (25%) |

Recorded because the failure mode is silent: a scan that returns zero looks exactly like a scan that
found nothing to report. Use `grep -a` on class files, and verify any zero against a single file
known to match before believing it.

A real SMAP body from `Clo$main$399829`, showing the cross-file inlining the dual-mode design was
built for:

```text
SMAP
Clo$main$399829.flix
Flix
*S Flix
*F
+ 1 Main.flix
/Users/werner/github.com/wstein/flix-lab/src/flix/Main.flix
+ 2 Nec.flix
Nec.flix
+ 3 List.flix
List.flix
*L
1#1,55:1
605#2,1:56
1340#3,1:57
*E
```

So the fork is behaving correctly and needs no change:

- `Smap.build()` emits whenever a class draws on a second file, and here it does — `Main.flix`
  primary, with `Nec.flix:605` and `List.flix:1340` mapped to synthetic lines 56 and 57;
- inlined code keeps the callee's own file and line rather than being reattributed to the call site,
  which is what makes stepping into inlined library code land somewhere truthful;
- the `"Flix"` stratum is live, not dead code, so **both** paths of the position manager are
  exercised in a normal build.

What follows for the gate: **row 8 is green**, because every class compiled from the project's
`Main.flix` carries SMAP and row 1's breakpoint therefore bound through the `"Flix"` stratum. **Row
9 is the one still missing a fixture** — the no-SMAP classes in this project all come from library
files inside the compiler jar, which are not navigable project sources.

### Row 14 — Step Over inside Flix does not stop on a Flix boundary

**Yellow.** Not a mapping defect, and deliberately not fixed here.

Observed: stopped at `Main.flix:53` (`let args = Env.getArgs();`) in frame
`applyFrame:53, Clo$main$400067`, F8 lands in `invoke():-1` of the *same* class, and the editor opens
the decompiled `Clo$main$400067.class` instead of any Flix source.

The cause is measurable in the class files. Over the 325 `Clo$main$*` classes `flix-lab` compiles
under `--Xdebug` (`javap -p -l`, 2026-07-28):

| Method | Line-number table |
| --- | --- |
| `invoke()` | **0 of 325** carry one at all |
| `applyFrame(Value$)` | **260 of 325** carry exactly one entry, at bytecode offset 0 |

Both follow from the CPS transformation: each continuation frame covers a single source line, and
`invoke()` is a synthetic bridge that only calls `applyFrame`.

That makes the JDI contract for Step Over — *run until the line number changes **within this frame**,
or the frame pops* — unsatisfiable in 80% of Flix frames. The line can never change, so the step
always terminates on frame pop, in a caller with no line table. `FlixPositionManager` then correctly
declines it (`lineNumberOf` rejects `-1`), the platform finds no source, and falls back to the
decompiler. Every component behaves as specified; the specification is a Java-shaped one.

So Flix needs a stepping *policy*, not another mapping rule. The position manager resolves
source ↔ bytecode and nothing else; it has no say in where a step stops. Two extension points in
`IU-2026.1.3` can supply one:

| EP | Interface | Fit |
| --- | --- | --- |
| `com.intellij.debugger.extraSteppingFilter` | `ExtraSteppingFilter` | says "do not stop here, keep stepping". Smallest change; would carry the step past `invoke()` and the trampoline to the next frame with a real Flix line. |
| `com.intellij.debugger.jvmSteppingCommandProvider` | — | replaces the step command outright. More control, more surface. |

Neither is proven. The filter's risk is the `dev.flix.runtime` trampoline between continuations:
whether stepping through it terminates promptly, or costs a JDWP round trip per bytecode, has to be
measured rather than assumed.

#### Implemented — `FlixSteppingFilter`

Once row 1 went green, Phase 4 was unblocked and this was built as the plan specifies ("add
Flix-specific stepping filters only where tests prove they are necessary" — this row is that proof).

`com.intellij.debugger.extraSteppingFilter` was chosen over `jvmSteppingCommandProvider`: it is the
smaller surface, and `RequestHint.processSteppingFilters` consults it at exactly the decision this
needs — *stop here, or step again, and how far*.

The policy is one rule: **resume stepping, with `STEP_INTO`, whenever the step lands in Flix
machinery that has no Flix line; stop as soon as it reaches one.**

`STEP_INTO` rather than `STEP_OUT` is deliberate. The trampoline driving one continuation into the
next is a loop within a single frame, so stepping out of it would leave the loop entirely and skip
every continuation still to run. Stepping in walks forward and descends into the next `applyFrame`.

Isolation holds by construction: the rule requires the frame to be Flix's — compiled from a `.flix`
file, or in `dev.flix.runtime.` — so no `.java`, `.kt` or `.scala` stop is ever suppressed. Notably
that includes a Java class compiled without `-g`, which has no line numbers either; "no line
information" alone is deliberately *not* the trigger, and `FlixSteppingPolicyTest` pins that case.
The DAP adapter's old broad `com.*`/`org.*`/`net.*` exclusions were not used and must not be — they
would take user Java, Kotlin and Scala out of stepping, the very frames this path exists to reach.

#### What it does not fix: Step Over still behaves as Step Into

Confirmed live. Stepping lands on Flix lines rather than in bytecode, but F8 descends into called
functions exactly as F7 does. This is not a shortcoming of the filter — **no filter can restore that
distinction**, and the reason is worth recording because it constrains every future attempt.

`Thunk$.run()` is a trampoline **loop inside a single frame**:

```text
 1: dup                    <- loop head
 2: instanceof Thunk$
11: invokeinterface invoke()
16: goto 1
```

Every continuation is invoked from that one loop, so two successive Flix lines are *siblings at the
same JVM depth* — whether they are consecutive statements in one function or a call into another.
JDI defines Step Over and Step Into purely on frame nesting, and CPS has erased the nesting that
carried the difference. Asking for a shallower step does not mean "stay in this Flix function"; it
means "leave the trampoline", abandoning every continuation still to run.

The obvious repair — recover the Flix function from the generated class name — also fails. The name
encodes the **entry point**, not the definition:

| Class | Compiled from |
| --- | --- |
| `Clo$main$399829` | `…/src/flix/Main.flix` |
| `Clo$main$399824` | `Nec.flix` |
| `Clo$main$399833` | `Sys/Env.flix` |

Library code reached from `main` is named `Clo$main$…` just like `main`'s own code, so the name
cannot separate "this function" from "something it called".

#### Resolved: Step Over is confined to the Flix definition it started in

**No compiler change was needed.** The earlier note here claimed a definition identity would have to
come from the bytecode or a side table. That was wrong, and worth correcting explicitly: the
identity does not need to survive compilation, because the IDE re-derives it. A resolved source
position is a file and a line, and the Flix PSI already says which `def` encloses that line.

Three pieces, all on public extension points:

| Piece | Role |
| --- | --- |
| `FlixSteppingCommands` (`debugger.jvmSteppingCommandProvider`) | `getStepOverCommand` is the one point that knows both the chosen action and the position it was chosen from. On Step Over it records the enclosing definition, the caller's definition and the stack depth; every other action clears it. |
| `FlixDefinitionScope` | Answers which `def` a position sits in, from the PSI. Identity is file + declaration start offset, not name — two `def`s in different `mod`s can share a name. |
| `FlixSteppingFilter` | Resumes the step whenever it surfaces in a *different* definition, except the recorded caller once the stack is shallower than where the step began. Step Into records no scope, so it still stops at the first Flix line, unchanged. |

One limit, inherent to a source-level notion rather than a defect:

- **Self-recursion is not distinguished.** A recursive call re-enters the same definition, so a step
  over one stops inside it. Separating those needs a per-activation identity, which is a runtime
  notion. *Mutual* recursion is distinguished, because returning to the caller is only accepted when
  the stack is shallower than where the step began — a call back into the caller's definition is
  deeper, and is stepped over.
Inlining used to be a second limit here — inlined code kept the callee's own `SourceLocation`, so a
step saw it as a different definition. That no longer arises: `--Xdebug` disables the optimizer
outright, so no body is folded into another and every definition a step can reach is one the
programmer wrote. See "Inlining and `--Xdebug`" below.

**Known cost, not yet measured.** A stretch of runtime work with no intervening Flix line is
single-stepped rather than run. Between adjacent source lines that is a few frames. A long
computation staying inside the runtime — a Datalog solve is the case to watch — would be stepped
instruction by instruction. It is bounded rather than unbounded, because `DebugProcessImpl.doStep`
applies the configured stepping filters as class exclusions on the step request, so `java.*` and
friends are not entered. Measure before assuming a Datalog fixture is usable under a step.

### Resolved: `let args` was the first-statement defect

A breakpoint on `let args = …` did not verify under the DAP path even though `javap -l` showed the
line present at bytecode offset 0. It was the only line at offset 0 — the entry of the CPS
continuation frame `applyFrame` — and `locationsOfLine("Flix", "Main.flix", 43)` returned nothing
for it while every other line resolved.

Two facts recorded on 2026-07-28 ruled out the obvious explanations, and both still hold: offset 0
is the ordinary shape of a CPS continuation rather than a marker of a misplaced line (260 of 325
`Clo$main$*` classes carry exactly one entry there), and reverse resolution of the same construct
worked natively. What neither could explain was why the forward direction found nothing.

**It was the declaration line taking offset 0**, which is the defect described under "The first
statement of every function" above. `let args = …` was the first statement of its function, so its
entry was the one the declaration displaced — and a line absent from the table resolves to nothing
in any stratum, which is exactly what was observed.

Confirmed by measurement rather than inference, on a fixture placing a `let` first on both codegen
paths:

| Line | Position | Verdict |
| --- | --- | --- |
| `let args = Ask.ask();` | first statement of a control-impure def (`applyFrame`) | can bind |
| `let args = List.length(…);` | first statement of a control-pure def (`staticApply`) | can bind |

The stratum-fallback change contemplated here was therefore never needed, and should not be made:
the `"Flix"` preference is what makes an inlined frame resolve to the file it came from rather than
to the wrong line of the enclosing one, and nothing has been found that requires trading it away.

One observation from the original note survives and is worth keeping. At offset 0 the binding has
not executed — `Env.getArgs()` runs after — so stopping there shows `args` unbound. "Stop at
`let args`" and "stop before the call producing it" are the same position in CPS, which makes this
partly a question about what the breakpoint should *mean* rather than only about where it binds.

## Verdict

### Recorded verdict, 2026-07-28: **Green**

The architectural question the gate exists to answer is settled affirmatively. IntelliJ's Java
debugger is the sole JDWP owner (row 12), it debugs Flix and Java in one session, Flix frames appear
in the stack and navigate to `.flix` source, Java breakpoints and evaluation are unaffected, and
split mode behaves identically to `runIde`. Nothing here points at Red — no extension point turned
out to be unusable, and no position proved unmappable.

Rows 1–9 and 11–14 are confirmed live: breakpoints in both languages, mixed stepping in both
directions, stack navigation, Java evaluation, lifecycle, split mode, Flix-aware Step Over, and both
strata of the position manager — `Def$noSmapFixture` resolves through the default stratum while
`Def$main` resolves through `"Flix"`, in one session. Row 10 is not reachable, with evidence.

What remains is a follow-up feature rather than a gap in the proof:

| Item | Row | State |
| --- | --- | --- |
| Duplicate bare base names | 10 | not reachable, with evidence |
| Variables in a **CPS** frame | 7 | see *Why some Flix frames show no variables* below — a follow-up feature, not a gate failure |

### Why some Flix frames show no variables

Row 9 sharpened this. It is not "Flix frames have no variables"; it depends on how the declaration
was compiled:

| Frame | `LocalVariableTable` | Lines in the method | What the user sees |
| --- | --- | --- | --- |
| `Def$noSmapFixture.staticApply` | **full** — `n`, `doubled`, `shifted` | 62, 63, 64, 65 — sequential | variables populate normally |
| `Clo$main$400241.applyFrame` | **empty** | 16 entries, non-monotonic | *Variables debug info not available* |

A direct, effect-free call compiles to an ordinary method and debugs like ordinary Java. A CPS
continuation keeps its state in **fields** — `l0`…`l8` plus `pc` — because the frame must survive
being suspended and resumed, and fields are not locals, so nothing appears in the variables view.

That makes Flix value presentation a *feature to build*, not a defect to fix: the values are present
and reachable, under names the debugger cannot interpret unaided. The plan already places custom
Flix value renderers after this milestone, and this is the evidence for what they must do.

One further observation from the same class, worth keeping: `Clo$main$400241.applyFrame` reports
lines `69, 48, 70, 71, …` — line 48 is `helloFromJava`'s body, inlined between two lines of `main`.
Inlined code keeps its own line and is interleaved rather than reattributed, which is exactly the
behaviour the position manager and `FlixDefinitionScope` rely on.

### Criteria

**Green** — all of 1–14. Proceed to the native run/debug configuration, which is what lets the
gutter's Debug action replace the DAP path.

**Yellow** — mixed breakpoints work but class-prepare timing, source lookup, stepping policy or
split-mode placement needs bounded fixes. Time-box and re-run.

**Red** — the required Java-debugger extension points are unusable for this IDE, or Flix positions
cannot be mapped reliably. Record the evidence and fall back to extending the single DAP/JDI
adapter for mixed Java support. Never ship two simultaneous JDWP owners.
