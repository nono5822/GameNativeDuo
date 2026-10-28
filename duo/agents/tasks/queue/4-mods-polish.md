# Task 4: regression pass after the upstream merge, then polish every other mod

Read `duo/agents/tasks/queue/RULES.md`.

1. Run the full procedure of `duo/agents/tasks/regression-pass.md` (all bundled games, Dead Cells/Isaac region view)
   on this branch, which now has the upstream merge and the new mod explorer. Hollow Knight was polished in task 1:
   here only check it still works.
2. Then, per mod (Stardew Valley, Balatro, Slime Rancher, Subnautica, the Isaac and Dead Cells region presets, the
   community Silksong and Risk of Rain 2 packages), take the "open / next" items from its newest note in `duo/notes/`
   and fix the most valuable ones, in this order of importance: anything broken or overlapping on the bottom screen;
   anything the user cannot customize that they would want to (UI layout parity with what HK got); performance cost;
   rough edges in first-launch experience (e.g. Unity's remembered resolution after turning Dual screen on: CLAUDE.md
   "Known issues" suggests `-screen-width/-screen-height` or the registry keys: implement it if it is safe).
3. Give each mod a good default `ui-layout.json` preset if it lacks one.
4. Update each mod's note with what changed and what is still open.
