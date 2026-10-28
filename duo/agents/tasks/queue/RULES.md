# Rules for every queued task (read this first)

The queue runs in file order on one branch, each task in a fresh session: `duo/agents/overnight.sh duo/agents/tasks/queue/[0-9]*.md`.
Earlier tasks may have committed already: start with `git log --oneline -15` and the newest `duo/notes/` file.

## Quality bar
- Act like a very cautious senior developer. The user wants the best dual-screen experience on any handheld and the
  best toolkit for modders; ship less rather than ship something half-working.
- Every change: build passes, the duo unit tests pass, and what the user sees is checked on the Thor (screenshots of
  both screens, looked at by you) whenever a device is available. Nothing on the bottom screen may overlap.
- Follow CLAUDE.md: the golden rule (new files, one-line `// GameNativeDuo` hooks), the performance rules
  (nothing on the CPU per frame, zero cost when a feature is off), catch-and-log at every entry point.
- UX: controller-first (the game owns the controller, but app screens must be fully usable with the D-pad),
  touch targets at least 48 dp, readable at arm's length on 3.9", no dead ends, every destructive action undoable.
- Settings the user can change go in the GameNative Duo tab or the UI layout editor, never only in a config file.

## Token economy (the user pays for these runs)
- **Using Qwen is required, not optional**: the user checks `qwen-usage.jsonl` after each run, and every run so far
  made 0 Qwen calls. Every log you would otherwise open goes through `triage_log` (and `qwen_summarize_log` when
  unclear); every new unit test file, doc table, template or catalog entry is drafted with `qwen_draft` first.
- Shell: one command per call where possible. Never `cd dir && ...`: use `git -C ../GameNativeDuo-community ...`
  and `python3 ../GameNativeDuo-community/tools/<script> ...` (both allowed); edit that repo's files directly.
- Never read a long log: `triage_log` first, `qwen_summarize_log` only if the cause is unclear (CLAUDE.md "Local Qwen worker").
- Delegate to Qwen what suits it: unit tests for pure logic, single-file compile fixes, boilerplate (catalog entries,
  docs tables, templates). Always review its diff before committing.
- Read files with offset/limit around what you need; use Grep before Read; do not re-read files you just edited.
- Stop a line of investigation after five failed attempts and write down what you learned.

## Safety
- **Battery**: before every game launch and at least every 15 minutes of device work, read
  `adb shell dumpsys battery | grep level`. Below 10% (or the device gone from adb): stop the game cleanly, remove
  any test-mode files (e.g. `hk-test.txt`), verify save checksums, commit your work (WIP commits are fine, say so),
  write the run-note section with where you stopped and what is left, and end the task.
- Real saves: back up and checksum a game's save before launching it, prefer each game's test mode, verify the
  checksum after. Never unlock achievements (GameNative uploads them live): seeded/test modes only.
- Never push, never force anything, never touch DRM. Upstream files only through documented hooks (update the
  CLAUDE.md table when you add one).
- Commit each logical change separately: what changed, why, how it was verified.

## End of task
Append a section for this task to `duo/notes/<today>-agent-run.md` (create it in the shape of
`duo/notes/2026-10-20-device-testing.md`): what was done, how it was verified (with screenshot paths), what failed,
what needs the user. Commit it. Keep the final message under 15 lines.
