#!/usr/bin/env bash
# Unattended Claude Code run on GameNative Duo, with the local Qwen worker and the Thor on adb.
#
#   duo/agents/overnight.sh duo/agents/tasks/regression-pass.md
#
# What it does, in order:
#   1. refuses to start on a dirty working tree, then creates a fresh branch agent/<date-time>
#   2. makes sure Qwen is up (starts start-qwen.sh if not, waits up to 5 minutes)
#   3. checks the Thor is on adb and keeps it awake while plugged in
#   4. runs `claude -p` with the task file, edits auto-approved, a fixed allow-list of commands,
#      and no permission prompts (anything not allowed is denied instead of waiting for you)
#   5. saves the run's JSON (result, session id, cost estimate) under duo/agents/runs/
#
# Optional environment:
#   MAX_BUDGET_USD=20     stop the run at this estimated spend (useful on API billing)
#   PERMISSION_MODE=auto  let Claude Code's classifier review actions instead of the allow-list
#   NO_DEVICE=1           run without the Thor (build, unit tests and code work only)

set -euo pipefail

TASK_FILE="${1:?usage: overnight.sh <task.md>}"
REPO="$(git rev-parse --show-toplevel)"
AGENTS="$REPO/duo/agents"
STAMP="$(date +%F-%H%M)"
START_TS="$(date +%s)"
RUN_DIR="$AGENTS/runs/$STAMP"
QWEN_URL="${QWEN_URL:-http://127.0.0.1:9931}"
cd "$REPO"
mkdir -p "$RUN_DIR"

# 1. clean tree, own branch
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Working tree has uncommitted changes; commit or stash them first." >&2
  exit 1
fi

# 2. Qwen
if ! curl -sf "$QWEN_URL/health" >/dev/null; then
  echo "Starting Qwen..."
  nohup "$AGENTS/start-qwen.sh" >"$RUN_DIR/qwen-server.log" 2>&1 &
  for _ in $(seq 60); do
    curl -sf "$QWEN_URL/health" >/dev/null && break
    sleep 5
  done
fi
if curl -sf "$QWEN_URL/health" >/dev/null; then
  echo "Qwen is up."
else
  echo "Qwen did not come up (see $RUN_DIR/qwen-server.log). Continuing: Claude will do the work itself." >&2
fi

# 3. Device
if [ -z "${NO_DEVICE:-}" ]; then
  if [ "$(adb get-state 2>/dev/null || true)" != "device" ]; then
    echo "No device on adb. Plug in the Thor (or set NO_DEVICE=1 for a code-only run)." >&2
    exit 1
  fi
  adb shell svc power stayon usb || true
fi
git switch -c "agent/$STAMP"   # only once every check has passed

# 4. Claude Code, unattended
ALLOWED="Read,Edit,Write,mcp__qwen"
ALLOWED+=",Bash(./gradlew *),Bash(dotnet build *),Bash(adb *)"
ALLOWED+=",Bash(git status *),Bash(git diff *),Bash(git log *),Bash(git add *),Bash(git commit *)"
ALLOWED+=",Bash(python3 duo/agents/triage.py *),Bash(mkdir *),Bash(ls *)"

ARGS=(-p "$(cat "$TASK_FILE")

Run folder for logs and screenshots: duo/agents/runs/$STAMP/
Device available: $([ -z "${NO_DEVICE:-}" ] && echo yes || echo no)."
  --output-format json
  --permission-prompts none
  --allowedTools "$ALLOWED")
if [ -n "${PERMISSION_MODE:-}" ]; then
  ARGS+=(--permission-mode "$PERMISSION_MODE")
else
  ARGS+=(--permission-mode acceptEdits)
fi
[ -n "${MAX_BUDGET_USD:-}" ] && ARGS+=(--max-budget-usd "$MAX_BUDGET_USD")

echo "Running Claude Code on branch agent/$STAMP..."
claude "${ARGS[@]}" >"$RUN_DIR/claude.json" || echo "claude exited with $?" >&2

# 5. Summary
if command -v jq >/dev/null; then
  jq -r '"Cost estimate: $\(.total_cost_usd // "n/a")\n\n\(.result // "no result")"' "$RUN_DIR/claude.json"
fi
if [ -f "$AGENTS/qwen-usage.jsonl" ]; then
  python3 - "$AGENTS/qwen-usage.jsonl" "$START_TS" <<'EOF'
import json, sys
since = int(sys.argv[2])
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
rows = [r for r in rows if r.get("ts", 0) >= since]
p = sum(r.get("prompt_tokens") or 0 for r in rows)
c = sum(r.get("completion_tokens") or 0 for r in rows)
print(f"Qwen this run: {len(rows)} calls, {p:,} tokens read, {c:,} written (kept out of Opus's context).")
EOF
fi
echo "Run saved in $RUN_DIR. Review: git log --oneline master..agent/$STAMP"
