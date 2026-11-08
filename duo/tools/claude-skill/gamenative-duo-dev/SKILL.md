---
name: gamenative-duo-dev
description: Build, test and package dual-screen mods for GameNative Duo (AYN Thor and other dual-screen Android handhelds running PC games). Use when writing or debugging a mod that puts a game's UI on the bottom screen, a companion, a UI layout, a game profile, or anything using the Duo SDK (C# GameNativeDuo.Sdk, duo.lua, duo.h), the bridge protocol, `duo sim`, `duo check` or the `gamenative-duo` MCP tools.
---

# GameNative Duo mod development

GameNative Duo runs Windows games on Android (Wine, Box64/FEX, DXVK) and splits them across two screens. A mod inside
the game (BepInEx, SMAPI, Lovely, an ASI loader) talks to the app over a UDP **bridge** on 127.0.0.1 and reads files
the app writes into `%AppData%\GameNativeDuo\` in the game's prefix.

## Pick the lowest level that works

1. **No code**: region view (parts of the frame shown on the bottom screen), a UI layout, a companion (JSON cards).
2. **Game profile** (Unity): `profile.json` configures the official adapter: UI cameras, canvases, element names and
   features, state read from public members. No compiler, no code review.
3. **Code mod** on the SDK: only when 1 and 2 cannot do it. Code mods need consent from every user.

## The loop

```
duo new --engine love|unity|smapi|native me.mygame-dual-screen --game steam-<appid>
duo build me.mygame-dual-screen/src [--game <references folder>]
duo sim [--prefix <wine prefix>]        # writes layout.json, runs the bridge, serves the editor at 127.0.0.1:8642
# run the game WINDOWED at the size duo sim prints (Thor at 100%: 1920x2160)
duo elements | duo logs -f | duo tap X Y | duo screenshot bottom | duo check | duo test script.duo
duo pack me.mygame-dual-screen --write  # then a PR to nono5822/GameNativeDuo-community
duo dev --device me.mygame-dual-screen   # on a real device (developer mode on)
```

With the `gamenative-duo` MCP server (`duo mcp`), the same as tools: `info`, `elements`, `set_rule`, `tap`, `swipe`,
`screenshot`, `logs`, `command`, `state`, `check`, `test`, `build`, `install_device`. Tap/swipe coordinates are
**bottom-screen pixels** (Thor: 1240x1080). Always `screenshot` after a change instead of assuming it worked.

## The SDK in one screen (C#; Lua and C use the same names)

```csharp
Duo.Start("unity-mygame");                       // once
Duo.Tick(Screen.width, Screen.height);           // every frame, game thread
if (!Duo.IsSplit) return;                        // act only while the window matches layout.json
Duo.Elements.Register("Score", "Score", () => rectOrNull, new ElementFeatures { Scale = false });
var rule = Duo.Rule("Score");                    // screen, scale, x/y (fractions of that screen); re-read when Duo.RulesVersion changes
Duo.OnTouch(t => ...);                           // bottom-screen fingers, window pixels, origin top-left
Duo.Publish("mygame.hp", hp);                    // companion state: only sent while a card shows it (Duo.Wants)
Duo.OnCommand("mygame.heal", args => CommandResult.NotNow("Sit on a bench first"));
Duo.Log("..."); Duo.Haptic(15);
```

## Rules that are not optional

- **Declare, then gate**: an element's `features` (screens, move, scale range) must be true. The editors trust them and
  `duo check` verifies them; elements not on screen are listed with `present: false` and a `when` ("During a run").
- **Rectangles are where the element is now**, rules applied, window pixels. Apply rules on top of what the game writes
  every frame, never instead of it.
- **Threads**: the bridge's receive side only queues; touch the game only inside `Tick`/`update`.
- **Commands go through the game's own UI paths** (the functions its menus call), never memory or save-file writes.
  Nothing that touches DRM or decrypts game files. Single-player only for state and commands.
- State keys go under your companion api's name (`mygame.`); the app drops `user.`, `static.`, `session.`, `file.`.

## Traps we already hit

- The bridge must bind **IPv4 127.0.0.1**; Android's loopback lookup can return `::1` and packets never arrive.
- **Harmony** does not write `__args` changes back to value arguments: use `ref` parameters.
- **Unity remembers its last resolution**: the app adds `-screen-width/-screen-height`; in the sim, set the window
  size yourself. Unity mods need the Mono backend (`*_Data/Managed`, no `GameAssembly.dll`).
- **Stardew**: never force `Game1.ShouldDrawOnBuffer` on the title or loading screens (GPU hang). The first launch
  after an app install often dies: retry.
- **Balatro / any game with live achievements**: GameNative uploads achievements live. Test with achievements off
  (Balatro: `G.F_NO_ACHIEVEMENTS`) and seeded runs.
- Two plugins in one game embedding different SDK copies: embed the SDK as source with `DUO_SDK_EMBEDDED` (internal
  types), never reference a shared SDK DLL from two plugins.
- A stale plugin DLL on the device gives `TypeLoadException`: update every DLL of a mod together.

## Reference

`duo/docs/engine-adapter-contract.md` (bridge, files, element features), `duo/docs/duo-api.md` (the HTTP API the tools
use), `duo/docs/companions.md` (cards, state, commands), `duo/docs/uniform-editing.md` (editing levels, conformance),
`duo/docs/game-profiles.md` (level 1), `duo/sdk/README.md` (the SDKs), `duo/docs/llms.txt` (index).
