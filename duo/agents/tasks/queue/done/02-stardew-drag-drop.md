# Task 02: Stardew drag and drop that feels natural

Owner: Opus · Thor needed: yes · Depends on: —

Read RULES.md and the Stardew notes in `duo/notes/` (native touch: `duo/notes/2026-10-19-ui-layout-native-touch.md`).
The user: "the item disappears as you drag, and when you drop it rarely ever actually places the object, you usually
need to tap somewhere on your screen". Expected: the item stays visible under the finger the whole drag (the game's
held-item sprite drawn at the touch point, on whichever screen the finger is), drop on finger-up places/swaps/merges
exactly like a mouse release, dropping outside a valid slot returns it, tap-to-pick/tap-to-place still works,
long-press = right-click behaviour stays. Find the cause in the `Mouse.GetState` postfix / bridge touch path (likely
the release frame is lost or the cursor position is not updated during the move). Test inventory, chests, toolbar,
shipping bin, crafting, across the hinge; record a short sequence of screenshots for each.

**Done 2026-10-28 (interactive session):** mod 0.4.0, see `duo/notes/2026-10-28-stardew-drag-drop.md`.
Left for task 10: shops, shipping bin, museum and tool-upgrade menus with drags; real-finger check by the user.
