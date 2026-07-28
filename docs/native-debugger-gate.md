# Native JVM debugger gate

The Phase-3 proof for [ADR 0002](adr/0002-native-jvm-debugger.md): does IntelliJ's own Java
debugger, plus `FlixPositionManager` and `FlixJavaDebugAware`, actually debug Flix?

Everything below runs against the **stock Remote JVM Debug** configuration. The gutter arrow and
right-click Debug both route through LSP4IJ's DAP producer, so using either would test the path
this gate exists to replace.

Record the outcome in the results table at the end. A gate nobody wrote down is a gate that gets
re-run.

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
to remote JVM*. Run it in Debug.

## Choosing fixtures deliberately

Two of the checks below only mean something against the right class, and the compiler decides which
you get. Inspect before relying on one:

```console
javap -v -p 'build/class/Clo$main$NNNNN.class' | grep -E 'SourceFile|SourceDebugExtension'
javap -l -p 'build/class/Clo$main$NNNNN.class' | grep -A30 LineNumberTable
```

- **SMAP class** — has `SourceDebugExtension`. Its `sourceNames("Flix")` are bare (`Main.flix`)
  while `SourceFile` is an absolute path. Exercises stratum selection.
- **No-SMAP class** — no `SourceDebugExtension`. Only the default stratum, and `SourceFile` may be
  absolute. Exercises the dual-mode fallback.
- **Ambiguity** needs *two* `Main.flix` in different modules whose classes carry **no** SMAP and
  report a **bare** `SourceFile`. If either reports a path, disambiguation resolves it and the
  refusal branch never runs — the check would pass without testing anything.

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
| 9 | A breakpoint in a **no-SMAP** class verifies and hits | |
| 10 | Duplicate bare-name `Main.flix` binds to **neither** rather than to the wrong one | |
| 11 | Pause, continue, terminate and detach behave | |
| 12 | **No DAP process starts** — `ps aux \| grep FlixDebugAdapter` finds nothing | |
| 13 | All of the above under `runIdeSplitMode` as well as `runIde` | |

### Known open question

A breakpoint on `let args = …` did not verify under the DAP path even though `javap -l` shows the
line present at bytecode offset 0. It is the only line at offset 0 — the entry of the CPS
continuation frame `applyFrame` — and `locationsOfLine("Flix", "Main.flix", 43)` returned nothing
for it while other lines resolved. Worth checking whether the native path behaves the same, and
whether the default stratum returns the location where `"Flix"` does not:

```java
rt.locationsOfLine("Flix", "Main.flix", 43)
rt.locationsOfLine(rt.defaultStratum(), "/abs/path/Main.flix", 43)
```

If the default stratum resolves it and `"Flix"` does not, the fix is a fallback in
`FlixPositionManager.locationsOfLine` when the preferred stratum yields nothing.

Note also that at offset 0 the binding has not executed — `Env.getArgs()` runs after — so stopping
there would show `args` unbound. "Stop at `let args`" and "stop before the call producing it" are
the same position in CPS.

## Verdict

**Green** — all of 1–13. Proceed to the native run/debug configuration, which is what lets the
gutter's Debug action replace the DAP path.

**Yellow** — mixed breakpoints work but class-prepare timing, source lookup or split-mode placement
needs bounded fixes. Time-box and re-run.

**Red** — the required Java-debugger extension points are unusable for this IDE, or Flix positions
cannot be mapped reliably. Record the evidence and fall back to extending the single DAP/JDI
adapter for mixed Java support. Never ship two simultaneous JDWP owners.
