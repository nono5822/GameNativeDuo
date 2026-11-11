# Make a companion

A companion is a set of pages on the bottom screen beside a game: notes, checklists, a map, timers, and, when the
game has a mod that publishes them, live values such as money or health. It is a folder with one JSON file. No code.

Every game already has the general companion (notes, a checklist, a picture, a guide file, timers). Make your own
when you want pages made for one game.

## The smallest companion

```
my-companion/
  companion.json
```

```json
{
  "format": "gamenative-duo/companion", "version": 1,
  "id": "my-companion", "name": "Route notes", "author": "you", "packageVersion": "1.0.0",
  "games": ["steam-367520"],
  "pages": [
    { "id": "route", "title": "Route", "cards": [
      { "id": "steps", "type": "checklist", "title": "Any% route",
        "items": ["False Knight", "Vengeful Spirit", "Hornet"] },
      { "id": "notes", "type": "notes", "title": "Notes", "hint": "Splits, mistakes, ideas." }
    ] },
    { "id": "timer", "title": "Timer", "cards": [ { "id": "run", "type": "timer", "mode": "stopwatch" } ] }
  ]
}
```

`games` lists store keys (`steam-<app id>`), or `["*"]` for every game.

## Try it on a handheld

Push the folder to the app's developer folder. `adb push` cannot create folders, so make it first:

```bash
adb shell mkdir -p /sdcard/Android/data/app.gamenative.duo/files/duo/companions/dev/my-companion
adb push my-companion/companion.json /sdcard/Android/data/app.gamenative.duo/files/duo/companions/dev/my-companion/
```

Start the game. In its settings, the GameNative Duo tab has "Companion": "Automatic" prefers a companion from the
developer folder. Without screen split the companion is the bottom screen; with it, choose "Companion" in the
two-finger menu. After the first start, every push shows at once: the app reloads the folder when a file changes.

## Cards

| Type | Shows | Main fields |
|---|---|---|
| `text` | Paragraphs, `#` headings, `-` bullets, `**bold**` | `text`, or `file` (`pages/x.md`) |
| `checklist` | Items with ticks | `items`, `file`, `userItems` (the player adds their own) |
| `notes` | The player's own text | `hint` |
| `timer` | Stopwatch or countdown | `mode`, `seconds` |
| `image` | A picture, pinch to zoom | `image` (in the folder) or `user: true` (the player picks one) |
| `value` | A label and a value | `label`, `value` |
| `progress` | A bar | `label`, `value`, `max` |
| `list` | One row per item of a list | `items`, `text`, `empty` |
| `map` | A picture with a live marker | `image`, `marker: {x, y}` |

## Live values

A game's mod can publish state (`hk.geo`, `sdv.money`). A card shows it with a template: `"value": "{hk.geo}"`,
`"{sdv.money:n0} g"`. A card or a page can depend on it:
`"when": {"key": "hk.scene.bench", "eq": true}`. Say which mod you rely on with
`"requires": {"api": {"name": "hk", "min": 1}}`; without that mod, the live cards say so and the rest still works.

What the player types and ticks is stored apart from your folder, so an update never loses it.

The full reference (conditions, formats, pages that come to the front, what is planned) is
[`companions.md`](../companions.md).

Sharing companions through the community repository is planned (a `companion` package kind); today `duo pack` does
not know it yet, so pass the folder around as it is. Draw your own maps and write your own text: do not ship pictures
or data taken from the game's files.
