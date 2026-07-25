#!/bin/bash
# Re-syncs the vendored copy of FlixDebugAdapter.java from flix-lab's canonical source
# (debug-adapter/src/FlixDebugAdapter.java there is also what the VS Code extension uses --
# this plugin's DAP support and flix-lab's are the same adapter, not a fork).
#
# flix-lab is expected as a sibling checkout by default, matching how both repos actually sit
# on disk under ~/github.com/wstein/; override with $FLIX_LAB_DIR if checked out elsewhere.
# Neither repo has a git remote configured yet (both are local-only as of this writing), so this
# is filesystem-based, not a fetch from GitHub -- there's nothing to fetch from yet. Once flix-lab
# is pushed, `--check` here is ready to drop into a GitHub Actions job that also checks out
# flix-lab, to catch drift in CI instead of only locally.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FLIX_LAB_DIR="${FLIX_LAB_DIR:-$DIR/../flix-lab}"
SOURCE="$FLIX_LAB_DIR/debug-adapter/src/FlixDebugAdapter.java"
DEST="$DIR/backend/src/main/resources/dap/FlixDebugAdapter.java"

if [ ! -f "$SOURCE" ]; then
    echo "flix-lab not found at $FLIX_LAB_DIR (debug-adapter/src/FlixDebugAdapter.java missing)." >&2
    echo "Set FLIX_LAB_DIR to its checkout path, or clone github.com/wstein/flix-lab as a sibling of this repo." >&2
    exit 1
fi

if diff -q "$SOURCE" "$DEST" >/dev/null 2>&1; then
    echo "backend/src/main/resources/dap/FlixDebugAdapter.java is already in sync with $SOURCE."
    exit 0
fi

if [ "${1:-}" = "--check" ]; then
    echo "backend/src/main/resources/dap/FlixDebugAdapter.java is OUT OF SYNC with $SOURCE:" >&2
    diff -u "$DEST" "$SOURCE" >&2 || true
    echo >&2
    echo "Run scripts/sync-debug-adapter.sh (without --check) to update the vendored copy." >&2
    exit 1
fi

cp "$SOURCE" "$DEST"
echo "Synced backend/src/main/resources/dap/FlixDebugAdapter.java from $SOURCE."
echo "Review the diff and commit it -- this script only copies the file, it doesn't commit."
