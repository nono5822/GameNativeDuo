<div align="center">

<img src="assets/banner.png" alt="GameNative Duo: dual-screen PC gaming for Android handhelds" width="100%">

**Your PC games, across both screens of your dual-screen Android handheld.**

A fork of [GameNative](https://github.com/utkarshdalal/GameNative) that makes the second screen part of the game.
The world plays on the top screen. The HUD, inventory, map and dialogue live on the bottom screen, and you use them
with your fingers.

<img src="assets/stardew-walk.gif" alt="The farmer walking around the farm on the AYN Thor: the world scrolls across the top screen, the hinge and the bottom screen as one picture, with the HUD only on the bottom screen" width="600">

<sub>Recorded on an AYN Thor. Both screens are captured on the device and assembled at their physical scale, hinge
included, so the farm lines up across the gap exactly as it does in your hands.</sub>

</div>

## What it is

[GameNative](https://github.com/utkarshdalal/GameNative) runs the Windows games you own on Steam, Epic, GOG and Amazon
directly on Android, using Wine, Box64 and DXVK. It uses one screen.

GameNative Duo adds what a dual-screen handheld needs:

- **One game across two screens.** The game renders a single tall picture. The top part goes to the top screen and the
  rest to the bottom screen, straight from the same GPU frame with no copies. A one-time calibration accounts for the
  hinge and for the different pixel density of each panel, so the picture reads as one.
- **Interface on the bottom screen.** Small per-game mods move the HUD and menus off the game view. The top screen shows
  only the game. Crosshairs and other things you aim with stay on top automatically.
- **A real touchscreen.** The bottom screen behaves like a touchscreen built into the game: no cursor, taps land under
  your finger, and drag and drop works. The game never switches its button prompts to keyboard and mouse.
- **A layout you control.** Every piece of the interface can be moved, resized, hidden or sent to the top screen, live,
  while you play, on the device or from a PC.
- **A bottom screen you style.** Show the world continuing below, a soft blur of it, a solid color or your own image,
  and add live readouts such as FPS, battery and temperature.

GameNative Duo installs next to official GameNative under its own app id (`app.gamenative.duo`), so you can keep both.

## A closer look

<table>
<tr>
<td width="50%" align="center"><img src="assets/stardew-dialogue.jpg" alt="An in-game event: the farm on the top screen, Demetrius's dialogue box on the bottom screen"></td>
<td width="50%" align="center"><img src="assets/stardew-inventory.jpg" alt="The inventory open on the bottom screen while the farm stays visible on the top screen"></td>
</tr>
<tr>
<td valign="top"><b>Dialogue and events</b><br>Conversations and choices appear on the bottom screen, and you answer
them with a tap. The scene stays uncovered on top.</td>
<td valign="top"><b>Inventory</b><br>Menus are laid out for the bottom screen's own size and resolution, so nothing is
cut off and nothing covers the game.</td>
</tr>
<tr>
<td width="50%" align="center"><img src="assets/stardew-drag.gif" alt="Dragging the watering can between inventory slots with a finger, recorded on the device"></td>
<td width="50%" align="center"><img src="assets/stardew-map.jpg" alt="The world map open on the bottom screen"></td>
</tr>
<tr>
<td valign="top"><b>Drag and drop</b><br>Pick an item up with your finger, carry it and drop it in another slot. Touches
go into the game directly and never move the system mouse.</td>
<td valign="top"><b>Map and menus</b><br>Every menu the game opens goes to the bottom screen: the map, the journal, shops
and settings.</td>
</tr>
<tr>
<td width="50%" align="center"><img src="assets/stardew-editor.jpg" alt="The layout editor on the bottom screen with the clock selected and sent to the top screen"></td>
<td width="50%" align="center"><img src="assets/stardew-custom.jpg" alt="The resulting layout: the clock and money on the top screen, the rest of the HUD on the bottom screen"></td>
</tr>
<tr>
<td valign="top"><b>Layout editor</b><br>Hold two fingers on the bottom screen and choose Edit UI layout. Tap an element,
then send it to the top or bottom screen, hide it, drag it or resize it.</td>
<td valign="top"><b>Your layout</b><br>Here the clock and money moved to the top screen. Changes apply instantly and are
saved per game, as fractions of the screen, so a layout fits any device with the same screen shapes.</td>
</tr>
</table>

## Balatro on two screens

The table stays on the top screen: the score, the blind, your jokers and the hand you play. Everything you touch moves
to the bottom screen: your hand at full width, Play, Sort and Discard under your thumbs, the hand you are building with
its chips and mult, your consumables, the shop, the blind select, the cash out, booster packs and the menus. Cards fly
between the screens as they would across one table. Play by touch or with the controller, which moves freely across
both screens.

<table>
<tr>
<td width="50%" align="center"><img src="assets/balatro-deal.gif" alt="Balatro on the AYN Thor: a blind is selected on the bottom screen and the hand is dealt from the deck on the top screen down to the bottom screen"></td>
<td width="50%" align="center"><img src="assets/balatro-play.gif" alt="A pair of Jacks is selected on the bottom screen, the hand info shows Pair, and the cards fly up to the top screen where the jokers score them"></td>
</tr>
<tr>
<td valign="top"><b>Dealt across the hinge</b><br>Pick a blind below and the cards travel from the deck on the top
screen into your hand.</td>
<td valign="top"><b>Choose below, score above</b><br>The hand you are building is named as you pick it. Played cards
fly up to the table, where your jokers trigger.</td>
</tr>
<tr>
<td width="50%" align="center"><img src="assets/balatro-buy.gif" alt="A joker is bought in the shop on the bottom screen and flies up to the joker row on the top screen"></td>
<td width="50%" align="center"><img src="assets/balatro-pack.gif" alt="A booster pack is bought and bursts open on the bottom screen in a shower of sparkles"></td>
</tr>
<tr>
<td valign="top"><b>The shop under your thumbs</b><br>A joker you buy takes its place on the top screen.</td>
<td valign="top"><b>Booster packs</b><br>Packs open on the bottom screen, with your hand dealt above the cards when a
tarot or spectral card needs targets.</td>
</tr>
</table>

<table>
<tr>
<td width="33%" align="center"><img src="assets/balatro-menu.jpg" alt="The Balatro title on the top screen and the main menu buttons on the bottom screen"></td>
<td width="33%" align="center"><img src="assets/balatro-blind.jpg" alt="The blind select on the bottom screen, with the jokers and the deck on the top screen"></td>
<td width="33%" align="center"><img src="assets/balatro-hand.jpg" alt="A hand in play: the HUD and jokers on top, the hand, its buttons, the hand info and the consumables below"></td>
</tr>
<tr>
<td width="33%" align="center"><img src="assets/balatro-shop.jpg" alt="The shop on the bottom screen"></td>
<td width="33%" align="center"><img src="assets/balatro-pack.jpg" alt="A Buffoon pack open on the bottom screen"></td>
<td width="33%" align="center"><img src="assets/balatro-cashout.jpg" alt="The cash out screen on the bottom screen"></td>
</tr>
</table>

Built for long runs. Late game tables were tested with nine jokers, full consumable slots, an eleven card hand, every
mega pack, a four slot shop, boss blinds and controller only play. The mod is one render pass in one window, with no
second draw and no frame read back to the CPU, so it costs a few microseconds per frame. Balatro runs at a 75% split
resolution by default, which keeps a busy late game table near 60 fps on the Thor.

## Features

### Two screens, one picture
<img src="assets/stardew-hero.jpg" alt="Stardew Valley on the AYN Thor: the farm on the top screen with no interface, the clock, toolbar and energy bar on the bottom screen over the world continuing below" width="420" align="right">

The game draws one frame for both screens and each screen shows its part of it. The split costs no extra rendering and
no frame copies. Calibration (hinge gap, pixel density, horizontal offset) is set once per device from the two-finger
menu and stays live, so you can line the screens up with a game running.

The top screen keeps the full 1920x1080 view of the world with nothing drawn over it. The bottom screen shows the world
continuing below, with the interface on top of it.
<br clear="right">

### Native touch
Bottom-screen touches are delivered to the game's own interface system: Unity's UI events, or the game's mouse state in
Stardew Valley without moving the real pointer. Taps, holds, drags and drops behave as the game expects, and a tap is
never mistaken for a gameplay click.

### Layout editor
<img src="assets/duo-editor-tabs.jpg" alt="The layout editor's three tabs on the bottom screen: Elements, Background and Widgets" width="100%">

The editor opens on the bottom screen over the running game and has three tabs.

- **Elements** lists every interface element the game reports, outlines them on the bottom screen and shows a map of
  the top screen for elements placed there. Select, move, pinch to resize, hide, reset one element or all of them.
- **Background** chooses what sits behind the interface on the bottom screen (see below).
- **Widgets** adds live readouts and places them with a finger.

Everything applies within a frame and is saved per game. Layouts are plain JSON and use the same format as community
presets, so a shared layout carries its background and widgets too.

<table>
<tr>
<td width="50%" align="center"><img src="assets/slime-rancher-editor.gif" alt="Slime Rancher on the AYN Thor: the ammo hotbar is selected in the layout editor, dragged up the bottom screen and enlarged step by step"></td>
<td width="50%" align="center"><img src="assets/slime-rancher-layout.jpg" alt="The finished Slime Rancher layout: the world on the top screen, and on the bottom screen a dimmed still of the game behind a larger hotbar, the day and time, health, energy, keys and money, with FPS and battery widgets"></td>
</tr>
<tr>
<td valign="top"><b>Moving the interface</b><br>Slime Rancher's ammo hotbar selected as one group, dragged up and
enlarged in a few taps while the game keeps running.</td>
<td valign="top"><b>The result</b><br>The hotbar at the centre where the thumb reaches it, day and time top left,
health, energy, keys and money bottom left, FPS and battery top right, over a dimmed still of the game.</td>
</tr>
</table>

### Bottom screen background and widgets
<img src="assets/stardew-blur-widgets.jpg" alt="Stardew Valley with a blurred world behind the HUD on the bottom screen and FPS, battery and clock widgets" width="420" align="right">

- **Game**: the world continues from the top screen onto the bottom one, aligned across the hinge.
- **Blurred game**: the same world, softly blurred, so the interface stands out.
- **Color**: a solid color from a palette. It also saves the game the work of drawing the lower part of the world.
- **Image**: any picture from your gallery, or a still capture of the top screen in one tap.

A dim slider darkens the game, blur and image backgrounds. The game's mod draws the background on the GPU, and the
editor only offers what that game's mod supports (Stardew Valley has all four, Unity games have color and image).

Widgets show FPS, CPU, GPU, temperature, battery or the time. They read what GameNative already measures for the
session, refresh once a second and cost nothing when none are placed.
<br clear="right">

### Edit from a PC
<img src="assets/duo-pc-card.jpg" alt="The bottom screen showing the PC editor's address, pairing code and QR code" width="360" align="right">

Choose Edit on PC and the device serves a web editor on your Wi-Fi. Open the address on a PC (or scan the QR code with
a tablet) and enter the six-letter code shown on the bottom screen. The page shows both screens with every interface
element outlined.

- Drag an element to move it, or drop it on the other screen to send it there. Scroll to resize.
- Search the element list, or use the keyboard: arrows move, plus and minus resize, T, B and H send an element to the
  top, the bottom or nowhere.
- Pick the background, upload an image from the PC, and place widgets.
- Share the layout with the community straight from the PC browser. Background images stay private.

The editor runs only while you have it open and stops with the game. After 20 wrong codes it shuts itself down.
<br clear="right">

### In GameNative's own menu
<img src="assets/duo-quick-menu.jpg" alt="GameNative's in-game menu with the GameNative Duo tab open" width="100%">

The menu GameNative opens with the Back button has a GameNative Duo tab: open the layout editor, start the PC editor,
change the background, switch widgets on and off, and line up the screens, without leaving the game.

### Region view, for any game
Games without a dual-screen mod can still use the bottom screen. Region view leaves the game unchanged on the top screen
and shows a live, magnified part of the same frame on the bottom screen, such as a minimap. Taps on the bottom screen
click the game at the matching spot. It costs almost nothing: the bottom screen is drawn from the frame already on the
GPU.

### Community packages
A Community button on each game page lists shared layouts, recommended settings and dual-screen mods for that game.
Packages come from a signed index on GitHub
([GameNativeDuo-community](https://github.com/nono5822/GameNativeDuo-community)). Layouts and settings apply in one tap.
Code mods install only after a consent screen that explains what they change, and removing one restores the game
exactly. Share your own layout from the same screen, and rate packages through GitHub Discussions.

### One-tap mods
Games with a bundled dual-screen mod get a Dual screen switch next to Play. It installs the mod and its loader, sets the
screen size and restores everything when switched off. After an app update, installed mods are refreshed automatically.

## Games

| Game | Engine | Support | Status |
|---|---|---|---|
| Stardew Valley | MonoGame (SMAPI) | Bundled mod | World across both screens, full touch interface, layout editor |
| Slime Rancher | Unity (BepInEx) | Bundled mod | HUD below, crosshair on top, native touch, layout editor |
| Subnautica | Unity (BepInEx) | Bundled mod | HUD and menus below, prompts and reticle on top |
| Balatro | LOVE (Lovely) | Bundled mod | Hand, shop, packs and menus below, table on top, native touch and controller |
| Dead Cells | HashLink | Region view | Minimap on the bottom screen, no mod needed |
| Hollow Knight, Silksong | Unity (BepInEx) | Community mod | Experimental, built from the game files, not yet tested on hardware |
| Risk of Rain 2 | Unity (BepInEx) | Community mod | Experimental, not yet tested on hardware |
| The Binding of Isaac | Custom | Region view | Experimental minimap preset |

The Unity adapter is generic. Most Unity games that use the Mono backend and Unity's standard UI can be added with a
single catalog entry, with per-game adjustments only where a game needs them. The list of games to support next is
ranked from GameNative's own play data for the AYN Thor ([`duo/futuregames.md`](https://github.com/nono5822/GameNativeDuo/blob/duo/slime-rancher/duo/futuregames.md)).

## Devices

| Device | Status |
|---|---|
| AYN Thor | Supported. Top screen 1920x1080, bottom screen 1240x1080, calibration built in |
| AYANEO Pocket DS, Retroid Dual Screen and others | Planned. Device profiles name screens "top" and "bottom" independently of how Android numbers its displays |

## Getting started

1. Install the APK. It sits next to official GameNative. Sign in as you would in GameNative.
2. Open a game with dual-screen support (the Dual screen tab lists them) and turn on its Dual screen switch, or open
   Community to apply a shared package.
3. Press Play. Unity games need their in-game resolution set to the tall window once (1920x2160 on the Thor).
4. In game, hold two fingers on the bottom screen to open the menu: Edit UI layout, Edit on PC, Line up screens, or
   Choose bottom view for region view. The same options are in the GameNative Duo tab of GameNative's in-game menu
   (Back button).

Every GameNative Duo option lives in the GameNative Duo tab of the container settings: screen split, split mode, split
resolution, native touch and the bottom-screen panel.

## Performance

Emulated games are limited by the CPU, and a handheld runs on a small battery. Every part of GameNative Duo is built
around that.

- Frames are never copied by the CPU. Both screens are drawn from the same GPU frame.
- Nothing polls. The app and the game mods talk over a local bridge that sends a few bytes per touch and is idle
  otherwise.
- Per-frame work is limited to the interface elements you changed. Measured in Slime Rancher on the Thor, the Unity
  adapter costs about 0.1 ms per frame, under half a percent of the frame time.
- Region view in Dead Cells kept the GPU around 12% busy.
- The Balatro mod draws both screens in one pass and never reads a frame back. Its own work is about 0.06 ms per
  frame. The cost that remains is presenting the tall frame, so Balatro defaults to the 75% split resolution.
- A split resolution setting (100, 75 or 50%) lowers the cost of the tall window on demanding games.
- Bottom screen backgrounds are drawn by the game on the GPU. The blur works on a target of a few thousand pixels, and
  a solid color skips drawing the lower part of the world.
- Widgets redraw once a second, and the PC editor copies a screen only when the page asks for it.

## For developers: a dual-screen devkit

The goal is that anyone can make a game dual-screen, whatever its engine, without changing the app. An in-game mod, the
engine adapter, and the app share a small, engine-neutral contract:

- **`layout.json`** describes where each screen sits inside the game window.
- **`ui.json`** holds the user's layout: for each element, its screen, scale and offset. Presets use the same format.
- **A local bridge** (UDP on 127.0.0.1, 16-byte touch packets) carries native touch, live layout updates and the
  element list for the editor.

Implement those three things for an engine and the editor, storage, touch and community presets work unchanged. The
specification is [`duo/docs/engine-adapter-contract.md`](https://github.com/nono5822/GameNativeDuo/blob/duo/slime-rancher/duo/docs/engine-adapter-contract.md).
The Unity adapter ([`duo/mods-src/unity`](https://github.com/nono5822/GameNativeDuo/tree/duo/slime-rancher/duo/mods-src/unity))
is the reference implementation, and the Stardew Valley adapter
([`duo/mods-src/stardew`](https://github.com/nono5822/GameNativeDuo/tree/duo/slime-rancher/duo/mods-src/stardew)) is a
second, complete example for a non-Unity engine.

### Roadmap
- More engine adapters: Godot (Slay the Spire 2, Brotato), Unreal (UMG), Ren'Py, RPG Maker, Unity UI Toolkit.
- Top-screen masks for region view, so an element shown below can be hidden above.
- An MCP server so AI agents can help build and test dual-screen mods: inspect the interface, move elements and capture
  both screens.
- Bottom-screen panels and window routing.

### Building
```bash
./gradlew :app:assembleModernDebug     # APK in app/build/outputs/apk/modern/debug/
```
The newest work is on the `devkit` branch and the `duo/*` feature branches. Architecture, conventions and debugging
notes are in [`CLAUDE.md`](https://github.com/nono5822/GameNativeDuo/blob/duo/slime-rancher/CLAUDE.md) and
[`duo/notes/`](https://github.com/nono5822/GameNativeDuo/tree/duo/slime-rancher/duo/notes).

## Staying close to GameNative

This fork follows upstream closely. New code lives in its own package (`app.gamenative.duo`), and upstream files are
only touched with small, documented hooks, so GameNative's improvements arrive here quickly. Fixes that help upstream
are offered back.

## Credits and licence

- [GameNative](https://github.com/utkarshdalal/GameNative) and everyone behind it. This fork is built entirely on their
  work. Please support them.
- Mod loaders bundled unmodified, with their licences: [BepInEx](https://github.com/BepInEx/BepInEx) (MIT),
  [SMAPI](https://github.com/Pathoschild/SMAPI) (LGPL-3.0) and
  [Lovely](https://github.com/ethangreen-dev/lovely-injector) (MIT).
- [balatro-dualscreen](https://github.com/rosbean/balatro-dualscreen) by rosbean (GPL-3.0), the native Android dual
  screen Balatro that inspired the layout of the Balatro mod.
- Licensed under the GPL-3.0, like GameNative.

Mods only change games you own. Nothing in this project bypasses or weakens DRM.
