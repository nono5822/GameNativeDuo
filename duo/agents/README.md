# Local Qwen worker for GameNative Duo

Opus plans, decides and reviews. Qwen3.8-27B on the RX 9070 XT reads the logs and drafts the routine code, so
bulk text stays out of Opus's context. Plain scripts do everything a pattern can do, for free.

```
claude -p (Opus) ── MCP ──> qwen_mcp.py ──> llama serve (Qwen, 127.0.0.1:9931)
      │                         └──> triage.py (no model)
      └── adb ──> AYN Thor
```

## Files

| File | What it is |
|---|---|
| `start-qwen.sh` | Starts Qwen: your friend's config with q8_0 KV cache, 24k context, localhost only |
| `triage.py` | Deterministic log scanner: GPU hangs, crashes, dex verify, BepInEx/SMAPI/Harmony errors, `Frames` table |
| `qwen_mcp.py` | MCP server: `triage_log`, `qwen_summarize_log`, `qwen_draft`, `qwen_fix_build` |
| `overnight.sh` | Unattended run: fresh branch, Qwen and Thor checks, `claude -p`, cost and Qwen usage summary |
| `tasks/regression-pass.md` | Example task: regression pass across the bundled games |

## Setup (once per PC)

The server is already registered in the repo's `.mcp.json` and the delegation rules are in `CLAUDE.md`
("Local Qwen worker"). On a new machine:

1. `pip install mcp` (Python 3.10+). `jq` is optional, for the run summary.
2. Start Qwen (`duo/agents/start-qwen.sh`) and check `curl localhost:9931/health`. If it runs out of VRAM, lower
   `QWEN_CTX` before switching the cache back to q4_0, and keep `QWEN_CTX` in `.mcp.json` in sync.
3. Open Claude Code in the repo and run `/mcp`: the `qwen` server should be connected. After editing
   `qwen_mcp.py`, reconnect it there.

Run output (`runs/`) and `qwen-usage.jsonl` stay local (gitignored).

## Before trusting it overnight

In an interactive session, ask Opus to:
- `triage_log` and `qwen_summarize_log` a real Slime Rancher `LogOutput.log` plus logcat
- `qwen_draft` unit tests for `ScreenCalibration` and run them
- break something on purpose in `duo/mods-src/stardew/.../Patches.cs` and `qwen_fix_build` it

and grade what Qwen did. If its code fails, keep it to log summaries (that is most of the savings anyway).

## Running

```bash
duo/agents/overnight.sh duo/agents/tasks/regression-pass.md          # with the Thor plugged in
NO_DEVICE=1 duo/agents/overnight.sh my-refactor-task.md               # code-only
MAX_BUDGET_USD=15 duo/agents/overnight.sh ...                          # spend cap on API billing
```

In the morning: read the summary it printed, `duo/notes/<date>-agent-run.md`, and
`git log --oneline master..agent/<stamp>`. Nothing is pushed; merge what you like.

## Notes

- Claude Code's built-in subagents run Claude models; Qwen is reached only through this MCP server.
- `overnight.sh` uses `acceptEdits` plus an allow-list (gradle, dotnet build, adb, local git, triage). Anything
  else is denied rather than waiting for you. `PERMISSION_MODE=auto` swaps the list for Claude Code's classifier.
- `adb *` is allowed broadly because device testing needs it; the agent can therefore run any command on the Thor.
- `qwen_fix_build` only runs commands starting with `./gradlew ` or `dotnet build` (`QWEN_BUILD_ALLOW`), refuses
  files over 800 lines, and restores the file exactly if the build still fails.
- `qwen-usage.jsonl` records the tokens Qwen handled per call, so you can see what it actually saved.
