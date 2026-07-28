# Native JVM debugger gate

The Phase-3 proof for [ADR 0002](adr/0002-native-jvm-debugger.md): does IntelliJ's own Java
debugger, plus `FlixPositionManager` and `FlixJavaDebugAware`, actually debug Flix?

Everything below runs against the **stock Remote JVM Debug** configuration. The gutter arrow and
right-click Debug both route through LSP4IJ's DAP producer, so using either would test the path
this gate exists to replace.

Record the outcome in the results table at the end. A gate nobody wrote down is a gate that gets
re-run.

## First: confirm which debugger you are actually running

The two paths look identical in the editor — same red gutter, same Debug toolwindow — and differ
only in the console. Getting this wrong invalidates every row, so check it before anything else.

**You are on the DAP path** if the console shows any of:

```text
java --add-modules jdk.jdi …/FlixDebugAdapterNNNN.java --port 50351
Launching 'Main.flix' with 'Flix (--Xdebug attach)' at 50351
Listening for DAP client on port 50351
[flix-debug-adapter] launching: …
```

`FlixDebugAdapter` is the bridge process, `Flix (--Xdebug attach)` is LSP4IJ's run configuration,
and `[flix-debug-adapter]` is its logging. If you see these, `FlixPositionManager` is not involved
and the gate is measuring the old path.

**You are on the native path** if the console shows only:

```text
Connected to the target VM, address: 'localhost:5005', transport: 'socket'
```

and the debuggee's own output appears in the terminal you launched it from, not in the IDE.

### How to end up on the wrong one

Pressing **Debug** on a `.flix` file, or clicking the gutter arrow, hands the request to whichever
run-configuration producer matches — today that is LSP4IJ's DAP producer, because the `*.flix`
mapping in the backend module is still registered. It will auto-create a `Flix (--Xdebug attach)`
configuration and use it.

The native path has no producer yet, so it cannot be reached that way. It is reachable **only** by
creating a Remote JVM Debug configuration by hand and launching the debuggee yourself, as below.
That is the whole reason step 2 starts the program in a terminal rather than from the IDE.

## Preconditions

**1. `scripts/flix-fork` must not inject `--Xdebug`.** Its committed form ends:

```bash
exec java -jar "$JAR" "$@"
```

An injected flag lands *before* the subcommand, where the parser treats it as global and stops
parsing commands — `run` is then demoted to a positional argument and rejected as
`Unrecognized file extension: 'run'`. Check with `git -C <flix-lab> diff scripts/flix-fork`.

**2. `--Xdebug` must reach the compiler.** It is not only a JDWP switch: `Let`, `ApplyDef`,
`ApplyClo`, `IfThenElse` and `Stm` emit line numbers only under it, and it stops the inliner
discarding programmer-written bindings. Without it, most statements have no breakpointable line.

**3. A fresh plugin build.** The sandbox keeps whatever was installed last; a stale one will not
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

Do not press Debug on `Main.flix`, and do not use the gutter arrow: both hand the request to
LSP4IJ's DAP producer and silently run the path this gate is meant to replace. Re-check the console
signatures above before continuing.

While suspended at the first breakpoint, confirm row 12 **now**, not afterwards:

```console
ps aux | grep -c '[F]lixDebugAdapter'
```

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

### Row 9 — a no-SMAP fixture, recipe verified

A **self-contained** file, one that calls nothing from another file, produces no SMAP. Verified by
compiling this and inspecting the result:

```flix
def twice(x: Int32): Int32 = x + x

def compute(): Int32 =
    let a = twice(21);
    let b = a + 1;
    b

def main(): Unit \ IO =
    let r = compute();
    println(r)
```

```text
Def$compute   smap=no   SourceFile="/private/tmp/smaptest/Selfcontained.flix"   lines 3 4 5 6
Def$main      smap=no   SourceFile="/private/tmp/smaptest/Selfcontained.flix"   lines 8 9 10
```

Add such a file to the project and breakpoint inside it. Note lines 4 and 5 — both `let` bindings —
carry line numbers, which is worth knowing for the `let args` question below.

### Row 10 — determine reachability before trying to test it

The precondition is a **bare** `SourceFile`, and a project file appears never to produce one.
`Source.name` resolves per input kind:

- `Input.RealFile(path, _) => path.toString` — project files, which `flix run` supplies as absolute
  paths. Observed absolute in every case above, with and without SMAP.
- `Input.FileInPackage(_, virtualPath, _, _) => virtualPath` — files inside a package, which *are*
  bare (`Nec.flix`) or short relative (`Sys/Env.flix`).

Only package sources report bare names, and the compiler carries one copy of each. So the
duplicate-bare-name collision the refusal guards against does not appear to be reachable from
ordinary project sources at all.

If that holds, record row 10 as **not reachable**, with the evidence, rather than leaving it blank or
marking it green off a fixture that never exercised it. The refusal in
`FlixSourceFiles.choose` stays either way: it costs nothing, and "cannot currently happen" is a
weaker guarantee than "cannot happen".

