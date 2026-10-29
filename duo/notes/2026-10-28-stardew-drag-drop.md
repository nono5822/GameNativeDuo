# 2026-10-28 Stardew Valley: natural drag and drop (mod 0.4.0)

User report: "the item disappears as you drag, and when you drop it rarely ever actually places the object, you
usually need to tap somewhere on your screen". Fixed in `duo/mods-src/stardew/.../TouchInput.cs`, verified on the Thor.

## Causes (found with the new test harness, every tick traced)

1. **Mouse.GetState was patched twice.** `GetState()` calls `GetState(window)`; with both patched, the outer postfix
   took the inner one's finger position for the "real" mouse, so after every lift touch decided a mouse was in use
   and handed the cursor back to the X pointer (parked at 284,368, on the top screen). The carried item is drawn at
   the cursor, so it vanished off screen, and the next tap was needed to get the cursor back. Now only the window
   overload is patched.
2. **A drop on an invalid target** (locked backpack row, outside the menu) left the item on the cursor; combined with
   (1) it looked like it disappeared. Now it goes back to the slot it came from.
3. **Lifting outside the menu clicked there**, which in Stardew throws the item on the ground. Now the drop click only
   happens inside the active menu (its inventories and trash can).
4. **Game cursor warps** (`Game1.setMousePositionRaw`, computed without the bottom-screen offset) moved the X pointer
   while a finger drove the cursor. Ignored while touch drives.
5. A press could be applied at a later finger position (moves in the same tick were consumed with it); touches were
   applied after SMAPI read the mouse, so the game saw them a tick late.

## Behaviour now

- Drag from a slot: the item follows the finger; lifting on a slot places, stacks or swaps it (a swap completes: the
  other item goes to the origin slot); an invalid target or outside the menu puts it back; nothing is ever thrown.
- Tap to pick, tap to place: unchanged (vanilla).
- Crafting: dragging a recipe crafts once and places the result where the finger lifts (no put-back: a second click
  on a recipe would craft again).
- Chests: the press moves the stack (vanilla); lifting on a slot of the player's inventory puts it in that slot.
  Chests keep their items packed, so a slot there is not honoured.
- Touch is applied when SMAPI reads the mouse (same tick); a press is seen exactly where the finger landed. Cost:
  one postfix per tick, reflection only on press/release.

## Tests (Thor, test save, clock frozen, saves checksummed before/after: identical)

`duo/agents/runs/2026-10-28-stardew-dnd/` (`cycle.sh` restarts and loads, `tests.sh` runs all, `r-*.png`): valid
drop, locked row (back), outside (back, not thrown), swap, taps, chest to inventory slot, crafting into a slot,
crafting released outside (held, visible under the finger, not thrown). 17 clicks exactly as designed; no spurious
hand-back to the mouse. Not yet tried with real fingers (adb swipes go through the same bridge path).

## Test harness

`%AppData%\GameNativeDuo\sdv-test.txt` (like Hollow Knight's): clock frozen; commands `menu inventory|crafting|chest`,
`close`, `trace on|off` (touch, mouse, held item per tick, every menu click), `state`. adb key events do not reach the
game; drive tests with bottom-screen taps/swipes and this file.

## Open

- Real-finger check by the user. Mouse users: the first real mouse move/click hands the cursor back (unchanged).
- Shops, the shipping bin, tool upgrades and the museum use other menus: drags there fall back to the generic
  "click where the finger lifts" (inside the menu); check them.

# Title screen over both screens (mod 0.5.0)

`TitleScreen.cs`: the title is one scene across both screens, like a dual-screen handheld's own title.
- Top screen: a second draw of `TitleMenu` at the top screen's size into its own render target (title only, freed
  when a save loads): sky, the sign with birds and leaves, the opening logos (centred on the top screen). Buttons,
  submenus, the cursor, the mute and windowed buttons are hidden in that pass; the sign stays while the load list or
  character creation is open below (vanilla slides it away). Fades to black are drawn on it too.
- Bottom screen: the game's own draw, scrolled 720 UI px further into the scene (never past its lower end, where the
  opening pan starts) so the sign is not repeated; buttons, the load list and every submenu unchanged, so taps are
  too. During the opening logos it shows the matching white, and a tap anywhere skips them.
- One sky: the top of the bottom screen starts with the gradient colour the top screen ends with (read from the
  game's own 264 px sky gradient, row computed from the live scroll), fading into the scene over 380 px; only once
  the top screen's edge is sky (not during the opening pan) and never over submenus.
- Verified on the Thor: intro, pan, logo drop, settled title, load list, character creation
  (`duo/agents/runs/2026-10-28-stardew-dnd/title-sheet.png`). No GPU hang (the title once hung when forcing
  `ShouldDrawOnBuffer`; this adds one render target and does not force it). Saves unchanged.
- Known: during the 3 s opening pan both screens show the hills (the scene has nothing further down).
