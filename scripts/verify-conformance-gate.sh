#!/usr/bin/env bash
# Fault-inject the real consumer gate; run after producing fresh projections.
set -euo pipefail
RUNNER="${1:?usage: verify-conformance-gate.sh RUNNER_JAR SPEC_ROOT ACTUAL_DIR}"
SPEC="${2:?spec root required}"
ACTUAL="${3:?actual directory required}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -R "$ACTUAL" "$WORK/actual"
cp "$ROOT/language/src/test/resources/conformance/accepted.json" "$WORK/accepted.json"
run() {
  java -jar "$RUNNER" --spec-root "$SPEC" --source-root "$SPEC" \
    --actual "$WORK/actual" --map "$ROOT/language/src/test/resources/conformance/projection-map.json" \
    --accepted "$WORK/accepted.json" --baseline 4 --recovery-baseline 6 \
    --depth-floor 36 --recovery-depth-floor 40 --report "$WORK/report.json"
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
echo 'OK: reviewed identities pass; unreviewed identities, stale revisions and lost tokens fail'
