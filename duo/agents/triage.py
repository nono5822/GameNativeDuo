#!/usr/bin/env python3
"""Deterministic log triage for GameNative Duo test runs. No model, no tokens.

Reads logcat dumps, BepInEx LogOutput.log, SMAPI logs and dmesg output, and prints a short report:
known-bad patterns (with line numbers and the verbatim line), confirmations that a mod did its job,
and the plugin's `Frames (...)` performance lines folded into one table.

    python3 triage.py logcat.txt LogOutput.log            # markdown report
    python3 triage.py --json logcat.txt                   # machine-readable
    python3 triage.py --examples 10 dmesg.txt             # more example lines per finding

Exit code: 2 when a critical pattern was found, 0 otherwise, so shell scripts can gate on it.
Add patterns to RULES as you meet new failure modes (CLAUDE.md "Known issues" is the source).
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from dataclasses import dataclass, field
from pathlib import Path

# (name, severity, regex, what it means). Severity: critical | error | warn | ok
RULES: list[tuple[str, str, str, str]] = [
    ("gpu_hang", "critical", r"GPU hang detected", "A GPU context died (check the pid: DXVK work counts as the app's pid)"),
    ("gpu_fault_ban", "critical", r"gpu fault threshold exceeded", "3 hangs within 2 s: the GPU context was banned"),
    ("frozen_present", "critical", r"dequeueBuffer: attempting to exceed the max dequeued buffer count",
     "Frozen screen: usually a dead GPU context"),
    ("app_crash", "critical", r"FATAL EXCEPTION|Fatal signal \d+", "A process crashed"),
    ("anr", "critical", r"\bANR in ", "Application not responding"),
    ("dex_verify", "critical", r"Verification error", "D8/R8 verification failure (see CLAUDE.md Known issues)"),
    ("bepinex_fatal", "critical", r"^\[Fatal\s*:", "BepInEx fatal error"),
    ("bepinex_error", "error", r"^\[Error\s*:", "BepInEx error"),
    ("smapi_error", "error", r"^\[\d\d:\d\d:\d\d (?:ERROR|ALERT)\b", "SMAPI error"),
    ("harmony", "error", r"HarmonyException|Harmony\S* .*(?:failed|Failed|exception)", "A Harmony patch failed"),
    ("duo_error", "error", r"\sE\s+GNDuo\s*:", "Error logged by app.gamenative.duo"),
    ("duo_warn", "warn", r"\sW\s+GNDuo\s*:", "Warning logged by app.gamenative.duo"),
    ("bepinex_warn", "warn", r"^\[Warning\s*:", "BepInEx warning"),
    ("camera_bottom", "ok", r"Camera '.*'.*-> bottom screen", "A camera was routed to the bottom screen"),
    ("quirk_patched", "ok", r"Game quirk patched:", "A per-game quirk was applied"),
]
NOISE = [re.compile(p) for p in (r"CP: AHB bus error",)]

FRAMES = re.compile(
    r"Frames \((?P<label>[^)]*)\): (?P<fps>[\d.]+) fps average, (?P<avg>[\d.]+) ms average, "
    r"(?P<p95>[\d.]+) ms 95th percentile, (?P<worst>[\d.]+) ms worst \((?P<n>\d+) frames\); "
    r"this plugin (?P<plugin>[\d.]+) ms per frame"
)
SEVERITY_ORDER = {"critical": 0, "error": 1, "warn": 2, "ok": 3}
_COMPILED = [(n, s, re.compile(p), d) for n, s, p, d in RULES]


@dataclass
class Finding:
    rule: str
    severity: str
    meaning: str
    count: int = 0
    examples: list[dict] = field(default_factory=list)  # {file, line, text, repeats}
    _seen: dict = field(default_factory=dict, repr=False)


def _shape(text: str) -> str:
    """Same message with different numbers or hex ids counts as one example."""
    return re.sub(r"0x[0-9a-fA-F]+|\d+", "#", text)


def scan(paths: list[str], max_examples: int = 5, max_line: int = 300) -> dict:
    findings: dict[str, Finding] = {}
    frames: dict[str, list[dict]] = {}
    files = []
    for p in paths:
        path = Path(p)
        if not path.is_file():
            files.append({"file": p, "error": "not found"})
            continue
        n_lines = 0
        with path.open("r", errors="replace") as fh:
            for lineno, raw in enumerate(fh, 1):
                n_lines = lineno
                line = raw.rstrip("\n")
                if any(n.search(line) for n in NOISE):
                    continue
                m = FRAMES.search(line)
                if m:
                    row = {k: float(v) for k, v in m.groupdict().items() if k != "label"}
                    row["file"], row["line"] = path.name, lineno
                    frames.setdefault(m.group("label"), []).append(row)
                    continue
                # BepInEx lines may carry a timestamp prefix; match rules on both forms
                stripped = re.sub(r"^\d\d:\d\d:\d\d(?:\.\d+)?\s+", "", line)
                for name, sev, rx, meaning in _COMPILED:
                    if rx.search(line) or rx.search(stripped):
                        f = findings.setdefault(name, Finding(name, sev, meaning))
                        f.count += 1
                        key = _shape(line)
                        if key in f._seen:
                            f._seen[key]["repeats"] += 1
                        elif len(f.examples) < max_examples:
                            ex = {"file": path.name, "line": lineno, "text": line[:max_line], "repeats": 1}
                            f.examples.append(ex)
                            f._seen[key] = ex
                        break
        files.append({"file": str(path), "lines": n_lines})

    ordered = sorted(findings.values(), key=lambda f: (SEVERITY_ORDER[f.severity], -f.count))
    return {
        "files": files,
        "critical": any(f.severity == "critical" for f in ordered),
        "findings": [
            {"rule": f.rule, "severity": f.severity, "meaning": f.meaning, "count": f.count, "examples": f.examples}
            for f in ordered
        ],
        "frames": {label: _frame_stats(rows) for label, rows in frames.items()},
    }


def _frame_stats(rows: list[dict]) -> dict:
    med = lambda k: round(statistics.median(r[k] for r in rows), 2)
    return {
        "samples": len(rows),
        "fps_median": med("fps"),
        "fps_min": min(r["fps"] for r in rows),
        "fps_max": max(r["fps"] for r in rows),
        "p95_ms_median": med("p95"),
        "worst_ms_max": max(r["worst"] for r in rows),
        "plugin_ms_median": round(statistics.median(r["plugin"] for r in rows), 3),
    }


def hit_lines(result: dict, severities=("critical", "error")) -> dict[str, list[int]]:
    """Line numbers of example hits per file name, for building excerpts."""
    out: dict[str, list[int]] = {}
    for f in result["findings"]:
        if f["severity"] in severities:
            for ex in f["examples"]:
                out.setdefault(ex["file"], []).append(ex["line"])
    return out


def excerpt(path: str, lines: list[int], radius: int = 8, tail: int = 40) -> str:
    """Lines around each hit plus the end of the file, with line numbers. Input for a model."""
    text = Path(path).read_text(errors="replace").splitlines()
    keep: set[int] = set(range(max(0, len(text) - tail), len(text)))
    for ln in lines:
        keep.update(range(max(0, ln - 1 - radius), min(len(text), ln + radius)))
    out, prev = [], -2
    for i in sorted(keep):
        if any(n.search(text[i]) for n in NOISE):
            continue
        if i != prev + 1:
            out.append("...")
        out.append(f"{i + 1}: {text[i][:300]}")
        prev = i
    return "\n".join(out)


def render_markdown(result: dict) -> str:
    out = []
    for f in result["files"]:
        out.append(f"- `{f['file']}`: " + (f.get("error") or f"{f['lines']} lines"))
    if not result["findings"]:
        out.append("\nNo known patterns found.")
    for f in result["findings"]:
        out.append(f"\n**{f['severity'].upper()} {f['rule']}** ×{f['count']}: {f['meaning']}")
        for ex in f["examples"]:
            rep = f" (×{ex['repeats']} similar)" if ex["repeats"] > 1 else ""
            out.append(f"  - {ex['file']}:{ex['line']}{rep}: `{ex['text']}`")
    if result["frames"]:
        out.append("\n| Frames | samples | fps median (min–max) | p95 ms | worst ms | plugin ms |")
        out.append("|---|---|---|---|---|---|")
        for label, s in result["frames"].items():
            out.append(
                f"| {label} | {s['samples']} | {s['fps_median']} ({s['fps_min']}–{s['fps_max']}) | "
                f"{s['p95_ms_median']} | {s['worst_ms_max']} | {s['plugin_ms_median']} |"
            )
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("logs", nargs="+")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--examples", type=int, default=5)
    args = ap.parse_args()
    result = scan(args.logs, max_examples=args.examples)
    print(json.dumps(result, indent=2) if args.json else render_markdown(result))
    return 2 if result["critical"] else 0


if __name__ == "__main__":
    sys.exit(main())
