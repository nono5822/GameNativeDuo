#!/usr/bin/env python3
"""MCP server that lets Claude Code (Opus) hand token-heavy chores to the local Qwen.

Design rule: Opus passes *paths*, never file contents. This server reads the files, talks to Qwen and
returns a short result, so the bulk text never enters Opus's context.

Tools
  triage_log           deterministic scan of logs (no model)
  qwen_summarize_log   triage + Qwen's explanation of what went wrong, from excerpts around the hits
  qwen_draft           Qwen writes one new file (a unit test, a catalog entry) from a task and context files
  qwen_fix_build       build -> Qwen fixes one file -> rebuild, up to N times; restores the file on failure

Environment
  QWEN_URL      default http://127.0.0.1:9931
  QWEN_CTX      context size the server was started with (default 24576)
  DUO_REPO      repo root (default: CLAUDE_PROJECT_DIR, then the current directory)
  QWEN_BUILD_ALLOW  comma-separated command prefixes qwen_fix_build may run
                    (default "./gradlew ,dotnet build")
"""
from __future__ import annotations

import difflib
import json
import os
import re
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

try:  # mcp 2.x
    from mcp.server.mcpserver import MCPServer as _Server
except ImportError:  # mcp 1.x
    from mcp.server.fastmcp import FastMCP as _Server

sys.path.insert(0, str(Path(__file__).resolve().parent))
import triage  # noqa: E402

QWEN_URL = os.environ.get("QWEN_URL", "http://127.0.0.1:9931").rstrip("/")
QWEN_CTX = int(os.environ.get("QWEN_CTX", "24576"))
REPO = Path(os.environ.get("DUO_REPO") or os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()).resolve()
BUILD_ALLOW = [p for p in os.environ.get("QWEN_BUILD_ALLOW", "./gradlew ,dotnet build").split(",") if p.strip()]
USAGE_LOG = Path(__file__).resolve().parent / "qwen-usage.jsonl"
CHARS_PER_TOKEN = 3.2  # conservative for code and logs
MAX_FIX_LINES = 800

mcp = _Server("qwen")


class QwenUnavailable(RuntimeError):
    pass


class QwenTruncated(RuntimeError):
    """Qwen hit max_tokens: its answer (and any code in it) is cut off."""


def _log(msg: str) -> None:
    print(f"[qwen-mcp] {msg}", file=sys.stderr, flush=True)


def _tokens(text: str) -> int:
    return int(len(text) / CHARS_PER_TOKEN) + 1


def _resolve(path: str) -> Path:
    p = Path(path)
    p = (REPO / p).resolve() if not p.is_absolute() else p.resolve()
    return p


def _inside_repo(p: Path) -> bool:
    return p == REPO or REPO in p.parents


