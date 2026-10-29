# Future games: what Thor owners play, ranked for dual-screen support

Researched 2026-10-19.

## Sources

1. **GameNative's own play data for the AYN Thor** (primary). `api.gamenative.app/api/device-game-stats?deviceModel=AYN Thor&gpuName=Adreno (TM) 740` —
   the public endpoint the app itself uses for its compatibility badges. **1,087,174 successful runs over 17,933 games**
   recorded on Thors (Snapdragon 8 Gen 2), with median FPS and 5-star counts. This is what people actually launch, not
   what they talk about.
2. **r/AynThor** (secondary, for context and sentiment), read through the Arctic Shift Reddit archive (reddit.com blocks
   scripts): 335 posts about PC/Steam games and ~1,150 comments from the 18 biggest threads, including
   [Can anyone recommend steam games that run good on the Thor](https://www.reddit.com/r/AynThor/comments/1umvmwx/),
   [What are the best steam games you guys have on your Thor?](https://www.reddit.com/r/AynThor/comments/1x0kotu/) and
   [Based on my game library so far what do u recommend](https://www.reddit.com/r/AynThor/comments/1w6kw9l/).
   Mentions by upvote weight agree with the play data (Silksong, Hades, Stardew, Slay the Spire, Megabonk, Isaac,
   Mina, Balatro, Celeste on top). Notable: **"Hollow Knight: Silksong Dual-screen Android port" got 634 upvotes**, the
   most of any PC-game post — demand for exactly what this project does.
3. Engine and modding facts: game files (Hollow Knight, Celeste, Risk of Rain 2, Isaac, Subnautica installed locally,
   inspected without running them), community modding docs (Thunderstore, Nexus, GitHub) — linked per game below.

## Ranking method

Priority = **popularity first** (Thor runs), **then engine** (how much of the devkit already applies):

- Engine tier **1**: an adapter exists or the engine is covered generically (Unity Mono + uGUI/BepInEx 5,
  XNA/FNA/MonoGame, LÖVE).
- Engine tier **2**: a known engine with a mature, script-level mod loader we have not adapted yet (Godot + its mod
  loader, GameMaker + YYToolkit/UTMT, Unity IL2CPP + BepInEx 6, custom engines with an official Lua API).
- Engine tier **3**: no practical UI hook (custom engines without a mod API, big AAA ports).

Dual-screen value: how much UI the game has that could move to a touchscreen (inventory, map, cards, HUD).

## Top 40 on the AYN Thor

| # | Game | Thor runs | Med. FPS | Engine | Tier | Modding | Dual-screen value | Status |
|---|---|---|---|---|---|---|---|---|
| 1 | The Binding of Isaac: Rebirth | 28,694 | 59 | Custom C++ (Nicalis) | 2 | Official Lua API (Afterbirth+/Repentance), REPENTOGON | High: minimap, items, stats | **Prototype: region view (minimap below)** |
| 2 | Slay the Spire 2 | 25,802 | 58 | Godot 4 (.NET) | 2 | C# + PCK mods in `mods/`, Harmony | Very high: hand of cards, map, relics | Researched, next |
| 3 | Megabonk | 15,647 | 59 | Unity (IL2CPP likely) | 2 | BepInEx 6 / MelonLoader | Medium: HUD, upgrades | Later |
| 4 | Kingdom Hearts HD 1.5+2.5 | 14,165 | 54 | Custom (Square Enix) | 3 | None for UI | Medium | No |
| 5 | Skyrim Special Edition | 13,805 | 44 | Creation Engine | 3 | SKSE (native, heavy) | High (map, inventory) but CPU-bound | No |
| 6 | Fields of Mistria | 12,391 | 59 | GameMaker | 2 | YYToolkit, Mods of Mistria (MOMI) | High: inventory, calendar | Later |
| 7 | Stardew Valley | 12,117 | 60 | MonoGame (XNA) | 1 | SMAPI | Very high | **Done, UI system added** |
| 8 | Hollow Knight | 10,905 | 59 | Unity 6 (Mono), tk2d | 1 | BepInEx 5, HK Modding API | High: HUD, inventory, map | **Prototype (community mod)** |
| 9 | Mina the Hollower | 10,318 | 60 | Custom C++ (Yacht Club) | 3 | None | Medium | No |
| 10 | Persona 3 Reload | 9,199 | 29 | Unreal Engine 4 | 3 | UE4SS (Lua), heavy | High | No (29 FPS already) |
| 11 | Hollow Knight: Silksong | 9,144 | 59 | Unity 6 (Mono), tk2d | 1 | BepInEx 5 (Thunderstore pack) | High | **Prototype (community mod, same quirk as HK)** |
| 12 | BALL x PIT | 8,951 | 60 | Unity 6 (IL2CPP) | 2 | BepInEx BE IL2CPP (unstable on Unity 6) | Medium | Later |
| 13 | DELTARUNE | 8,363 | 30 | GameMaker | 2 | UndertaleModTool | Medium (it was a DS-like idea…) | Later |
| 14 | Balatro | 8,343 | 60 | LÖVE (Lua) | 1 | Lovely | High | **Done** |
| 15 | Hades II | 7,709 | 75 | Custom (Forge) + Lua | 2 | Hell2Modding (Lua) | Medium: boons, map | Later |
| 16 | Hades | 7,627 | 60 | Custom + Lua | 2 | ModImporter / Hell2Modding | Medium | Later |
| 17 | Celeste | 7,598 | 59 | XNA/FNA | 1 | Everest (C#, MonoMod) | Low: almost no HUD | Studied; not worth an Everest port yet |
| 18 | Persona 5 Royal | 7,354 | 40 | Custom (Atlus) | 3 | Reloaded-II (native) | High | No |
| 19 | Cult of the Lamb | 6,884 | 50 | Unity (Mono) | 1 | BepInEx 5, COTL API | High | Generic plugin, untested |
| 20 | Persona 4 Golden | 6,873 | 54 | Custom (Atlus) | 3 | Reloaded-II | High | No |
| 21 | Vampire Crawlers | 6,801 | 59 | Unity 6 | 2 | Unknown | Medium | Later |
| 22 | Halo: MCC | 6,457 | 57 | Custom | 3 | None for UI | Low | No |
| 23 | Fallout: New Vegas | 6,164 | 52 | Gamebryo | 3 | NVSE (native) | High (Pip-Boy) | No |
| 24 | Vampire Survivors | 6,083 | 59 | Unity (IL2CPP) | 2 | MelonLoader | Low | Later |
| 25 | Undertale | 5,682 | 33 | GameMaker | 2 | UndertaleModTool | Medium | Later |
| 26 | Fallout 4 | 5,375 | 53 | Creation Engine | 3 | F4SE | High | No |
| 27 | Cuphead | 5,159 | 59 | Unity (Mono) | 1 | BepInEx 5 | Low | Generic, low value |
| 28 | Dark Souls Remastered | 5,097 | 58 | Custom (FromSoft) | 3 | DSFix-style only | Low | No |
| 29 | Dead Cells | 5,027 | 57 | Haxe/HashLink (Heaps) | 3 | Official mod.io for content only | Medium: minimap | **Prototype: region view** |
| 30 | Borderlands 2 | 4,794 | 45 | Unreal Engine 3 | 3 | PythonSDK | Medium | No |
| 31 | Blasphemous | 4,310 | 58 | Unity (Mono) | 1 | BepInEx 5, Blasphemous Mod Installer | Medium | Generic, untested |
| 32 | Left 4 Dead 2 | 4,189 | 59 | Source | 3 | VScript | Low | No |
| 33 | Dave the Diver | 4,183 | 48 | Unity (IL2CPP) | 2 | BepInEx 6 / MelonLoader | High (phone UI!) | Later |
| 36 | Mewgenics | 3,827 | 62 | Custom | 3 | None yet | High | No |
| 38 | Brotato | 3,571 | 59 | Godot 3 | 2 | Godot Mod Loader (official Workshop) | Medium: shop, stats | Later (with StS2's Godot work) |
| 39 | Slay the Spire | 3,461 | 59 | libGDX (Java) | 2 | ModTheSpire + BaseMod | Very high: cards, map | Later |
| 42 | Risk of Rain 2 | 3,344 | 42 | Unity 2021.3 (Mono) | 1 | BepInEx 5 (huge Thunderstore scene) | Medium: HUD, items, crosshair on top | **Prototype (community mod)** |
| 43 | Terraria | 3,143 | 47 | XNA/FNA | 1 | tModLoader | Very high: inventory, minimap | Later (Stardew-like) |
| – | Subnautica | – | – | Unity 2019.4 (Mono) | 1 | BepInEx 5 | High: PDA, inventory | In progress (quirks done) |

(Ranks 34–35, 37, 40–41 are AAA ports in tier 3: Black Ops III, Monster Hunter Rise, Batman: Arkham Asylum, FFX/X-2,
Octopath Traveler.)

## The selection (built 2026-10-20, untested on hardware)

At least three engines, chosen for popularity, reach (one adapter covering several games) and whether the files
could be studied for real:

1. **Hollow Knight + Silksong** (Unity 6, Mono, 2D Toolkit) — #8 + #11, 20k runs, and the most wanted dual-screen
   game on r/AynThor. Studied from the installed game: Team Cherry's `ForceCameraAspect` letterboxes both cameras to
   the window shape, `tk2dCamera` re-applies its viewport every frame, and the whole HUD (masks, soul, geo, inventory,
   menus) hangs from `_GameCameras/HudCamera`. The Unity plugin now places tk2d cameras through their settings, sends
   game UI cameras to the bottom screen (widened for its shape), makes sprite HUDs editable, and a Team Cherry quirk
   neutralises the letterbox. Community mod packages.
2. **Risk of Rain 2** (Unity 2021, uGUI) — the generic Unity path on a heavy 3D game: `uiCam` renders the HUD (moved
   to the bottom by name), `sceneCam.rect` is reset every frame (absorbed by per-frame placement), crosshair kept on
   top. Community mod package.
3. **Stardew Valley** (MonoGame) — the full UI system: native touch, drag and drop, HUD elements resized, moved,
   hidden or sent to the top screen. Written tonight, compiled tomorrow (needs the game's assemblies).
4. **The Binding of Isaac + Dead Cells** (custom C++ / HashLink engines) — **region view**, new tonight: the game runs
   unchanged on top and the bottom screen shows a live, magnified part of the same frame (the minimap), drawn by the
   renderer from the same GPU frame. Works for any engine with no mod at all. Community presets.
5. **Slay the Spire 2** (Godot 4 .NET) — #2. Design for a Godot adapter in `duo/notes/2026-10-20-overnight.md`; needs
   the game's files on the PC to build.
