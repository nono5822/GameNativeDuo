Overnight regression pass on the Thor for GameNative Duo. Follow the "Local Qwen worker" rules in CLAUDE.md.

1. Build and install: `./gradlew :app:installModernDebug`. If the build fails, fix it (use `qwen_fix_build` when
   the errors sit in one file under 800 lines). Then run `./gradlew :app:testModernDebugUnitTest --tests 'app.gamenative.duo.*'`.
2. For each bundled game (Stardew Valley, Slime Rancher, Subnautica, Balatro) and the Dead Cells region view:
   launch by intent (CLAUDE.md "Debugging freezes"), wait for the game, then:
   - screenshot both screens into the run folder (`adb exec-out screencap -p -d <id> > top.png / bottom.png`);
     look at them yourself only if the logs suggest something is wrong
   - drive the bottom screen with `adb shell input -d 4 tap|swipe` through the menus the notes list as testable
   - after about two minutes save logcat (`--pid` of app.gamenative.duo), dmesg and the mod log into the run folder
   - `triage_log` them; `qwen_summarize_log` only if something failed and the cause is unclear
   - the first launch after an APK install may die at "Waiting for game to launch": retry once before calling it a bug
3. If something regressed, find the cause, fix it on this branch, rebuild, and retest that game only.
   Commit each fix separately with a message saying what broke and how you verified the fix.
4. Ask Qwen (`qwen_draft`) for unit tests covering any pure-logic code you changed; keep the ones that pass and make sense.
5. Write `duo/notes/<today>-agent-run.md` in the shape of `duo/notes/2026-10-20-device-testing.md`: results table,
   bugs found and fixed, Frames table from triage, what still needs a person. Commit it.

Do not push, do not touch upstream files outside the documented hooks, and stop after five fix attempts on the
same problem: write down what you learned instead.
