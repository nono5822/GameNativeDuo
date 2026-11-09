# Task 2: bring the fork up to date with upstream GameNative, keeping every Duo feature

Read `duo/agents/tasks/queue/RULES.md`. The fork is ~73 commits behind `upstream/master` (merge base 2026-10-13).

1. `git fetch upstream --tags`. Target: the newest upstream release tag (`v1.4.0-prerelease` on 2026-10-28; take a
   newer one if it exists, a stable one over a prerelease of the same version). **Merge, never rebase** (CLAUDE.md).
2. Before merging, list what upstream changed in every file of CLAUDE.md's "Every upstream file we modify" table
   (`git diff <base>..<tag> --stat -- <files>`), and grep the incoming code for new hardcoded
   `/data/data/app.gamenative` / `app.gamenative/` paths, new uses of `Container` fields we hook, renderer changes in
   `app/src/main/cpp/winlator/` (they require rebuilding `libvulkan_renderer.so` per `duo/native/README.md`), Gradle /
   AGP / D8 changes (the R8 pin in the root `build.gradle.kts` exists for a reason: re-check the VerifyError).
3. Merge. Resolve conflicts so upstream's new behaviour and our `// GameNativeDuo` hooks both survive; never drop a
   hook silently. New hardcoded paths go through `AppPaths`.
4. Verify: `./gradlew :app:assembleModernDebug`, duo unit tests, install, `adb shell cmd package compile -m verify -f app.gamenative.duo`
   with no `Verification error` in logcat, then a smoke test on the Thor: launch Stardew, Balatro and Hollow Knight
   with Dual screen on (save rules apply), both screens screenshotted, triage the logs. If the renderer changed,
   rebuild the native library and check the bottom screen output and region view (Dead Cells or Isaac).
5. If the build cannot be made to pass, or a Duo feature breaks and you cannot fix it within the five-attempt rule,
   `git merge --abort` and instead write in the run note exactly which upstream commits conflict and why.
6. Update the CLAUDE.md upstream-files table and "Known issues" with anything that changed (e.g. upstream unit tests
   that now assert paths, new upstream files we hook).
