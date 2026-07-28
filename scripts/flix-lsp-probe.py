#!/usr/bin/env python3
"""Exercise the Flix language server the way the IDE does, and report what it answers.

Why this exists
---------------
The plugin delegates every language-intelligence feature to `flix lsp` through LSP4IJ. That chain
crosses a process boundary, and nothing automated exercises it: `docs/phase-8-verification.md`
records completion and hover as never having been run in a test or a recorded session.

A unit test cannot cover it — the server is a separate process, and what can break is the
conversation between them rather than either side alone. This runs that conversation and prints
what came back, which is the same trade `scripts/FlixLineProbe.java` makes for the debugger.

It has already earned its place once: an earlier version of this handshake is what proved the
fork's Plain-LSP server never loaded workspace jars, taking `Undefined Java class` from five
occurrences to zero.

Use
---
    python3 scripts/flix-lsp-probe.py <flix-vendor.jar> <project-dir> <file.flix> [line] [col] [prefix]

`prefix` is text to type at that position before asking for completion, sent as a `didChange` the
way an editor would. Without it the buffer is complete valid code, and a server returning nothing
there is behaving correctly -- completion is what a user gets *while typing*. Nothing is written to
disk.

Exits non-zero if the server reports an error diagnostic, so it can gate a script. Completion and
hover are reported rather than asserted: what counts as a good answer depends on where the caret
is, and a probe that guessed would be a test that lies.
"""

import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path


class LspClient:
    """A minimal LSP client speaking JSON-RPC over the server's stdio."""

    def __init__(self, jar: Path, root: Path):
        self.proc = subprocess.Popen(
            ["java", "-jar", str(jar), "lsp"],
            cwd=str(root),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        self.next_id = 1
        self.responses = {}
        self.diagnostics = []
        self._lock = threading.Lock()
        threading.Thread(target=self._read_loop, daemon=True).start()

    def _send(self, message: dict) -> None:
        body = json.dumps(message).encode()
        self.proc.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
        self.proc.stdin.flush()

    def notify(self, method: str, params: dict) -> None:
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def request(self, method: str, params: dict, timeout: float = 120.0):
        with self._lock:
            request_id = self.next_id
            self.next_id += 1
        self._send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})

        deadline = time.time() + timeout
        while time.time() < deadline:
            if request_id in self.responses:
                return self.responses.pop(request_id)
            time.sleep(0.05)
        return None

    def _read_loop(self) -> None:
        while True:
            header = b""
            while b"\r\n\r\n" not in header:
                byte = self.proc.stdout.read(1)
                if not byte:
                    return
                header += byte
            fields = dict(
                line.split(b": ", 1) for line in header.strip().split(b"\r\n")
            )
            payload = json.loads(self.proc.stdout.read(int(fields[b"Content-Length"])))

            if "id" in payload and "method" not in payload:
                self.responses[payload["id"]] = payload.get("result")
            elif payload.get("method") == "textDocument/publishDiagnostics":
                self.diagnostics.extend(payload["params"].get("diagnostics", []))

    def close(self) -> None:
        self.proc.kill()


def main() -> int:
    if len(sys.argv) < 4:
        print(__doc__)
        return 2

    jar = Path(sys.argv[1]).resolve()
    root = Path(sys.argv[2]).resolve()
    source = Path(sys.argv[3]).resolve()
    line = int(sys.argv[4]) if len(sys.argv) > 4 else 0
    column = int(sys.argv[5]) if len(sys.argv) > 5 else 0
    prefix = sys.argv[6] if len(sys.argv) > 6 else ""

    for path in (jar, root, source):
        if not path.exists():
            print(f"missing: {path}")
            return 2

    client = LspClient(jar, root)
    uri = source.as_uri()

    # Exactly what an LSP4IJ client sends: a display name plus the folder's real location. Passing
    # the name where the URI belongs is what stopped the server loading workspace jars.
    initialized = client.request("initialize", {
        "processId": os.getpid(),
        "rootUri": root.as_uri(),
        # Declared rather than left empty: a server may gate a feature on the client claiming to
        # support it, and an empty object would make the probe blame the server for its own silence.
        "capabilities": {
            "textDocument": {
                "completion": {
                    "completionItem": {"snippetSupport": False},
                    "contextSupport": True,
                },
                "hover": {"contentFormat": ["markdown", "plaintext"]},
                "publishDiagnostics": {},
            },
        },
        "workspaceFolders": [{"uri": root.as_uri(), "name": root.name}],
    })
    print(f"initialize            -> {'ok' if initialized else 'NO RESPONSE'}")
    client.notify("initialized", {})

    client.notify("textDocument/didOpen", {
        "textDocument": {
            "uri": uri,
            "languageId": "flix",
            "version": 1,
            "text": source.read_text(),
        },
    })

    # Diagnostics arrive unsolicited once the server has analysed the file.
    time.sleep(8)
    errors = [d for d in client.diagnostics if d.get("severity") == 1]
    print(f"diagnostics           -> {len(client.diagnostics)} total, {len(errors)} error(s)")
    for diagnostic in errors[:5]:
        text = diagnostic.get("message", "").replace("\n", " ")[:100]
        print(f"    error: {text}")

    position = {"line": line, "character": column}

    if prefix:
        # What the editor sends as the user types. Completion against an already-complete buffer is
        # a different question from the one users ask, and answering nothing there is correct.
        lines = source.read_text().split("\n")
        lines[line] = lines[line][:column] + prefix + lines[line][column:]
        client.notify("textDocument/didChange", {
            "textDocument": {"uri": uri, "version": 2},
            "contentChanges": [{"text": "\n".join(lines)}],
        })
        position = {"line": line, "character": column + len(prefix)}
        time.sleep(5)
        print(f"typed {prefix!r:<15} -> at {position['line']}:{position['character']}")

    completion = client.request("textDocument/completion", {
        "textDocument": {"uri": uri},
        "position": position,
        # 1 = Invoked, i.e. the user asked explicitly rather than typing a trigger character.
        "context": {"triggerKind": 1},
    })
    items = completion.get("items", completion) if isinstance(completion, dict) else completion
    count = len(items) if isinstance(items, list) else 0
    at = f"{position['line']}:{position['character']}"
    print(f"completion {at:<11} -> {count} item(s)"
          + (f", first: {items[0].get('label')}" if count else ""))

    hover = client.request("textDocument/hover", {
        "textDocument": {"uri": uri}, "position": position,
    })
    contents = (hover or {}).get("contents") if isinstance(hover, dict) else None
    rendered = json.dumps(contents)[:100] if contents else "none"
    print(f"hover {at:<16} -> {rendered}")

    client.close()
    # Errors fail; an empty completion list does not. What a good answer looks like depends on
    # where the caret is, and a probe that guessed would be a test that lies.
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
