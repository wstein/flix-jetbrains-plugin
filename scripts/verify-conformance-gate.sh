#!/usr/bin/env bash
# Fault-inject the real consumer gate; run after producing fresh projections.
set -euo pipefail
RUNNER="${1:?usage: verify-conformance-gate.sh RUNNER_JAR SPEC_ROOT ACTUAL_DIR}"
SPEC="${2:?spec root required}"
ACTUAL="${3:?actual directory required}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
# Check the standard task graph itself: the historical check must not become optional.
"$ROOT/gradlew" -p "$ROOT/conformance" --dry-run check > "$WORK/graph.log" 2>&1
grep -q '^:historicalFlixSpecCheck SKIPPED$' "$WORK/graph.log"
grep -q '^:flixSpecCheck SKIPPED$' "$WORK/graph.log"
cp -R "$ACTUAL" "$WORK/actual"
cp "$ROOT/language/src/test/resources/conformance/accepted.json" "$WORK/accepted.json"
run() {
  java -jar "$RUNNER" --spec-root "$SPEC" --source-root "$SPEC" \
    --actual "$WORK/actual" --map "$ROOT/language/src/test/resources/conformance/projection-map.json" \
    --accepted "$WORK/accepted.json" --baseline 4 --recovery-baseline 4 \
    --depth-floor 63 --recovery-depth-floor 65 --report "$WORK/report.json"
}
expect_exit() {
  local expected="$1" status=0
  run > "$WORK/run.log" 2>&1 || status=$?
  if [ "$status" -ne "$expected" ]; then
    cat "$WORK/run.log" >&2
    echo "Expected exit $expected, got $status" >&2
    exit 1
  fi
}
expect_exit 0
cp "$WORK/accepted.json" "$WORK/original.json"
# Same parser output and counts, but one location was not reviewed: identity must fail.
jq '.lanes.recovery_conformance[0] = "not-the-reported-difference"' "$WORK/original.json" > "$WORK/accepted.json"
expect_exit 1
grep -q 'not in the accepted set' "$WORK/run.log"
jq '.fixtureRevision = "stale"' "$WORK/original.json" > "$WORK/accepted.json"
expect_exit 2
grep -q 'fixture revision' "$WORK/run.log"
cp "$WORK/original.json" "$WORK/accepted.json"
# Structural agreement is not permission to lose source text.
jq 'walk(if type == "object" and has("token") then .text = "" else . end)' \
  "$ACTUAL/hello.json" > "$WORK/actual/hello.json"
expect_exit 1
grep -q 'source invariants failed' "$WORK/run.log"
# The 167-fixture gate is required separately and retains the historical floor.
cp -R "$SPEC" "$WORK/historical"
rm "$WORK/historical/fixtures/expected/identifier__reserved-val.json" "$WORK/historical/fixtures/raw/identifier__reserved-val.json"
historical() {
  java -jar "$RUNNER" --spec-root "$WORK/historical" --source-root "$WORK/historical" \
    --actual "$WORK/actual" --map "$ROOT/language/src/test/resources/conformance/projection-map.json" \
    --accepted "$WORK/historical-accepted.json" --baseline 3 --recovery-baseline 2 \
    --depth-floor 63 --recovery-depth-floor 67 --report "$WORK/historical-report.json"
}
cp "$ACTUAL/hello.json" "$WORK/actual/hello.json"
cp "$ROOT/language/src/test/resources/conformance/accepted-historical.json" "$WORK/historical-accepted.json"
historical > "$WORK/run.log" 2>&1
# A missing historical projection must fail, not silently reduce coverage.
rm "$WORK/actual/hello.json"
status=0
historical > "$WORK/run.log" 2>&1 || status=$?
if [ "$status" -eq 0 ]; then
  echo 'Historical gate accepted a missing fixture' >&2
  exit 1
fi
grep -q 'had no consumer output' "$WORK/run.log"
grep -q 'hello.json' "$WORK/run.log"
echo 'OK: full and historical gates pass; unreviewed identities, stale revisions, lost tokens and missing historical fixtures fail'
