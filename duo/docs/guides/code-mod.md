# Write a code mod

A code mod runs inside the game. It puts the game's interface on the screens the player chose, takes fingers from the
bottom screen, lists its elements for the layout editors, and can publish state for companions. Write one when a
layout, a companion or a game profile cannot do what you want.

Do [the quickstart](../quickstart.md) first: it shows the whole loop on the simulator in a few minutes.

## Pick a template

| Game | Template | You get |
|---|---|---|
| Unity (Mono) | `duo new --engine unity ID` | A BepInEx plugin on the C# SDK, and a `profile.json` |
| Stardew Valley | `duo new --engine smapi ID` | A SMAPI mod on the C# SDK |
| LÖVE | `duo new --engine love ID` | `main.lua` and `duo.lua` |
| Native | `duo new --engine native ID` | A C file on `duo.h`, built as an `.asi` |

`ID` is `yourname.modname`, lowercase.

## What a mod does

The SDK does the talking; your code answers four questions.

| Question | C# | Lua |
|---|---|---|
| Where are the screens? | `Duo.Layout`, `Duo.IsSplit` | `duo.layout`, `duo.is_split()` |
| What can the player move? | `Duo.Elements.Register(id, name, rect, features)` | `duo.elements.register(id, name, fn, opts)` |
| Where did they put it? | `Duo.Rule(id)` | `duo.rule(id)`, `duo.elements.screen_of(id)` |
| What did they touch? | `Duo.OnTouch(t => ...)` | `duo.on_touch(fn)` |

Call `Duo.Tick(width, height)` (`duo.update`) every frame. Optional: `Duo.Publish(key, value)` for companions,
`Duo.OnCommand(name, ...)` for their buttons, `Duo.Log`, `Duo.Haptic`.

Rules every mod follows:

- Act only while the window has the size in `layout.json` (`IsSplit`). Then a leftover file does no harm.
- Touch game objects on the game's own thread, inside `Tick`. The bridge only queues.
- Deliver fingers to the game's interface as touches, never as the mouse: a mouse draws a cursor and steals focus.
- Declare what each element can really do (`features`: screens, move, scale). The editors offer exactly that, and
  `duo check` tries all of it.
- Go through the game's own interface for commands. No memory writes, no save-file edits, nothing near DRM.

## Build and try

```bash
duo build mymod/src --game "<game folder>"   # dotnet for C#, make for C, a syntax check for Lua
duo sim                                      # then run the game in a window of the size it prints
duo check                                    # every declared feature
duo test steps.txt                           # your own script of taps, commands and expectations
```

On a handheld: turn on developer mode in the game's GameNative Duo settings, then
`duo dev --device yourname.mymod mymod`. The files load at the next game start, and the bottom screen shows an
overlay with the mod's engine, its traffic and its last log lines.

## Publish

Code needs more care than data, because it runs in the player's game.

1. `duo keygen` once: your author key.
2. `duo pack mymod --write --sign`.
3. `duo submit` explains the pull request to the community repository. Its CI builds your mod **from source**; the
   binary players get is the one it built. A C# mod is compiled against reference stubs of the game's assemblies
   (`duo stubs`), never the assemblies themselves.
4. Players see a consent screen with your name, what the mod may do, and its files, once per version.

Details: [`community-packages.md`](../community-packages.md) ("Code trust"), the protocol in
[`engine-adapter-contract.md`](../engine-adapter-contract.md), the SDKs in [`duo/sdk/`](../../sdk/README.md).