To disprove it, the shape needed is two `.fpkg` dependencies each carrying a `Main.flix`, both
unpacked as navigable sources. If you build that, record it — it turns a defensive branch into a
tested one.

## When a breakpoint does not bind

The gutter looks the same whichever step failed, so turn on the position manager's own log rather
than guessing. **Help → Diagnostic Tools → Debug Log Settings**, add:

```text
#dev.wstein.flixplugin.debugger
```

Then reproduce and read `idea.log`. Each line names one step:

| Line | Meaning |
| --- | --- |
| `createPrepareRequests(...): watching all prepares` | the breakpoint asked to be told about future classes |
| `getAllClasses(Main.flix:44): 0 of N loaded classes matched` | no loaded class claims that source — expected before the class loads, a problem afterwards |
| `locationsOfLine(Clo$main$…): no source name matches Main.flix` | the class was reached but its recorded source name did not resolve to this file |
| `locationsOfLine(..., names=[...], line=44): 0 location(s)` | the class and file matched, but the line has no code — check `javap -l` |
| *(nothing at all)* | the manager was never consulted; the breakpoint is not reaching it |

The last row is the important one: it separates "my mapping is wrong" from "I am not being asked",
and those have entirely different causes.

## Results

| # | Check | Result |
| --- | --- | --- |
| 1 | A `.flix` breakpoint set before its class loads becomes verified and hits | |
| 2 | A `.java` breakpoint verifies and hits in the same session | |
| 3 | Step Into moves Flix → project Java | |
| 4 | Step Over stays in Java | |
| 5 | Step Out returns to the correct Flix line | |
| 6 | The stack shows both Flix and Java frames, each navigating to the right file and line | |
| 7 | Java locals, watches and expression evaluation work in a Java frame | |
| 8 | A breakpoint in a **SMAP** class verifies and hits | |
| 9 | A breakpoint in a **no-SMAP** class verifies and hits — needs the self-contained fixture below | |
| 10a | *Fixture:* two `Main.flix` files exist in different project modules — record both paths | |
| 10b | *Fixture:* neither one's class has a `SourceDebugExtension` — record the `javap -v` line | |
| 10c | *Fixture:* both report a bare `SourceFile: Main.flix`, not a path — record it | |
| 10 | Duplicate bare-name `Main.flix` binds to **neither** rather than to the wrong one — or *not reachable*, with evidence | |
| 11 | Pause, continue, terminate and detach behave | |
| 12 | **No DAP process** — `ps aux \| grep -c '[F]lixDebugAdapter'` prints `0` **while suspended**, and the console shows `Connected to the target VM` rather than `[flix-debug-adapter]` | |
| 13 | All of the above under `runIdeSplitMode` as well as `runIde` | |

### Known open question: `let args` — evidence before any fix

A breakpoint on `let args = …` did not verify under the DAP path even though `javap -l` shows the
line present at bytecode offset 0. It is the only line at offset 0 — the entry of the CPS
continuation frame `applyFrame` — and `locationsOfLine("Flix", "Main.flix", 43)` returned nothing
for it while every other line resolved.

**Run these two queries against the live VM before changing anything**, and record both results:

```java
rt.locationsOfLine("Flix", "Main.flix", 43)                        // preferred stratum
rt.locationsOfLine(rt.defaultStratum(), "/abs/path/Main.flix", 43) // default stratum
```

| Outcome | Reading | Action |
| --- | --- | --- |
| Both empty | The line is not addressable in this class at all; offset 0 is not the issue. | Look elsewhere — likely which class holds the line. |
| Both return a location | The lookup is fine; the DAP adapter's failure was its own. | No change here. |
| Default returns, `"Flix"` does not | A stratum-translation edge at offset 0. | *Then*, and only then, add a default-stratum fallback in `FlixPositionManager.locationsOfLine`. |

The `"Flix"` preference is not incidental — it is what makes an inlined frame resolve to the file it
came from rather than to the wrong line of the enclosing one. Falling back before the evidence
supports it would trade that precision away to chase a symptom, and the loss would only show up in
inlined code, which is the hardest place to notice it.

Note also that at offset 0 the binding has not executed — `Env.getArgs()` runs after — so stopping
there would show `args` unbound. "Stop at `let args`" and "stop before the call producing it" are
the same position in CPS, which may make this a question about what the breakpoint should *mean*
rather than a defect.

## Verdict

**Green** — all of 1–13. Proceed to the native run/debug configuration, which is what lets the
gutter's Debug action replace the DAP path.

**Yellow** — mixed breakpoints work but class-prepare timing, source lookup or split-mode placement
needs bounded fixes. Time-box and re-run.

**Red** — the required Java-debugger extension points are unusable for this IDE, or Flix positions
cannot be mapped reliably. Record the evidence and fall back to extending the single DAP/JDI
adapter for mixed Java support. Never ship two simultaneous JDWP owners.
