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
`0.79.0-20261108.013727-1`, using artifact-only Ivy URLs to avoid floating snapshot
metadata. This is not a stable 0.79 release claim. Strict dependency verification
pins SHA-256 for all three artifacts in `gradle/verification-metadata.xml`; the real
PSI producer independently verifies its data jar bytes before projecting.

Qualified digests:

- data: `6092ac9a0f09b801f6b02ab7304af46247c90c9ac33a7aebc2642209031242e0`
- runner: `ea02ead1dbe3c5d9d22f6bfffec7f8207715f04ebc0d33a694e9f75aebc1fec2`
- plugin: `0f28a0eff272e7d1b2d713f955e4a63c810ce5b852316eda3106d07c964e1615`

Flix pin: `36d923ca325dc466b28c63419667e6026a80f920`. The 163-fixture
revision is `aa16763bda3466465bc0735b4c2b14dcf2e072fc64893b49991f74cefeeb312f`.
The exact fixture/path/reason list and depth gates bind this input set. Reports
are `build/reports/flix-spec/report.json` and `report.html` in this directory.

See [the review](../docs/conformance-migration.md) for the four structural and six
recovery identities, fixture binding, depth floors and explicit limitations.
Run [fault injection](../scripts/verify-conformance-gate.sh) with the runner jar,
extracted spec root and actual directory to test that the gate still fails safely.
Historical pilot evidence is retained under `conformance-pilot/README.md`.
