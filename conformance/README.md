# Conformance gate

```sh
./gradlew -p conformance --configuration-cache check
```

This is the normal CI structural/recovery/source-fidelity gate. It uses the
published Gradle plugin, real PSI producer and runner; there is no Kotlin copy of
the comparison algorithm. The separate build avoids a comparison dependency in
the shipped IDE plugin. Root `./gradlew check` runs IDE/unit tests; CI also requires
this gate before preparing a release draft.

Data, runner and plugin resolve from the immutable timestamped snapshot coordinate
`0.79.0-20261109.125843-3`, using artifact-only Ivy URLs to avoid floating snapshot
metadata. This is not a stable 0.79 release claim. Strict dependency verification
pins SHA-256 for all three artifacts in `gradle/verification-metadata.xml`; the real
PSI producer independently verifies its data jar bytes before projecting.

Qualified digests:

- data: `f58c987974501e22f6671511a4454458fb669511c140788d27a560ac09b03922`
- runner: `989d4117462ddd7b3a5d8de57e21e0ca5c82f121e9e5d2fdc239c7ccbfa0f1ed`
- plugin: `0f28a0eff272e7d1b2d713f955e4a63c810ce5b852316eda3106d07c964e1615`

Flix pin: `36d923ca325dc466b28c63419667e6026a80f920`. The 166-fixture
revision is `7e017bc8d4850fd293c6c97ddb3e4e5c4cb8cee0ed691d2750d461eccc236dd2`.
The exact fixture/path/reason list and depth gates bind this input set. Reports
are `build/reports/flix-spec/report.json` and `report.html` in this directory.

See [the review](../docs/conformance-migration.md) for the three structural and two
recovery identities, fixture binding, depth floors and explicit limitations.
Run [fault injection](../scripts/verify-conformance-gate.sh) with the runner jar,
extracted spec root and actual directory to test that the gate still fails safely.
Historical pilot evidence is retained under `conformance-pilot/README.md`.

## Opt-in local candidate qualification

A candidate data jar from a required source check can be tested without changing
the published pin. Both the file and its SHA-256 are explicit; a missing or wrong
SHA fails rather than falling back to the published data. Use a dedicated output
directory because the producer clears its previous JSON output.

```sh
./gradlew :language:test \
  --tests '*FlixSpecConformanceTest.testFixturesParseAndProject' \
  --tests '*FlixSpecConformanceTest.testProjection*' \
  -PflixSpec.candidateData=/absolute/candidate-data.jar \
  -PflixSpec.candidateSha256=EXACT_SHA256 \
  -PflixSpec.projectionDir=/absolute/dedicated-candidate-output
```

The published data contains 166 fixtures; qualification measures 3 structural and 2 recovery differences, with 64%/67% depth. Token accounting and positions pass. Its bytes match the earlier candidate below.

The earlier unpublished 166-fixture source-check candidate at flix-spec source revision
`1463cdaafd8dd5b67c5cdd3b9948923c1e98b1b9` passes with data SHA-256
`f58c987974501e22f6671511a4454458fb669511c140788d27a560ac09b03922` and
runner SHA-256 `9e8c4388c3feed99d3918b58b9c1b5ecd6aff2f265662778573a57efb0ed0b2c`.
Fixture revision: `7e017bc8d4850fd293c6c97ddb3e4e5c4cb8cee0ed691d2750d461eccc236dd2`.
All 166 fixtures project; structure 3 differences, 163 agreeing, depth 64%;
recovery 2 differences, 28/30 agreeing, depth 67%. Token accounting and token
positions pass; diagnostics remain unmodeled. Every remaining fixture/path/reason
identity is unchanged from the published 163-fixture pin. The comparison used a
separate candidate accepted file with only the fixture revision updated after
checking identity equality, preserving the 3/2 and 63%/67% gates.
No standalone candidate build or publication was performed by this consumer.
