"""Coder-feature script runner. Executes one LLM-generated script and returns
a JSON envelope {ok, stdout, stderr, syntax} — never raises across the JNI
boundary. The process-level sandbox + kill-on-timeout is the real wall
(Kotlin side); the import/open gate runs before code reaches here.

For cross-note ("meta") tasks the host writes every note to a private cache
file and passes its path; the runner loads it into a `notes` global the script
can read (the script itself still can't open files — the gate blocks that).
"""
import io
import json
import traceback
from contextlib import redirect_stdout, redirect_stderr


def run(code, notes_path=""):
    out, err = io.StringIO(), io.StringIO()

    notes = []
    if notes_path:
        try:
            with open(notes_path) as f:
                notes = json.load(f)
        except Exception:
            notes = []

    try:
        compiled = compile(code, "<coder>", "exec")
    except SyntaxError:
        return json.dumps({
            "ok": False, "syntax": True, "stdout": "",
            "stderr": traceback.format_exc(limit=0),
        })
    try:
        with redirect_stdout(out), redirect_stderr(err):
            exec(compiled, {"__name__": "__main__", "notes": notes})
        return json.dumps({
            "ok": True, "syntax": False,
            "stdout": out.getvalue(), "stderr": err.getvalue(),
        })
    except BaseException:
        return json.dumps({
            "ok": False, "syntax": False,
            "stdout": out.getvalue(), "stderr": traceback.format_exc(),
        })
