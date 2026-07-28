# ADR 0003 — Cross-module contracts stay pure functions

**Status:** accepted, 2026-07-28. Prompted by an external architecture review.

## Context

`shared` holds two things every other module needs: `FlixJar`, which decides *which* compiler jar to
launch, and `FlixLaunchCommand`, which builds the invocation. Both are static methods on final
classes.

A review characterised this as a junk drawer — static utilities dumped into a shared bucket to
bypass module isolation — and proposed replacing them with a service interface (`FlixCompilerResolver`)
consumed through the platform's service container, so implementations could be swapped in tests and
the two modules could evolve independently.

The concern behind that is real and worth stating plainly: `backend` and `debugger` **cannot** depend
on each other (one needs LSP4IJ, the other the Java plugin, and content modules are class-loader
isolated), so anything they share has nowhere to go but `shared`. Left unchecked that becomes a
dumping ground.

## Decision

Keep them as pure functions. Do not introduce platform services for this.

Three reasons, in order of weight.

**1. `shared` has no IntelliJ Platform dependency, and that is load-bearing.** It is loaded in every
process, and a platform service requires the platform. Introducing `@Service` would either drag the
platform into `shared` or push the contract back into modules that cannot see each other — the
problem it was meant to solve. `FlixJar.resolve` takes a base path rather than a `Project` precisely
so this boundary holds; the `Project` lookup stays in `FlixFork`, on the platform side.

**2. The testability argument does not apply to these two.** The review's premise was that static
methods cannot be mocked without bytecode manipulation. True in general; irrelevant here, because
neither has anything to mock. `FlixJar.resolve(basePath, pinnedJar)` and
`FlixLaunchCommand.debug(jar, entryPoint, port, suspend)` are total functions of their arguments,
and they carry **28 tests** between them covering ordering rules, malformed input and the failure
modes each was written for. A service interface would add an indirection whose only effect is to let
a test substitute a function that is already trivially callable.

**3. What is actually shared is a *rule*, not a capability.** The reason `FlixJar` exists at all is
that two modules must reach the **same** answer: a debug session running a different compiler than
the editor was analysed with is invisible until behaviour disagrees. A single pure function is the
strongest available guarantee of that. An injectable service is weaker — it invites two
registrations.

## What the review got right

**A dedicated integration-test module.** Proposed as a top-level Gradle module depending on all
content modules at once, giving a unified classpath for tests that cross boundaries. This is
adopted in principle and recorded as the route for the one test gap that is currently open by
choice: `FlixPositionManager.getAllClasses` and the class-prepare filter need a file that is both
typed as Flix and present in the file index, which no single module's fixture provides today. See
[verification coverage](../phase-8-verification.md).

**Live LSP feature coverage.** Completion and hover have never been exercised end to end. That
remains an accurate description of a real gap.

## What was declined, and why

**Replacing `checkIntegrationGlue` with an IDE inspection.** An inspection gives feedback in the
editor as the mistake is made, which is genuinely better ergonomics. But an inspection does not fail
a build, and the contract's value is that drift *cannot* be committed. The two are complementary
rather than alternatives; the checker stays, and an inspection is a possible addition, not a
replacement.

## Consequences

- `shared` stays platform-free. Anything needing the platform belongs on the platform side of the
  boundary, as `FlixFork` demonstrates.
- The junk-drawer risk is real and is not addressed by this decision. What keeps it in check is that
  `shared` may hold only rules both sides must agree on — if a future addition is a *capability*
  rather than a shared answer, that is the signal to revisit this, and a service interface is then
  the right shape.
- The reasoning is recorded so the next reviewer sees a decision rather than an accident.
