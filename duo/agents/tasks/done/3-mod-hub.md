# Task 3: one "Dual screen" control per game, an explorer for its mods, official badge, better voting

Read `duo/agents/tasks/queue/RULES.md`, `duo/docs/community-packages.md`, then the code: `duo/mods/DualScreenToggle.kt`,
`duo/community/CommunityPackagesSheet.kt`, `CommunityPackages.kt`, `CommunityIndex.kt` and the hook in
`ui/screen/library/LibraryAppScreen.kt`. Community repo: `../GameNativeDuo-community` (schemas, validator, index, share
bot, ratings from discussions).

## The user's requests
1. **Merge the Dual screen toggle and the Community button** into one control next to Play: a switch (dual screen
   on/off with the currently selected mod) joined to a dropdown chevron that opens an explorer of every mod available
   for this game: bundled ones and community packages. Selecting a mod in the explorer makes it the one the switch
   applies; switching mods while on must uninstall/restore the previous one cleanly through `DuoModInstaller`
   (undo record) before installing the next. Games with only community packages get the same control.
2. **Official mods look official**: a distinct badge/colour for GameNative Duo mods (logo blue `#5B8DF6`, see
   `duo/branding/README.md`) with a short label. "Official" must be derived from the bundled catalog or the
   maintainer's index signature, **never** from a field a package author can set.
3. **Polish voting**: study how ratings work now (discussions in the community repo, the bot, the app's display).
   Make it trustworthy and useful: one vote per GitHub account, no self-votes, counts shown with the average, sort by
   rating/recency/compatibility, per-device compatibility reports ("works on AYN Thor") shown next to the rating,
   a one-tap way from the app to vote or report (opens the right page on the phone or via QR for the PC). Keep the
   repo's validator and CI green; add tests for the rating aggregation.

## Explorer UX (aim for the best in the field)
- Each entry: name, author, official/community/code-mod badges, rating + votes, device compatibility, version and
  "update available", size, what it changes (screens, UI layout, native touch), installed/selected state.
- Detail view: description, screenshots if the package has them, changelog, permissions/consent for code mods (the
  existing consent dialog), install/update/remove, open in browser, share.
- Offline: cached index with its age shown; never block the game screen on the network.
- Fully usable with the D-pad (focus order, visible focus, Back closes), and touch. Follows the app's theme.
- Snappy: no jank opening it; load images lazily.

Verify on the Thor with screenshots of every state (off, on, explorer open, detail, consent, switching mods, offline,
no packages). Upstream files: keep it to the existing one-line hook in `LibraryAppScreen.kt`.
