"""Coder-feature script runner. Executes one LLM-generated script and returns
a JSON envelope {ok, stdout, stderr, syntax} — never raises across the JNI
boundary. Hardening (restricted builtins, op limits) lands with the harness;
the process-level sandbox + kill-on-timeout is the real wall (Kotlin side).
"""
import io
import json
import traceback
from contextlib import redirect_stdout, redirect_stderr


def run(code: str) -> str:
    out, err = io.StringIO(), io.StringIO()
    try:
        compiled = compile(code, "<coder>", "exec")
    except SyntaxError:
        return json.dumps({
            "ok": False, "syntax": True, "stdout": "",
            "stderr": traceback.format_exc(limit=0),
        })
    try:
        with redirect_stdout(out), redirect_stderr(err):
            exec(compiled, {"__name__": "__main__"})
        return json.dumps({
            "ok": True, "syntax": False,
            "stdout": out.getvalue(), "stderr": err.getvalue(),
        })
    except BaseException:
        return json.dumps({
            "ok": False, "syntax": False,
            "stdout": out.getvalue(), "stderr": traceback.format_exc(),
        })
