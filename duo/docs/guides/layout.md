# Make and share a layout

A layout says where each piece of a game's interface goes: bottom screen, top screen or hidden, how big, shifted by
how much. It also carries the bottom screen's background and widgets. It is data, not code, so anyone can make one
and it installs with one tap.

You need a game that has a dual-screen mod (the Dual screen tab of the library lists them) or region view.

## Make it

1. Turn on Dual screen next to Play and start the game.
2. Hold two fingers still on the bottom screen for a second. The GameNative Duo menu opens.
3. Choose **Edit UI layout**. Tap an element, drag it, pinch to resize it, or use the Bottom / Top / Hidden buttons.
   "List" reaches elements that are hidden or not on screen right now.
4. The Background and Widgets tabs set what is behind the interface and add FPS, battery, clock and so on.
5. "Done" keeps it. It applies at once and every time you play.

A keyboard and mouse are more comfortable for fine work: choose **Edit on PC** in the same menu and open the address
it shows in a browser on the same Wi-Fi.

Some elements offer fewer choices than others, because of how the game draws them. The editor says why when you
select one.

## Share it

In the mod explorer (the arrow next to the Dual screen switch), "Share your layout" opens a form on GitHub with your
layout filled in. The PC editor has the same under its Share tab. Give it a name, say what it changes, and submit:
a bot checks it and it joins the community list after review. A background picture you chose stays on your device;
the shared layout gets a plain colour instead.

## Use someone else's

The mod explorer lists community layouts for the game under the mods. "Apply" installs one.

## The file

A layout is one JSON file (`gamenative-duo/ui-layout`). You rarely need to read it, but it is simple:

```json
{
  "format": "gamenative-duo/ui-layout", "version": 1,
  "game": "STEAM_433340", "engine": "unity-ugui",
  "elements": {
    "HUD Root/HudUI/UIContainer/ammo": { "screen": "top", "scale": 1.2, "x": 0.05, "y": -0.02 }
  },
  "background": { "mode": "color", "color": "#101322", "dim": 0.3 },
  "widgets": [ { "type": "fps", "x": 0.03, "y": 0.04 } ]
}
```

`x` and `y` are fractions of the screen the element is on, so a layout made on one device fits another.
