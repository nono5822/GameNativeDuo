# Task 5: the best toolkit for dual-screen modders

Read `duo/agents/tasks/queue/RULES.md`, `duo/docs/engine-adapter-contract.md`, `duo/docs/community-packages.md`,
and the community repo's `CONTRIBUTING.md`. Goal: a typical modder makes a working dual-screen layout or mod for a new
game in minutes, with no code; a skilled modder gets full control and good diagnostics.

First write `duo/docs/modder-toolkit.md` (short): who does what at each skill level, what exists, what is missing,
and the plan below adjusted to what you find. Then implement in this order, stopping cleanly when time runs out:

1. **No-code path** (typical modder): region-view presets and UI layouts made entirely in the PC editor
   (`duo/pc/`, `assets/duo/pc-editor/index.html`) and shared as a community package in one flow (already partly
   there: Share). Fill the gaps: create a region preset for a game that has none, name it, test it live, share it.
2. **Developer mode** (a GameNative Duo setting, off by default, zero cost when off): an overlay on both screens with
   element/region bounds and names, the bridge's live traffic (message types, rate, last values, errors), the mod's
   per-frame cost, and live reload of `ui.json`/`layout.json`. Reachable from the two-finger menu.
3. **`duo/tools/duo-dev`** (one Python 3 file, standard library only, uses adb): `push <mod folder> <game>`,
   `logs <game>` (mod log + filtered logcat, follow mode), `shot` (both screens), `layout pull|push`, `validate`
   (against the community schemas), `pack` (ready-to-submit package). Clear `--help`, helpful errors.
4. **Templates** under `duo/sdk/templates/`: a Unity BepInEx plugin using the shared plugin's API (cameras, UI
   routing, bridge, touch), a LÖVE/Lovely Lua mod, a minimal bridge client in C# and Lua implementing the
   engine-adapter contract, each building out of the box with a README of five steps or fewer.
5. **Docs**: one entry page `duo/sdk/README.md` linking everything, with a "your first mod in 10 minutes" walkthrough;
   keep the contract docs as the reference. Use `qwen_draft` for boilerplate and doc tables, then edit them.

Test each tool for real (the CLI against the Thor, templates built, developer mode screenshotted on both screens,
measured fps with it off = unchanged).
