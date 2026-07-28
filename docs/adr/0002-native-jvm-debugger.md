# ADR 0002 — IntelliJ's Java debugger is the sole JDWP owner

- **Status:** Accepted
- **Date:** 2026-07-27

## Context

Flix compiles to JVM bytecode. Debugging today goes through a vendored
`FlixDebugAdapter.java`, a JDWP↔DAP bridge driven by LSP4IJ's generic DAP client. It
works — breakpoints resolve and hit, and it pretty-prints Flix records and tagged unions
— but every debugger capability it offers had to be implemented inside the adapter.

The alternative is to let IntelliJ's own Java debugger own the JDWP connection and teach
it about Flix source positions, the way the Scala, Kotlin and Groovy plugins do.

### The bytecode already carries what is needed

The Flix fork used by this project (`wstein/flix-fork`) emits a JSR-45/SMAP
`SourceDebugExtension` with stratum literal `"Flix"`:

- `main/src/ca/uwaterloo/flix/language/phase/jvm/Smap.scala:136` — `Stratum = "Flix"`
- `GenFunAndClosureClasses.scala:109,180,272` —
  `visitor.visitSource(defn.loc.source.name, smap.build(className).orNull)`

Upstream `flix/flix` passes `null` there and emits no SMAP.

**SMAP is not emitted for every class.** `Smap.build()` returns `None` when:

1. `foreign.isEmpty` — the class draws on a single `.flix` file. This is the common case;
   SMAP exists only after inlining pulls in code from other files.
2. `primaryLines + foreign.size > 65535` — the `u2` ceiling on `LineNumberTable`.
3. The class is built through `ClassMaker.scala:155`, which still passes `null`.

In all three cases `availableStrata()` contains only `"Java"` — but the class still has
`SourceFile = "Main.flix"` and real `.flix` line numbers, because `Smap.register` maps the
primary source with the identity function.

### Which lines are breakpointable at all depends on `--Xdebug`

A breakpoint can only bind to a line the `LineNumberTable` mentions, and the fork emits those
selectively. Of the 30 `addLoc` call sites under `phase/jvm`, **7 are gated on
`flix.options.xdebug`** — and they cover the statement forms most code is made of:

| Expression | `GenExpression.scala` |
| --- | --- |
| `Expr.Let` | `:1467`, `:1483` |
| `Expr.ApplyDef` — ordinary function calls | `:1186` |
| `Expr.ApplyClo` — closure calls | `:1125` |
| `Expr.IfThenElse` | `:1376` |
| `Expr.Stm` — statement sequences | `:1500`, `:1504` |

So a program compiled **without** `--Xdebug` carries no line-number entry for a `let` binding, a
call, an `if`, or a statement sequence, and a breakpoint on any of those lines can never verify no
matter what the position manager does. The flag is not only about starting the JDWP agent; it
changes what debug information exists in the class file.

When a breakpoint refuses to verify, check the class before suspecting the position manager:

```bash
javap -l -p path/to/Class.class | grep -A20 LineNumberTable
```

A line absent from the table is a compiler-invocation problem, not a plugin one.

### CPS makes Java's Step Over semantics inapplicable to Flix frames

`--Xdebug` decides *which* lines exist. The CPS transformation decides how they are **distributed
across methods**, and that has its own consequence — one the position manager cannot address,
because it is not a mapping question.

Measured over the 325 `Clo$main$*` classes `flix-lab` compiles under `--Xdebug`
(`javap -p -l`, 2026-07-28):

| Method | Line-number table |
| --- | --- |
| `invoke()` | **0 of 325** carry one at all |
| `applyFrame(Value$)` | **260 of 325** carry exactly one entry, at bytecode offset 0 |

Each continuation frame covers a single source line; `invoke()` is a synthetic bridge that only
calls `applyFrame`.

JDI defines Step Over as *run until the line number changes within this frame, or the frame pops*.
In a frame holding one line, the first clause can never fire, so the step always ends on frame pop —
in `invoke()`, which has no line table, so there is no source to show and the IDE falls back to the
decompiler.

Two things follow, and they are the reason this is recorded here rather than as a bug:

1. **Flix needs a stepping policy, and it is a separate concern from position mapping.**
   `PositionManager` answers "what source is this location?"; it has no say in where a step stops.
   The extension points that do are `com.intellij.debugger.extraSteppingFilter` and
   `com.intellij.debugger.jvmSteppingCommandProvider`, both public in IU-2026.1.3.
2. **This does not weaken the decision.** Breakpoints, stack navigation, frame-specific evaluation
   and Flix ↔ Java transitions all work through the native debugger; a DAP adapter would face the
   identical bytecode and the identical problem, with none of the cross-language benefit. The
   trade-off is that Flix stepping needs code the Java case gets for free.

