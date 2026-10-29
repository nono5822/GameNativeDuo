# Task 13: cross-review follow-ups (HK, upstream merge, mod hub)

Owner: Opus (items 1-2 need the Thor), Codex (3-8) · Thor needed: for 1-2 · Depends on: —

Read `duo/agents/tasks/queue/RULES.md`. A Sonnet review of branches `agent/2026-10-28-1007` and `agent/2026-10-28-1446`
(task 01 of queue 2026-10-28-2157) found these. The three medium undo/crash findings were fixed there (see
`duo/notes/2026-10-28-agent-run.md`). Confirm each item below in the code before changing anything; drop it with a
note if it does not hold.

1. **Mod explorer, gamepad A (medium, unconfirmed)**: `ModExplorer.kt` (~289-303, 525-636). `gamepadClick` sits on the
   whole card, so A on an inner button (Use / Update / Apply) may open the detail page instead of acting, and the
   detail page may have no A handler. Test with real gamepad A on the Thor; fix like `DualScreenToggle` (A handled on
   each focusable action).
2. **Settings packages and Gamescope (medium)**: `CommunityPackages.kt` (~136-157) turns `screenSplit` on for a
   `duo-settings` package without `DuoModInstaller`'s Gamescope -> x11 switch and its restore, so on a new Bionic
   container the switch reads On and nothing splits. Same switch + undo field (`previousDisplayServer`), or a warning.
3. `LiveMap.cs` (~563, 595): `Areas()` allocates a 14-element array every frame in Area zoom. Cache it per `_map`.
4. `LiveMap.cs` (~330-337): with no free layer, `Show()` retries every frame (warning log + `Camera.allCameras`
   allocation). Back off (e.g. once a second).
5. `WorldOnTop.cs` (~22, 63): `GetComponent<Camera>()` every LateUpdate; `_forced` is dead. Cache, remove.
6. `TestHarness.cs` (~46): a leftover `hk-test.txt` silently disables saving and makes the Knight invincible. Require
   a marker line inside the file, and show an on-screen "TEST MODE" note.
7. Community index replay (low): the signed index has no freshness check, so an old signed file can be served.
   Refuse a game file whose signed `generated` date is older than the cached copy (the builder already writes it in
   the catalog; add it to game files too in the community repo's `duo_index.py`).
8. Community downloads (low): `CommunityPackages.kt` (~105-122) buffers whole files and ignores the declared `size`.
   Stream to a temp file and stop past `size` (or the 20 MB mod limit).

Also seen during task 01: Subnautica's installed `GameNativeDuo.UnitySplit.dll` no longer matched its undo record's
hash (so it counted as "changed by the user" and was backed up on re-enable). Find out whether
`DuoModInstaller.refreshIfUpdated` replaces files without updating the record's sha256.

Unverified by the reviewer (its shell was denied): the merge commit's conflict hunks (`git show --cc 12aaa4449`).
Checked in task 01: the merge did not touch `app/src/main/cpp/winlator/` (no `.so` rebuild needed); it changed
`VulkanRenderer.java`, whose duo hooks are present (8 matches), but the device test of task 2 is the real proof.
