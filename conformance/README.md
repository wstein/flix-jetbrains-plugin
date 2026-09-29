# Conformance gate

```sh
./gradlew -p conformance --configuration-cache check
```

This is the normal CI structural/recovery/source-fidelity gate. It uses the
published Gradle plugin, real PSI producer and runner; there is no Kotlin copy of
the comparison algorithm. The separate build avoids a comparison dependency in
the shipped IDE plugin. Root `./gradlew check` runs IDE/unit tests; CI also requires
this gate before preparing a release draft.

Runner/plugin 0.77.4 are not released yet. Until publication, pass
`-PflixSpecRepository=file:///absolute/path/to/staged-maven`. CI builds a pinned
flix-spec commit and stages its Maven publications without publishing remotely.
The producer checks that its 0.75.8 fixture jar is byte-identical to the plugin's.
Reports are `build/reports/flix-spec/report.json` and `report.html` in this directory,
including on comparison failure. Each invocation regenerates PSI projections.

See [the review](../docs/conformance-migration.md) for the four structural and six
recovery identities, fixture binding, depth floors and explicit limitations.
Run [fault injection](../scripts/verify-conformance-gate.sh) with the runner jar,
extracted spec root and actual directory to test that the gate still fails safely.
Historical pilot evidence is retained under `conformance-pilot/README.md`.
