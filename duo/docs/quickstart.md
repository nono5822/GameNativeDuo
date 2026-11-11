# Your first dual-screen mod

About fifteen minutes, on a PC, with no handheld: a small LÖVE game whose score sits on the bottom screen, running
against the simulator. The same steps work for the other engines; only the template changes.

If you only want to rearrange a game that already has a mod, or make a notes-and-checklists page, you do not need
this page: see [the guides](#what-to-make) below.

## 1. Get the tool

Download `duo` for your system from the [releases page](https://github.com/nono5822/GameNativeDuo/releases) and put
it on your `PATH` ([`duo/tools/duo/README.md`](../tools/duo/README.md) has the details), or run it from a checkout
with the .NET 10 SDK: `dotnet run --project duo/tools/duo/src -- version`.

```
$ duo version
duo 0.1.0 (Duo SDK 1.0)
```

You also need [LÖVE](https://love2d.org) for this example.

## 2. Start a mod from the template

```
$ duo new --engine love score
Created score/ (love):
  src/main.lua
  src/duo.lua
  manifest.json
```

`src/main.lua` is a whole game in about fifty lines. The three parts that matter:

```lua
duo.start({ engine = "love-example", log = print })

-- An element the layout editors can list, move, send to the other screen or hide.
duo.elements.register("Score", "Score", function()
    return score_box.x, score_box.y, score_box.w, score_box.h
end, { features = { scale = false } })

-- Fingers on the bottom screen, in window pixels.
duo.on_touch(function(action, pointer, x, y) ... end)
```

and, every frame, `duo.update(love.graphics.getDimensions())`.

## 3. Start the simulator

The simulator plays the app's part: it writes the same `layout.json` the app writes on a handheld, runs the bridge,
and serves the layout editor.

```
$ duo sim
duo sim: AYN Thor at 100%: window 1920x2160
  files:  /home/you/GameNativeDuo
  bridge: 127.0.0.1:41709
  editor: http://127.0.0.1:8642/?k=BXRHX2
Run the game windowed at 1920x2160. Ctrl+C stops.
```

The window is tall: the top screen's picture above the bottom screen's. `--scale 0.5` halves it if your monitor is
small.

## 4. Run the game

Leave the simulator running. In another terminal:

- **Windows**: `love score\src`. The mod finds the simulator's files in `%AppData%\GameNativeDuo`.
- **Linux, macOS**: the simulator wrote its files into a `GameNativeDuo` folder where you started it. Tell the game
  where that is: `APPDATA="$PWD" love score/src` (from the folder you ran `duo sim` in).

Make the window the size the simulator printed. The game draws its field on the top part and the score on the bottom
part.

## 5. Look at it through the tool

```
$ duo info        # the mod connected: its engine, SDK version and capabilities
$ duo elements    # "Score", on the bottom screen
$ duo tap 620 540 # a finger on the bottom screen: the score goes up
$ duo check       # tries every feature the element declares
```

Open the `editor:` address in a browser. It is the layout editor the app serves to a PC: drag "Score" to the top
screen and the game moves it, because `main.lua` asks `duo.elements.screen_of("Score")` every frame.

## 6. Pack it

```
$ duo pack score --write
```

checks the folder against the community's rules and fills in the file hashes. The first run tells you what is still
missing (a real id like `yourname.score`, the files to ship). `duo submit` says how to send it in. On a handheld,
`duo dev --device` pushes the folder to the game with developer mode on.

## What to make

There are four kinds of things you can make. Start with the smallest that does what you want.

| You want to | Make | Code | Guide |
|---|---|---|---|
| Rearrange a game that already has a mod | A layout | none | [guides/layout.md](guides/layout.md) |
| Notes, checklists, maps, timers, live values beside a game | A companion | none | [guides/companion.md](guides/companion.md) |
| Dual screen for a Unity game | A game profile | none (JSON) | [guides/game-profile.md](guides/game-profile.md) |
| Dual screen for another engine, or something a profile cannot say | A code mod | yes | [guides/code-mod.md](guides/code-mod.md) |

Every game also works with **region view** and no mod at all: the player picks parts of the picture to show on the
bottom screen.

## Engines

| Engine | Works today | What the game needs | How |
|---|---|---|---|
| Unity (uGUI, sprite HUDs) | Yes | The Mono build: a `<Game>_Data/Managed` folder and no `GameAssembly.dll`. IL2CPP games are not supported | A game profile for the shared plugin (BepInEx 5); a code mod only for what a profile cannot say |
| Unity (UI Toolkit) | Not yet | | Region view |
| MonoGame with SMAPI (Stardew Valley) | Yes | SMAPI | A SMAPI mod on the C# SDK (`duo new --engine smapi`) |
| LÖVE (Balatro) | Yes | A way to load Lua into the game (Lovely for Balatro), or your own game's source | `duo.lua` (`duo new --engine love`) |
| Native games | Partly | An ASI loader | `duo.h` (`duo new --engine native`): state and commands; the picture through region view |
| Godot, Unreal, Ren'Py, RPG Maker | Not yet | | Region view and companions |

The exact messages a mod exchanges with the app are in [`engine-adapter-contract.md`](engine-adapter-contract.md);
the SDKs ([`duo/sdk/`](../sdk/README.md)) implement them so you do not have to.

## Rules

- A mod only adds code to a game the player owns. Nothing that touches DRM, decrypts game files or ships them.
- Read the game's state and drive it through its own interface. No memory writes, no save-file edits.
- Code mods are built from source by the community repository and signed by their author; see
  [`community-packages.md`](community-packages.md), "Code trust".
