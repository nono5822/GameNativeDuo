#!/usr/bin/env bash
# Unattended Claude Code run on GameNative Duo, with the local Qwen worker and the Thor on adb.
#
#   duo/agents/overnight.sh duo/agents/tasks/regression-pass.md
#   duo/agents/overnight.sh duo/agents/tasks/queue/*.md      several tasks, in order, on one branch
#
# What it does, in order:
#   1. refuses to start on a dirty working tree, then creates a fresh branch agent/<date-time>
#   2. makes sure Qwen is up (starts start-qwen.sh if not, waits up to 5 minutes)
#   3. checks the Thor is on adb and keeps it awake while plugged in
#   4. runs `claude -p` once per task file (fresh context each, so long queues stay cheap), edits auto-approved, a fixed allow-list of commands,
#      and no permission prompts (anything not allowed is denied instead of waiting for you)
#      commits anything a task left uncommitted as WIP so the next task starts clean
#   5. saves each task's JSON (result, session id, cost estimate) under duo/agents/runs/
#
# Optional environment:
#   MAX_BUDGET_USD=20     stop the run at this estimated spend (useful on API billing)
#   PERMISSION_MODE=auto  let Claude Code's classifier review actions instead of the allow-list
#   NO_DEVICE=1           run without the Thor (build, unit tests and code work only)
#   THOR_WIFI=1           reach the Thor over Wi-Fi (thor-wifi.sh) instead of the USB cable

set -euo pipefail

[ $# -ge 1 ] || { echo "usage: overnight.sh <task.md>..." >&2; exit 2; }
TASK_FILES=("$@")
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

# 3. Device (ANDROID_SERIAL, if set, also steers gradle installs and every adb call in the run)
if [ -z "${NO_DEVICE:-}" ]; then
  if [ -n "${THOR_WIFI:-}" ]; then
    ANDROID_SERIAL="$("$AGENTS/thor-wifi.sh")" || { echo "Thor not reachable over Wi-Fi." >&2; exit 1; }
    export ANDROID_SERIAL
    # no cable to keep it awake: stay on whatever the power source, until the run ends
    adb shell svc power stayon true || true
    trap 'adb shell svc power stayon false >/dev/null 2>&1 || true' EXIT
  elif [ "$(adb get-state 2>/dev/null || true)" != "device" ]; then
    echo "No device on adb. Plug in the Thor, set THOR_WIFI=1, or NO_DEVICE=1 for a code-only run." >&2
    exit 1
  else
    adb shell svc power stayon usb || true
  fi
fi
git switch -c "agent/$STAMP"   # only once every check has passed

# 4. Claude Code, unattended
ALLOWED="Read,Edit,Write,Grep,Glob,mcp__qwen"
ALLOWED+=",Bash(./gradlew *),Bash(dotnet build *),Bash(adb *)"
ALLOWED+=",Bash(git status *),Bash(git diff *),Bash(git log *),Bash(git add *),Bash(git commit *)"
ALLOWED+=",Bash(git show *),Bash(git checkout -- *),Bash(git merge *),Bash(git fetch upstream *),Bash(git rm *)"
for c in status diff log show add commit; do ALLOWED+=",Bash(git -C ../GameNativeDuo-community $c *)"; done   # no push
ALLOWED+=",Bash(python3 duo/agents/triage.py *),Bash(mkdir *),Bash(ls *),Bash(sha256sum *),Bash(cmp *),Bash(cp *),Bash(jq *)"
ALLOWED+=",Bash(python3 duo/tools/*),Bash(python3 ../GameNativeDuo-community/tools/*),Bash(python3 -m pytest *)"
ALLOWED+=",Bash(curl -s https://api.gamenative.app/*)"

run_task() {
  local task="$1" name out
  name="$(basename "$task" .md)"
  out="$RUN_DIR/$name.json"
  local args=(-p "$(cat "$task")

Run folder for logs and screenshots: duo/agents/runs/$STAMP/$name/
Device available: $([ -z "${NO_DEVICE:-}" ] && echo yes || echo no).
Branch: agent/$STAMP (earlier tasks of this queue may have committed to it: see git log)."
    --output-format json
    --permission-prompts none
    --allowedTools "$ALLOWED")
  if [ -n "${PERMISSION_MODE:-}" ]; then
    args+=(--permission-mode "$PERMISSION_MODE")
  else
    args+=(--permission-mode acceptEdits)
  fi
  [ -n "${MAX_BUDGET_USD:-}" ] && args+=(--max-budget-usd "$MAX_BUDGET_USD")
  mkdir -p "$RUN_DIR/$name"
  echo "[$(date +%T)] $name..."
  claude "${args[@]}" >"$out" || echo "claude exited with $? on $name" >&2
  if [ -f "$(git rev-parse --git-dir)/MERGE_HEAD" ]; then
    echo "$name left a merge unfinished: aborted it" >&2
    git merge --abort || true
  fi
  if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    git commit -qam "WIP left uncommitted by agent task $name (review before keeping)" || true
  fi
  if command -v jq >/dev/null; then
    jq -r --arg n "$name" '"== \($n): $\(.total_cost_usd // "n/a")\n\(.result // "no result")\n"' "$out" || true
  fi
}

for task in "${TASK_FILES[@]}"; do
  run_task "$task"
done

# 5. Summary
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
