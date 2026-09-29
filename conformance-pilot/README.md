# Pre-release Gradle plugin pilot

This is an opt-in, separate build using the real IntelliJ PSI parser. Normal
plugin builds retain their current dependencies and local comparator. No grammar,
compiler pin, or count baseline is changed by this pilot.

Requires Java 21 and a local Maven publication of flix-spec runner/plugin 0.77.4
(including the plugin marker). The runner/plugin are not yet released. In the
flix-spec checkout, publish locally with:

```sh
./gradlew :tools:conformance:publish :tools:gradle-plugin:publish \
  -PflixSpec.publishRepo=file:///absolute/path/to/pilot-maven
```

Then from this repository:

```sh
./gradlew -p conformance-pilot --configuration-cache check \
  -PflixSpec.pilotRepository=file:///absolute/path/to/pilot-maven
```

The plugin resolves its marker, implementation and runner from that repository;
data stays at this consumer's published **0.75.8**. It extracts the bundle, runs
`testFixturesParseAndProject` with `--rerun` against the real parser, then compares
the fresh projections. The producer asserts byte identity between its test-classpath
data jar and the plugin's resolved data jar before emitting anything. This also
catches future independent pin changes. Projection output is schema 2, `form: raw`,
under `language/build/flix-spec-projection`; old JSON files there are removed by
the producer. Neither a Flix checkout nor an oracle jar is used by this path.

Reports, including on comparison failure:

- `conformance-pilot/build/reports/flix-spec/report.json`
- `conformance-pilot/build/reports/flix-spec/report.html`

## Measured outcome — 2026-09-29

Runner/plugin 0.77.4, report schema 9, Gradle 9.7.1, Java 21:

| Lane | Measurement | Gate |
| --- | --- | --- |
| Structure | 130/138 agree, 13 differences, depth 36% | Fails unchanged baseline 4 |
| Recovery | 19/22 agree, 6 differences, depth 40% | Fails default baseline 0; previously unmeasured |
| Diagnostics | No diagnostic output | Explicitly not applicable |
| Source invariants | Shape passes | Tokens and lexical correctness unmeasured |

The map opts into structure and recovery only. It does not pretend that empty
diagnostics are error-reporting support or that PSI children are a token stream.
There is no existing depth floor to migrate in this consumer; defaults remain 0.

**The structural discrepancy is comparator drift, not a parser change.** The local
Kotlin comparator drops token leaves before testing elision arity. The new runner
retains their counts, so e.g. a token-bearing `CommentList` or multi-child `QName`
cannot disappear as an empty/single-child wrapper. As a diagnostic experiment,
removing token leaves from a temporary copy of the expected trees makes the runner
report exactly the old **4 differences / 134 agreeing fixtures**. Untouched data
reports 13. This experiment is not a workaround and is not part of the pilot build.

Configuration-cache reuse was verified in both builds; the producer still executes
on each invocation. Supplying data 0.77.2 to the producer's `flixSpec.pilotBundle`
check correctly fails with an artifact-identity error. JSON and HTML are generated
for the genuine conformance failure, which the plugin propagates as runner exit 1.

Before making this the default gate, review the token-sensitive map/adapter
differences and the six recovery differences. Do not simply change 4 to 13 or
accept 6 to turn the build green. The old comparator remains only for comparison
during migration; its passing result is not equivalent to passing the runner.
