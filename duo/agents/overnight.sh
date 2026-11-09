#!/usr/bin/env bash
# Unattended Claude Code run on GameNative Duo, with the local Qwen worker and the Thor on adb.
#
#   duo/agents/overnight.sh duo/agents/tasks/regression-pass.md
#   duo/agents/overnight.sh 8 9 10                           GitHub issues (label `agent task`), in order, on one branch
#
# What it does, in order:
#   1. refuses to start on a dirty working tree, then creates a fresh branch agent/<date-time>
#   2. makes sure Qwen is up (starts start-qwen.sh if not, waits up to 5 minutes)
#   3. checks the Thor is on adb and keeps it awake while plugged in
#   4. runs `claude -p` once per task file (fresh context each, so long queues stay cheap), edits auto-approved, a fixed allow-list of commands,
#      and no permission prompts (anything not allowed is denied instead of waiting for you).
#      On a usage limit it sleeps until the reset time in the message and resumes the same session (every 5 h
#      window until the queue is done, at most MAX_LIMIT_WAITS times); API errors are retried with a back-off
#      commits anything a task left uncommitted as WIP so the next task starts clean
#   5. saves each task's JSON (result, session id, cost estimate) under duo/agents/runs/
#
# Optional environment:
#   MAX_BUDGET_USD=20     stop the run at this estimated spend (useful on API billing)
#   PERMISSION_MODE=auto  let Claude Code's classifier review actions instead of the allow-list
#   NO_DEVICE=1           run without the Thor (build, unit tests and code work only)
#   THOR_WIFI=1           reach the Thor over Wi-Fi (thor-wifi.sh) instead of the USB cable
#   MIN_BATTERY=10        stop before a task or a resume when the Thor is below this
#   MAX_LIMIT_WAITS=12    usage-limit resets to wait through in one run
# Detached and keeping the PC awake: duo/agents/start-queue.sh <issue number | task.md>...

set -euo pipefail