def ask_qwen(system: str, user: str, *, think: bool, max_tokens: int, tool: str) -> str:
    """One chat completion against llama.cpp's OpenAI-compatible endpoint."""
    body = {
        "model": "qwen",
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "temperature": 0.6 if think else 0.2,
        "max_tokens": max_tokens,
        "chat_template_kwargs": {"enable_thinking": think},
    }
    req = urllib.request.Request(
        f"{QWEN_URL}/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=1800) as resp:
            data = json.load(resp)
    except urllib.error.HTTPError as e:  # reachable, but refused the request (e.g. prompt over the context)
        detail = e.read().decode(errors="replace")[:300]
        raise QwenUnavailable(f"Qwen server returned HTTP {e.code}: {detail}. Do this task yourself.")
    except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
        raise QwenUnavailable(f"Qwen server at {QWEN_URL} not reachable ({e}). Start start-qwen.sh, or do this task yourself.")
    try:
        choice = data["choices"][0]
        msg = choice["message"]
    except (KeyError, IndexError, TypeError):
        raise QwenUnavailable(f"Unexpected reply from the Qwen server: {str(data)[:300]}")
    content = re.sub(r"<think>.*?</think>", "", msg.get("content") or "", flags=re.S).strip()
    usage = data.get("usage", {})
    try:
        with USAGE_LOG.open("a") as fh:
            fh.write(json.dumps({
                "ts": int(started), "tool": tool, "seconds": round(time.time() - started, 1),
                "prompt_tokens": usage.get("prompt_tokens"), "completion_tokens": usage.get("completion_tokens"),
            }) + "\n")
    except OSError:
        pass
    if choice.get("finish_reason") == "length":
        raise QwenTruncated(f"Qwen ran out of tokens ({max_tokens}) before finishing.")
    return content


def _code_block(text: str) -> str:
    blocks = re.findall(r"```[^\n`]*\n(.*?)```", text, flags=re.S)
    return max(blocks, key=len) if blocks else text.strip()


# --------------------------------------------------------------------------- tools


@mcp.tool()
def triage_log(paths: list[str], examples: int = 5) -> str:
    """Scan log files for known failure patterns and Frames stats. No model, exact, free.

    Use this first on any logcat dump, BepInEx LogOutput.log, SMAPI log or dmesg output, instead of reading
    the log yourself. Paths are relative to the repo root or absolute.
    """
    result = triage.scan([str(_resolve(p)) for p in paths], max_examples=examples)
    return triage.render_markdown(result)


@mcp.tool()
def qwen_summarize_log(paths: list[str], focus: str = "") -> str:
    """Triage the logs, then have local Qwen explain what went wrong, from excerpts around each hit.

    Returns the exact triage report plus Qwen's explanation (marked unverified). Use `focus` to say what the
    test was checking, e.g. "Slime Rancher with split at 75%: did the HUD camera reach the bottom screen?".
    Quote line numbers from the report when you act on it; check a cited line yourself before a big decision.
    """
    resolved = [str(_resolve(p)) for p in paths]
    result = triage.scan(resolved)
    report = triage.render_markdown(result)
    hits = triage.hit_lines(result)
    budget = QWEN_CTX - 2500  # room for prompt and answer
    parts = [f"Triage report:\n{report}"]
    for path in resolved:
        if not Path(path).is_file():
            continue
        ex = triage.excerpt(path, hits.get(Path(path).name, []))
        parts.append(f"\n=== {Path(path).name} (excerpts) ===\n{ex}")
    user = "\n".join(parts)
    while _tokens(user) > budget and len(user) > 2000:  # trim excerpts, keep the report
        user = user[: int(len(user) * 0.8)]
    if focus:
        user = f"What this test was checking: {focus}\n\n{user}"
    system = (
        "You read Android, Wine and mod-loader logs for a dual-screen game app (GameNative Duo). "
        "Explain what went wrong in at most 200 words. Cite line numbers like `file:123` for every claim. "
        "Say 'nothing conclusive' if the excerpts do not show a cause. Never guess beyond the excerpts."
    )
    try:
        answer = ask_qwen(system, user, think=False, max_tokens=600, tool="summarize_log")
    except (QwenUnavailable, QwenTruncated) as e:
        return f"{report}\n\n(Qwen unavailable: {e})"
    return f"{report}\n\n**Qwen's reading (unverified):**\n{answer}"


@mcp.tool()
def qwen_draft(task: str, context_paths: list[str], out_path: str, overwrite: bool = False) -> str:
    """Have local Qwen write ONE new file, such as a unit test or a catalog entry, and save it.

    Write `task` like a ticket: what the file must contain, the test framework, names to use, edge cases.
    `context_paths` are the files Qwen needs to read (the class under test, an existing test to copy the style
    of). Qwen never sees anything else. Returns the path and Qwen's note, not the file: run the build or tests
    and read the diff yourself before keeping it.
    """
    out = _resolve(out_path)
    if not _inside_repo(out):
        return f"Refused: {out} is outside the repo {REPO}."
    if out.exists() and not overwrite:
        return f"Refused: {out} exists. Pass overwrite=true to replace it (a .qwen-backup copy is kept)."
    ctx_parts = []
    for p in context_paths:
        rp = _resolve(p)
        if not rp.is_file():
            return f"Context file not found: {p}"
        ctx_parts.append(f"=== {rp.relative_to(REPO) if _inside_repo(rp) else rp} ===\n{rp.read_text(errors='replace')}")
    ctx = "\n\n".join(ctx_parts)
    need = _tokens(ctx) + _tokens(task) + 6000
    if need > QWEN_CTX:
        return (f"Too large for Qwen's {QWEN_CTX}-token context (needs ~{need}). "
                "Pass fewer or smaller context files, or do this one yourself.")
    system = (
        "You are a careful Kotlin/C# engineer on GameNative Duo. Write exactly one complete file for the task. "
        "Use only APIs that appear in the context files or the standard library and test framework named in the "
        "task. Output the file in a single fenced code block, then one or two sentences on anything uncertain."
    )
    user = f"Task:\n{task}\n\nTarget file: {out.relative_to(REPO)}\n\nContext files:\n{ctx}"
    try:
        answer = ask_qwen(system, user, think=True, max_tokens=QWEN_CTX - _tokens(user) - 500, tool="draft")
    except (QwenUnavailable, QwenTruncated) as e:  # never save a cut-off file
        return f"{e} Nothing written."
    code = _code_block(answer)
    if not code.strip():
        return "Qwen returned no code. Do this one yourself."
    note = answer.split("```")[-1].strip()[:400]
    if out.exists():
        out.with_name(out.name + ".qwen-backup").write_text(out.read_text())
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(code if code.endswith("\n") else code + "\n")
    return f"Wrote {out.relative_to(REPO)} ({len(code.splitlines())} lines). Qwen's note: {note or '(none)'}"


def _run(command: str, timeout: int = 1800) -> tuple[bool, str]:
    try:
        proc = subprocess.run(command, shell=True, cwd=REPO, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return False, f"error: build timed out after {timeout}s"
    return proc.returncode == 0, (proc.stdout or "") + (proc.stderr or "")


def _errors_for(output: str, target: Path, limit_tokens: int) -> str:
    """Compiler error lines, those naming the target file first, then a little context."""
    lines = output.splitlines()
    err_rx = re.compile(r"(^e: |\berror\b|\bError\b|: error CS\d+|FAILED|Unresolved reference|Type mismatch)")
    hits = [i for i, l in enumerate(lines) if err_rx.search(l)]
    hits.sort(key=lambda i: (target.name not in lines[i], i))
    out, used = [], 0
    for i in hits:
        chunk = "\n".join(lines[max(0, i - 1): i + 3])
        if used + _tokens(chunk) > limit_tokens:
            break
        out.append(chunk)
        used += _tokens(chunk)
    return "\n---\n".join(out) or "\n".join(lines[-60:])


@mcp.tool()
def qwen_fix_build(command: str, file_path: str, max_attempts: int = 3) -> str:
    """Run a build, and while it fails let local Qwen fix ONE file, then rebuild. Up to `max_attempts`.

    Use when you already know which file the errors are in (e.g. after editing an adapter against a new game
    version). `command` must start with an allowed prefix such as `./gradlew ` or `dotnet build`. If the
    build still fails at the end, the file is restored exactly. Returns pass/fail, a diff, and the remaining
    errors, never the full build log. Files over 800 lines are refused: fix those yourself.
    """
    if not any(command.startswith(p) for p in BUILD_ALLOW):
        return f"Refused: command must start with one of {BUILD_ALLOW}."
    if any(tok in command for tok in (";", "&&", "||", "|", "`", "$(")):
        return "Refused: one plain build command only, no chaining."
    target = _resolve(file_path)
    if not (_inside_repo(target) and target.is_file()):
        return f"Refused: {file_path} is not a file in the repo."
    original = target.read_text()
    if len(original.splitlines()) > MAX_FIX_LINES:
        return f"Refused: {file_path} has more than {MAX_FIX_LINES} lines; fix it yourself."
    if 2 * _tokens(original) + 5000 > QWEN_CTX:
        return f"Refused: {file_path} is too large for Qwen's {QWEN_CTX}-token context."

    ok, output = _run(command)
    if ok:
        return "Build already passes. Nothing changed."
    system = (
        "You fix compile errors in one file of GameNative Duo. Change only what the errors require; keep "
        "everything else byte-for-byte. Output the complete corrected file in one fenced code block, nothing else."
    )
    current = original
    for attempt in range(1, max_attempts + 1):
        errors = _errors_for(output, target, limit_tokens=max(1500, QWEN_CTX - 2 * _tokens(current) - 3000))
        user = f"Build command: {command}\n\nErrors:\n{errors}\n\nFile {target.relative_to(REPO)}:\n```\n{current}```"
        try:
            # thinking tokens count against max_tokens, so give it everything the context has left
            answer = ask_qwen(system, user, think=True, max_tokens=max(2000, QWEN_CTX - _tokens(user) - 500),
                              tool="fix_build")
        except QwenTruncated as e:
            _log(f"attempt {attempt}: {e}")
            continue
        except QwenUnavailable as e:
            target.write_text(original)
            return f"{e}\nFile restored."
        fixed = _code_block(answer)
        ratio = len(fixed) / max(1, len(current))
        if not fixed.strip() or not 0.7 <= ratio <= 1.3:
            _log(f"attempt {attempt}: rejected output (size ratio {ratio:.2f})")
            continue
        current = fixed if fixed.endswith("\n") else fixed + "\n"
        target.write_text(current)
        ok, output = _run(command)
        _log(f"attempt {attempt}: build {'passed' if ok else 'failed'}")
        if ok:
            diff = "".join(difflib.unified_diff(
                original.splitlines(True), current.splitlines(True),
                fromfile=f"a/{target.relative_to(REPO)}", tofile=f"b/{target.relative_to(REPO)}"))
            if _tokens(diff) > 3000:
                diff = diff[: int(3000 * CHARS_PER_TOKEN)] + "\n... (diff truncated; run git diff)"
            return f"Build passes after {attempt} attempt(s). Review the change:\n```diff\n{diff}```"
    target.write_text(original)
    return (f"Still failing after {max_attempts} attempt(s); {file_path} restored to its original content.\n"
            f"Remaining errors:\n{_errors_for(output, target, limit_tokens=1500)}")


if __name__ == "__main__":
    _log(f"repo={REPO} qwen={QWEN_URL} ctx={QWEN_CTX}")
    mcp.run()
