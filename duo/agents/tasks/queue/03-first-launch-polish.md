# Task 03: first launch that just works, and visible mod status

Owner: Opus · Thor needed: yes · Depends on: —

Read RULES.md and CLAUDE.md "Known issues".
1. Status in the Dual screen control: mod loaded / bridge connected / why not (game updated, mod failed to load,
   wrong resolution, Gamescope), with one tap to the fix. Never a silent black bottom screen: show a status card there.
2. Unity games: set the tall resolution automatically when Dual screen turns on (`-screen-width/-screen-height` or the
   registry keys), restored when off.
3. Auto-retry the "Waiting for game to launch" death on the first launch after an install (once).
4. Two-finger long press: fix "first press after load fails"; a one-time hint; an entry from GameNative's menu (Duo tab).
5. Installer manifest with file hashes: "modified by you" vs "pushed by hand" vs ours; a "Reset mod files" button.
6. Back up the game's save before installing any code mod, one-tap restore in the mod explorer.
7. Test the Gamescope -> X11 switch from task 2 on a fresh Bionic container.