[ $# -ge 1 ] || { echo "usage: overnight.sh <issue number | task.md>..." >&2; exit 2; }
TASK_FILES=("$@")
REPO="$(git rev-parse --show-toplevel)"
AGENTS="$REPO/duo/agents"
STAMP="$(date +%F-%H%M)"
START_TS="$(date +%s)"
RUN_DIR="$AGENTS/runs/$STAMP"
QWEN_URL="${QWEN_URL:-http://127.0.0.1:9931}"
cd "$REPO"
mkdir -p "$RUN_DIR"

# Issue numbers become task files: the issue's title and body, plus the rules every task follows.
GH_REPO="$(git remote get-url origin | sed -E 's#^.*github\.com[:/]##; s#\.git$##')"
for i in "${!TASK_FILES[@]}"; do
  n="${TASK_FILES[$i]#\#}"
  [[ "$n" =~ ^[0-9]+$ ]] || continue
  f="$RUN_DIR/issue-$n.md"
  gh issue view "$n" -R "$GH_REPO" --json number,title,body \
    --jq '"# Issue #\(.number): \(.title)\n\nRead duo/agents/tasks/RULES.md first. Mention the issue in commits (Refs #\(.number)).\n\n\(.body)"' >"$f" ||
    { echo "Could not read issue #$n (gh auth status?)" >&2; exit 2; }
  TASK_FILES[$i]="$f"
done

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
ALLOWED+=",Bash(python3 -I ../GameNativeDuo-community/tools/*),Bash(python3 -I $(dirname "$REPO")/GameNativeDuo-community/tools/*)"
ALLOWED+=",Bash(curl -s https://api.gamenative.app/*)"

MIN_BATTERY="${MIN_BATTERY:-10}"
MAX_LIMIT_WAITS="${MAX_LIMIT_WAITS:-12}"   # usage-limit windows to wait through in one run (5 h each: 2.5 days)
LIMIT_WAITS=0
battery() { adb shell dumpsys battery 2>/dev/null | awk '/^ *level:/ { print $2; exit }'; }

# True (0) when the Thor is fine to work on (or no device is used).
device_ok() {
  [ -n "${NO_DEVICE:-}" ] && return 0
  local level
  level="$(battery)"
  if [ -z "$level" ] || [ "$level" -lt "$MIN_BATTERY" ]; then
    echo "[$(date +%T)] Thor battery ${level:-unreachable}% (< $MIN_BATTERY%): stopping the queue." >&2
    return 1
  fi
}

# "You've hit your 5-hour limit · resets 2:10pm (America/Halifax)": sleeps until 2 min after the reset.
# Returns 1 after MAX_LIMIT_WAITS waits; an unreadable reset time means looking again in an hour.
wait_for_reset() {
  local msg="$1" when tz target now
  LIMIT_WAITS=$((LIMIT_WAITS + 1))
  if [ "$LIMIT_WAITS" -gt "$MAX_LIMIT_WAITS" ]; then
    echo "[$(date +%T)] usage limit hit $LIMIT_WAITS times: stopping (MAX_LIMIT_WAITS=$MAX_LIMIT_WAITS)." >&2
    return 1
  fi
  when="$(sed -n 's/.*resets \(.*\)$/\1/p' <<<"$msg")"
  tz="$(sed -n 's/.*(\([A-Za-z_]*\/[A-Za-z_/]*\)).*/\1/p' <<<"$when")"
  when="$(sed 's/ *([^)]*)//; s/,//g' <<<"$when")" # "Oct 30, 9am" -> "Oct 30 9am" (GNU date reads that)
  now="$(date +%s)"
  target=""
  [ -n "$when" ] && target="$(TZ="${tz:-$(date +%Z)}" date -d "$when" +%s 2>/dev/null || true)"
  if [ -z "$target" ]; then
    target=$((now + 3600)) # unreadable reset time: look again in an hour
  elif [ "$target" -le "$now" ]; then
    target=$((target + 86400)) # "6:10am" when it is already past 6:10 today
  fi
  target=$((target + 120))
  echo "[$(date +%T)] usage limit; waiting until $(date -d "@$target" +'%a %H:%M') (reset: ${when:-unknown}${tz:+ $tz})."
  sleep $((target - now))
}

run_task() {
  local task="$1" name out session="" attempt=0 errors=0 result
  name="$(basename "$task" .md)"
  mkdir -p "$RUN_DIR/$name"
  local common=(--output-format json --permission-prompts none --allowedTools "$ALLOWED"
    --add-dir "$(dirname "$REPO")/GameNativeDuo-community")   # edits there are auto-accepted too
  if [ -n "${PERMISSION_MODE:-}" ]; then
    common+=(--permission-mode "$PERMISSION_MODE")
  else
    common+=(--permission-mode acceptEdits)
  fi
  [ -n "${MAX_BUDGET_USD:-}" ] && common+=(--max-budget-usd "$MAX_BUDGET_USD")
  while :; do
    attempt=$((attempt + 1))
    out="$RUN_DIR/$name.$attempt.json"
    local args
    if [ -z "$session" ]; then
      args=(-p "$(cat "$task")

Run folder for logs and screenshots: duo/agents/runs/$STAMP/$name/
Device available: $([ -z "${NO_DEVICE:-}" ] && echo yes || echo no).
Branch: agent/$STAMP (earlier tasks of this queue may have committed to it: see git log).")
    else
      # Same session, so the task keeps its context; the device may have changed while waiting.
      args=(-p "Continue this task from where you stopped: a usage limit (or an API error) interrupted you, possibly
hours ago. First check git status/log and the device state (game still running? test mode files?), then carry on." --resume "$session")
    fi
    echo "[$(date +%T)] $name (attempt $attempt${session:+, resuming $session})..."
    claude "${args[@]}" "${common[@]}" >"$out" || true
    result="$(jq -r '.result // ""' "$out" 2>/dev/null || true)"
    local sid
    sid="$(jq -r '.session_id // empty' "$out" 2>/dev/null || true)"
    [ -n "$sid" ] && session="$sid"
    jq -r --arg n "$name" '"== \($n): $\(.total_cost_usd // "n/a")\n\(.result // "no result")\n"' "$out" 2>/dev/null || cat "$out"
    if grep -qi "hit your .*limit\|usage limit\|limit · resets" <<<"$result"; then
      wait_for_reset "$result" || return 1
      device_ok || return 1
      continue
    fi
    if [ "$(jq -r '.is_error // false' "$out" 2>/dev/null)" = "true" ] || [ ! -s "$out" ]; then
      errors=$((errors + 1))
      if [ "$errors" -le 3 ]; then
        echo "[$(date +%T)] $name: error ($result); retrying in $((errors * 5)) min." >&2
        sleep $((errors * 300))
        device_ok || return 1
        continue
      fi
      echo "[$(date +%T)] $name: giving up after $errors errors." >&2
    fi
    break
  done
  if [ -f "$(git rev-parse --git-dir)/MERGE_HEAD" ]; then
    echo "$name left a merge unfinished: aborted it" >&2
    git merge --abort || true
  fi
  if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    git commit -qam "WIP left uncommitted by agent task $name (review before keeping)" || true
  fi
}


for task in "${TASK_FILES[@]}"; do
  device_ok || break
  run_task "$task" || break
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
