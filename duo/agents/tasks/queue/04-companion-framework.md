# Task 04: Duo Companions, the framework (user: "insanely important")

Owner: Opus (design + app runtime) / Codex (SDK, tools, docs) · Thor needed: yes for 04b-d · Depends on: 01

Read RULES.md, `duo/docs/engine-adapter-contract.md`, `duo/docs/community-packages.md`, and study
https://github.com/igawa6/eden-duo-companions (GPL-3.0: take ideas, write our own code) and the PC entries in
`duo/docs/existing-dual-screen-mods.md`. Do this as separate commits/sessions, in order:

**04a Design** `duo/docs/companions.md`: a companion = a per-game bottom-screen (or top-screen) app made of pages,
cards, maps, lists, trackers, planners and hotkeys, fed by data sources: (1) the bridge (mods send state, receive
commands), (2) region views of the frame, (3) static content (guides, map images, checklists, notes), (4) user
input. Ideas to adopt from Eden Duo: version gating per game build (unsupported build = notice, reads nothing),
context-sensitive cards (show when relevant), staged actions applied through the game's own UI, no game assets in
packages, generators per game, a porting guide. Ours goes further: declarative (JSON manifest + layout), no code for
typical companions, sandboxed optional script for skilled modders, works with or without an in-game mod, per-device
variants (task 06), shared through the community index with ratings, live-reloadable, zero cost when idle.
Include a **porting guide**: how an existing dual-screen mod (Android port, Eden companion, PC companion app like
Skyrim Web Monitor / Minecraft Second Screen) maps onto it.
**04b Runtime** in the app (native views from the JSON schema; WebView only as an opt-in for script companions),
on either screen, idle at 0 fps, editor integration (place/resize like UI layout elements).
**04c First companions**: a static "guide + checklist + map image + notes + timer" companion usable for ANY game;
Hollow Knight (live map page + charms/progress cards from the bridge); Stardew (calendar, birthdays, crops); Balatro
(run info, deck). **04d SDK**: schema + validator in the community repo, `duo-dev companion new|validate|push`,
two templates, a 10-minute tutorial (Codex can do the SDK/tooling half from the 04a spec).