The correction not to make: the DAP adapter's broad `com.*`/`org.*`/`net.*` step exclusions. They
would remove user Java, Kotlin and Scala from stepping — the frames this architecture exists to
reach — to fix a problem confined to Flix's own generated bridges.

### The required platform API is public and stable

Verified against IU-2026.1.3, `plugins/java/lib/modules/intellij.java.debugger.jar`:

```java
public abstract class com.intellij.debugger.PositionManagerFactory {
  public static final ExtensionPointName<PositionManagerFactory> EP_NAME;
  public abstract PositionManager createPositionManager(DebugProcess);
}

public interface com.intellij.debugger.PositionManager {
  SourcePosition getSourcePosition(Location) throws NoDataException;
  List<ReferenceType> getAllClasses(SourcePosition) throws NoDataException;
  List<Location> locationsOfLine(ReferenceType, SourcePosition) throws NoDataException;
  ClassPrepareRequest createPrepareRequest(ClassPrepareRequestor, SourcePosition) throws NoDataException;
  default Set<? extends FileType> getAcceptedFileTypes();
  default boolean isAcceptedFileType(FileType);
}

public interface com.intellij.debugger.MultiRequestPositionManager extends PositionManager {
  List<ClassPrepareRequest> createPrepareRequests(ClassPrepareRequestor, SourcePosition) throws NoDataException;
}
```

Extension points: `com.intellij.debugger.positionManagerFactory` and
`com.intellij.debugger.javaBreakpointHandlerFactory`, both declared by the Java plugin.

## Decision

IntelliJ's native Java debugger is the sole owner of the JDWP connection for Flix debug
sessions in this plugin.

1. A Flix debug launch starts the compiler with `--Xdebug` and a JDWP listener; the
   platform Java debugger attaches as the **only** JDWP client.
2. `FlixPositionManager` is **dual-mode**. It claims a location when
   `sourceName().endsWith(".flix")`, then resolves through the `"Flix"` stratum if
   `availableStrata()` offers it and through the default-stratum overloads otherwise. A
   rule based on `availableStrata().contains("Flix")` alone would silently fail to claim
   the majority of Flix frames.
3. The position manager **never** calls `VirtualMachine.setDefaultStratum("Flix")`. That
   setting is process-global and would corrupt every other language's position manager in
   the session.
4. Ownership is declined declaratively through `isAcceptedFileType`/`getAcceptedFileTypes`
   and by throwing `NoDataException` for anything not backed by a `.flix` source, so the
   Java, Kotlin, Scala and Groovy position managers keep their own frames.
5. Stepping filters are restricted to `dev.flix.runtime.*`, `ca.uwaterloo.*` and the
   `java.lang.invoke.LambdaForm$*` synthetics generated by Flix's `invokedynamic` lambdas.
   `FlixDebugAdapter.java`'s broad `com.*`/`org.*`/`net.*` exclusions are **not** carried
   over — they were sized for a Flix-only session and would exclude most user Java, Kotlin
   and Scala code from stepping.
6. The native Java debugger and the DAP/JDI adapter are **never** attached to the same
   debuggee. Exactly one debugger owns the connection, suspension state, breakpoints,
   stepping requests and lifecycle.
7. The DAP path stays registered and functional until the native path passes its gate,
   then its IntelliJ-side registration, descriptor, factory and vendored adapter copy are
   removed. The canonical adapter continues to serve VS Code from `flix-lab`.

## Consequences

- Java parity comes for free: mixed Flix/Java stacks, frame-specific expression
  evaluation, conditional breakpoints, exception breakpoints, watches, source-JAR and JDK
  source resolution, and HotSwap for compatible Java classes.
- Other JVM languages compose without further work. Kotlin is bundled with IDEA; Scala and
  Groovy contribute their own position managers when their plugins are installed. The
  guarantee this plugin owes them is non-interference.
- The debugging half gains a hard dependency on the Java plugin, so it is scoped to its own
  content module. The language half must keep loading in IDEs without Java support.
- Flix expression evaluation, Smart Step Into for Flix call targets and custom Flix value
  renderers are follow-up features, not prerequisites.
- Flix HotSwap is not claimed until recompilation and SMAP class-redefinition behavior are
  separately proven.
- Datalog rule-level debugging is explicitly **not** solved by this decision. The solver is
  Flix library code (`Fixpoint3`), so JVM stepping lands inside fixpoint internals.
  Relational debugging is a separate cooperative protocol; see the implementation plan.
