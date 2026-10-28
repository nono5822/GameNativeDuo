# Task 6: Disco Elysium and Skyrim, and the updated future-games list

Read `duo/agents/tasks/queue/RULES.md` and `duo/futuregames.md`. The user has downloaded **Disco Elysium** and
**The Elder Scrolls V: Skyrim Special Edition** in GameNative on the Thor (find them under
`/data/user/0/app.gamenative.duo/Steam/steamapps/common/` with `run-as`). Back up their saves first; no achievements.

## Disco Elysium (Unity)
- Find the scripting backend: `*_Data/Managed/Assembly-CSharp.dll` = Mono (our generic Unity plugin applies),
  `GameAssembly.dll` = IL2CPP (needs BepInEx 6, which we do not ship yet).
- Measure baseline fps without the mod. If Mono: try the generic plugin, find what goes to the bottom screen (the
  dialogue/thought panel on the right, inventory, character sheet, Thought Cabinet, journal and map are the obvious
  wins), add quirks in `Quirks.cs` by type name as needed, a catalog entry (`qwen_draft` from an existing Unity entry)
  and a `ui-layout.json`. If IL2CPP: build a region-view preset as the no-mod fallback and write what a BepInEx 6
  path would take.

## Skyrim Special Edition (Creation Engine)
- Feasibility only, tonight: baseline fps (median on Thors is 44, so CPU-bound: add nothing per frame), whether SKSE
  can load under GameNative (Wine + Box64/FEX), and what the Scaleform HUD/menus would allow (compass, map, inventory
  on the bottom). Deliver a region-view preset if any part of the frame is worth magnifying, and a written plan.

## Future games list
Rewrite `duo/futuregames.md` with refreshed Thor play data (save the endpoint from its "Sources" to the run folder
with `curl -s`, then `jq`; each game maps to `[runs, median fps, ...]`, summed over stores) and these additions,
keeping its format:
- Originally-dual-screen games: Mega Man Star Force Legacy Collection (RE Engine via REFramework, 1,412 runs),
  Ace Attorney Trilogy (Unity, 2,079; check Mono vs IL2CPP), Castlevania Dominus Collection (577).
- New engine families: GameMaker (Fields of Mistria, DELTARUNE, Undertale, Pizza Tower, UFO 50, HoloCure), RPG Maker
  MZ (Look Outside, 1,538), Unity IL2CPP (Megabonk, BALL x PIT, Dave the Diver), Factorio (Lua API).
- Unreal shortlist (the user's next engine): Bloodstained (UE4, 56 fps, UE4SS proven), Dispatch (UE 4.27, 59 fps,
  CommonUI), Octopath Traveler I/II + the HD-2D games, Ace Combat 7 (custom UE4SS build), Pseudoregalia (UE5 test).
  Avoid for now: P3 Reload, FF7 Remake, Clair Obscur, Lies of P, Stray, Palworld (already under 40 fps). Adapter
  notes: UE4SS via `dwmapi=n,b`, its GUI console off, viewport split through the splitscreen layout, UMG widgets
  moved with SetPositionInViewport, the UDP bridge needs a C++ UE4SS mod (clang-cl + xwin on Linux).
- Demanding picks: Valheim, Outer Wilds, DREDGE, RimWorld, Subnautica: Below Zero.
- Status column: Slay the Spire 2 and Isaac (proper Lua mod) are the user's own next projects; Disco Elysium and
  Skyrim as found tonight.
