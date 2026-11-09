#!/usr/bin/env bash
# Starts overnight.sh in the background, detached from the terminal (closing it, or the Claude Code session that
# started it, does not stop the run), with the PC kept awake while it runs (it may wait through several 5 h usage
# windows). Same arguments and environment as overnight.sh.
#
#   duo/agents/start-queue.sh 8 9               GitHub issues #8 then #9 (a task .md path works too)
#   tail -f duo/agents/runs/queue.log        follow it
#   kill "$(cat duo/agents/runs/queue.pid)"  stop it (the current task's work is left for you to commit or drop)

set -euo pipefail

[ $# -ge 1 ] || { echo "usage: start-queue.sh <issue number | task.md>..." >&2; exit 2; }
AGENTS="$(cd "$(dirname "$0")" && pwd)"
RUNS="$AGENTS/runs"
mkdir -p "$RUNS"
if [ -f "$RUNS/queue.pid" ] && kill -0 "$(cat "$RUNS/queue.pid")" 2>/dev/null; then
  echo "A queue is already running (pid $(cat "$RUNS/queue.pid")); see $RUNS/queue.log" >&2
  exit 1
fi

inhibit=()
command -v systemd-inhibit >/dev/null && inhibit=(systemd-inhibit --what=sleep:idle --who=GameNativeDuo --why="agent queue")
setsid nohup "${inhibit[@]}" "$AGENTS/overnight.sh" "$@" >"$RUNS/queue.log" 2>&1 </dev/null &
echo $! >"$RUNS/queue.pid"
echo "Queue started (pid $!). Follow: tail -f $RUNS/queue.log"
