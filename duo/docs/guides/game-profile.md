# Make a game profile (Unity)

For many Unity games the whole port is one JSON file. The app ships a Unity plugin that already knows how to send a
game's interface to the bottom screen; a game profile tells it what it would otherwise guess: which cameras draw the
interface, which canvases go where, what stays on top.

## Will it work for this game

Look in the game's folder:

- `<Game>_Data/Managed/` with many `.dll` files: yes (the Mono build).
- `GameAssembly.dll` and an `il2cpp_data` folder: no. IL2CPP games are not supported.

The interface has to be uGUI (canvases) or sprites under a camera of their own. UI Toolkit is not supported yet.
Either way, region view works with no profile at all.

## Make one

1. `duo new --engine unity yourname.mygame --game steam-<app id>` writes a folder with a stub `profile.json`.
2. Run the game with the plugin, under `duo sim` on a PC or on a handheld with developer mode on, and open the
   editor's **Inspector** tab. It lists the cameras, canvases and elements as the plugin sees them.
3. Write into `profile.json` what the plugin got wrong and save. The plugin reads the file again when it changes.
4. `duo check` tries every feature you declared.

```json
{
  "format": "gamenative-duo/game-profile", "version": 1,
  "game": "steam-433340", "engine": "unity-ugui",
  "cameras": { "ui": [ { "name": "UICamera" } ], "world": [ { "name": "Main Camera" } ] },
  "canvases": [ { "path": "HUD Root/HudUI", "screen": "bottom" }, { "path": "Pause Menu", "screen": "bottom" } ],
  "stayOnTop": [ "*/crossHair" ],
  "elements": { "HUD Root/HudUI/UIContainer/crossHair": { "name": "Crosshair" } }
}
```

| Key | Says |
|---|---|
| `cameras.ui` | Cameras that draw the interface: they go to the bottom screen |
| `cameras.world` | Cameras that are never interface, whatever they look like |
| `canvases` | A canvas (by its path in the scene) and the screen it starts on |
| `stayOnTop` | Elements that stay on the top screen unless the player moves them (crosshairs) |
| `elements` | A readable name for an element, and what the editor may do with it |
| `state` | Values to publish for companions, read from the game's public fields |

Everything has a default, so an empty profile changes nothing, and you only write what is wrong.

## Share it

`duo pack yourname.mygame --write`, then `duo submit`. A profile is data: it needs no code review and installs with
one tap. The full reference is [`game-profiles.md`](../game-profiles.md).

## When a profile is not enough

If the game needs real code (a patch to how it scales its interface, a new element like a live map), that is a code
mod: [code-mod.md](code-mod.md). Tell us first, though: a game that needs a patch is often a sign that the shared
plugin is missing something everyone would use.
