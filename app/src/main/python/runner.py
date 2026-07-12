"""Coder-feature script runner. Executes one LLM-generated script and returns
a JSON envelope {ok, stdout, stderr, syntax} — never raises across the JNI
boundary. The process-level sandbox + kill-on-timeout is the real wall
(Kotlin side); the import/open gate runs before code reaches here.

Two runtime guards back the Kotlin-side textual gate (which string tricks can
dodge): the exec globals get a builtins dict without open/eval/exec/…, and
__import__ re-checks the same stdlib allowlist. Not a sandbox either — but the
gate now holds at the execution layer, not just on source text.

For cross-note ("meta") tasks the host writes every note to a private cache
file and passes its path; the runner loads it into a `notes` global the script
can read (the script itself still can't open files — the gate blocks that).
"""
import builtins as _builtins
import io
import json
import traceback
from contextlib import redirect_stdout, redirect_stderr

# Keep in sync with CoderHarness.IMPORT_ALLOWLIST.
_ALLOWED_IMPORTS = {
    "math", "statistics", "datetime", "json", "re", "itertools", "functools",
    "collections", "random", "string", "textwrap", "decimal", "fractions",
    "heapq", "bisect", "calendar", "unicodedata", "typing", "dataclasses",
    "enum", "operator", "copy", "difflib", "zoneinfo",
}

# The generated script has no legitimate use for any of these; imports inside
# stdlib modules are unaffected (they resolve against the real builtins).
_REMOVED_BUILTINS = {"open", "eval", "exec", "compile", "input", "breakpoint", "exit", "quit"}

# Binder caps a Messenger reply around 1 MB — an over-printing script must be
# truncated here or the reply never arrives and the run fakes a timeout.
_STDOUT_CAP = 200_000
_STDERR_CAP = 20_000

_REAL_IMPORT = _builtins.__import__


def _guarded_import(name, globals=None, locals=None, fromlist=(), level=0):
    root = name.partition(".")[0]
    if root not in _ALLOWED_IMPORTS:
        raise ImportError(f"import of '{root}' is not allowed")
    return _REAL_IMPORT(name, globals, locals, fromlist, level)


def _safe_builtins():
    d = {k: getattr(_builtins, k) for k in dir(_builtins) if k not in _REMOVED_BUILTINS}
    d["__import__"] = _guarded_import
    return d


def _cap(s, limit):
    return s if len(s) <= limit else s[:limit] + "\n…[output truncated]"


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
            "stderr": _cap(traceback.format_exc(limit=0), _STDERR_CAP),
        })
    try:
        with redirect_stdout(out), redirect_stderr(err):
            exec(compiled, {"__name__": "__main__", "notes": notes, "__builtins__": _safe_builtins()})
        return json.dumps({
            "ok": True, "syntax": False,
            "stdout": _cap(out.getvalue(), _STDOUT_CAP),
            "stderr": _cap(err.getvalue(), _STDERR_CAP),
        })
    except BaseException:
        return json.dumps({
            "ok": False, "syntax": False,
            "stdout": _cap(out.getvalue(), _STDOUT_CAP),
            "stderr": _cap(traceback.format_exc(), _STDERR_CAP),
        })
