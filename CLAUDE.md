# GameNativeDuo

A fork of [GameNative](https://github.com/utkarshdalal/GameNative) (Windows PC games on Android via Wine,
Box64/FEX, DXVK) that adds **dual-screen support for Android handhelds**, starting with the **AYN Thor**,
and grows into a **devkit** others can build bottom-screen tools and per-game mods on.

## Target device: AYN Thor

- Snapdragon 8 Gen 2 (Adreno 740), Android 13. Build the `modern` flavour.
- Top screen: 6" 1920x1080 AMOLED, 120 Hz. Android's **default** display (#0 "Built-in Screen"). The game runs here.
- Bottom screen: 3.92" 1240x1080 AMOLED, touch; reported as #4 "Screen-2", 1080x1240 portrait, **60 and 120 Hz** modes
  (runs at 120 by default; render at 60 to save power). Android reports it as a **secondary
  (presentation) display**. Other dual-screen devices (AYANEO Pocket DS, Retroid Dual Screen) are reversed,
  so code says "top/bottom" through device profiles, never raw display IDs.
- Ground truth for display layout: run a game, then `adb logcat -s GNDuo` or pull
  `/sdcard/Android/data/app.gamenative.duo/files/duo/displays.json`.

## Golden rule: keep upstream merges cheap

Upstream lands ~4-5 commits/day; `XServerScreen.kt` (~6,800 lines) changes almost daily.

- New code goes in **new files** under `app/src/main/java/app/gamenative/duo/` (package `app.gamenative.duo`)
  and `app/src/main/res/values/duo_*.xml`. A separate Gradle module was rejected: our code needs
  `XServer`, renderers and `Container` from `:app`, which would be a circular dependency.
- Touch upstream files only with **one-line hooks**, marked `// GameNativeDuo`. Never add fields to
  `Container.java`/`ContainerData`; store devkit settings in our own files keyed by container id
  (`duo/settings/DuoSettings.kt`, `files/duo/settings/`).
- **Every GameNative Duo setting goes in the "GameNative Duo" tab** of the container settings dialog, right after
  "Emulation" (`duo/settings/DuoSettingsTab.kt`; add a field to `DuoContainerSettings` and a row to
  `DuoSettingsTabContent`). The global default config edits `defaults.json`, used by containers without their own file.
- Prefer changes that upstream would accept, and offer them upstream (see "Upstream candidates").
- Sync by merging upstream release tags into `devkit` (merge, never rebase a published branch).

### Every upstream file we modify (keep this list current)

| File | Change |
|---|---|
| `ui/screen/xserver/XServerScreen.kt` | import + one `DualScreenHost.attach(...)` line after the external-display controllers start |
| `app/build.gradle.kts` | `applicationId = "app.gamenative.duo"` |
| `build.gradle.kts` (root) | `buildscript` pins D8/R8 `com.android.tools:r8` (see Known issues) |
| `com/winlator/renderer/VulkanRenderer.java` | `duoViewRect` crop in `updateTransform` (right after the surface guard, before upstream's effective-screen scaling; uses the full `xServer.screenInfo`), `setDuoViewRect`, `duoSetSecondary`/`duoClearSecondary` + their natives |
| `cpp/winlator/VulkanRendererContext.{h,cpp}` | declarations, `duoRenderSecondary()` after both present paths, `duoWaitSecondary()` before the `deleteQueue` flush, `duoDestroySecondary()` in the destructor (new code in `DuoSecondaryOutput.cpp`) |
| `cpp/CMakeLists.txt` | `DuoSecondaryOutput.cpp` source line |
| `modern/jniLibs/arm64-v8a/libvulkan_renderer.so` | **rebuilt by us** (see `duo/native/README.md`); rebuild after upstream renderer changes |
| `AndroidManifest.xml` | 3 launcher labels -> `@string/duo_app_name` |
| `ui/component/QuickMenu.kt` | five lines marked `// GameNativeDuo`: a `DUO` tab id (9: upstream's ids end at `GAMESCOPE = 8`; take the next free one after merges), rail button, title, list entry and content (`duo/ui/DuoQuickMenu.kt`) |
| `ui/theme/Color.kt` | `PluviaPrimary`/`customPrimary`/`PluviaPurple` -> logo blue `#5B8DF6`, `PluviaPink` -> logo purple (values only, marked) |
| `com/winlator/container/Container.java` | `AppPaths` for default drives/MEDIACONV paths; `setDrives`/`setEnvVars` rewrite old paths; `getBox64Version` strips labels like " (Default)" |
| `com/winlator/core/DXVKHelper.java`, `WineUtils.java` | `AppPaths` instead of `/data/data/app.gamenative` |
| `xenvironment/components/BionicProgramLauncherComponent.java` | `AppPaths` for gamepad mem files; sets `EVSHIM_BASE_PATH` |
| `com/winlator/winhandler/WinHandler.java` | sets `EVSHIM_BASE_PATH` in the app process before `loadLibrary("evshim")` (else the controller is dead) |
| `ui/component/dialog/ContainerConfigDialog.kt` | `containerId` param, Duo draft, tab inserted after Emulation, commit on Save, dirty check |
| `ui/PluviaMain.kt`, `ui/screen/library/appscreen/BaseAppScreen.kt` | pass `containerId` to `ContainerConfigDialog` |
| `ui/enums/LibraryTab.kt` | `DUAL_SCREEN` tab entry (games with a bundled dual-screen mod) |
| `ui/model/LibraryViewModel.kt` | one filter line for the `DUAL_SCREEN` tab |
| `ui/screen/library/LibraryAppScreen.kt` | `DualScreenToggle(...)` next to the Play button |
| `.github/workflows/{app-release-signed,tagged-release,issues-contributors-only}.yml` | jobs guarded to run only in upstream repo |

## Why `app.gamenative.duo` and `AppPaths`

The fork needs its own applicationId to install beside official GameNative. Upstream hardcoded
`/data/data/app.gamenative/...` in several places (and community configs from GameNative's servers contain
such paths), which breaks under any other id. `com.winlator.core.AppPaths` derives paths from
`BuildConfig.APPLICATION_ID` and `AppPaths.rewrite()` fixes stored strings. It is a no-op for upstream's id,
so it is an **upstream candidate** (it also fixes their `.gold` variant). Keep the `app.gamenative` prefix in
the id: prebuilt `libkgslshim.so` matches on that substring.

## Architecture (planned, in build order)

1. **Bottom-screen panels**: Android UI in a non-focusable `Presentation` on the secondary display
   (existing pattern: `app/gamenative/externaldisplay/`). The controller must always stay with the game.
2. **Region views**: crop rectangles of the game frame onto the bottom screen; second render thread at the
   bottom screen's 60 Hz; **zero-copy** (share `AHardwareBuffer`s, never `glReadPixels`); redraw only on
   change. Plus top-screen masks, a touch layout editor, and touch passthrough (tap -> click at source coords).
   **Started**: split mode `region` (`split/RegionView.kt`, `RegionPanel.kt`): one region, pan/zoom editor in game,
   taps click at source coords; works for any game without a mod (Isaac, Dead Cells presets).
3. **Window routing**: send specific X windows (by title/class) to the bottom screen. Frames already arrive
   zero-copy via DRI3 `AHardwareBuffer`s + the Present extension.
4. **Bridge**: local socket between Windows-side mods (inside Wine) and Android panels; compact binary
   messages, state capped ~30/s, commands back to the game; native panels from a JSON schema, WebView optional.
   **Started**: UDP on 127.0.0.1 (`duo/bridge/DuoBridge.kt`) carrying native touch, UI-layout reloads and element
   lists; spec in `duo/docs/engine-adapter-contract.md` (what every engine adapter implements).
5. **Engine adapters**: Ren'Py, RPG Maker MV/MZ, Unity (uGUI/UI Toolkit; uGUI started, see below), Godot, Unreal (UMG); later a DXVK
   "UI split" that sends UI draws to a separate layer.
6. **Community packages**: Git-based index, per-game presets that auto-apply (data-only by default, code mods
   need consent), device profiles, signing, compatibility reports. **Started**: repo `nono5822/GameNativeDuo-community`
   (schemas, validator, signed index on GitHub Pages); design and app-side plan in `duo/docs/community-packages.md`.

Performance rules: the CPU is the bottleneck (Box64/FEX), so keep devkit work off it: low-priority threads,
no CPU frame copies, data over pixels, idle at 0 fps when nothing changes.

## Dual-screen mods (per game)

- Bundled in `app/src/main/assets/duo/mods/<id>/` (the source of truth), listed in `duo/mods/DuoModCatalog.kt`.
- The "Dual screen" toggle next to Play runs `DuoModInstaller`: copies files into the game folder and the Wine
  prefix, sets the container's screen size (`ScreenSplit.recommendedFor`) and `WINEDLLOVERRIDES`, turns on screen
  split, and records the previous values in `files/duo/mods/<containerId>.json` so turning it off restores them.
  After an app update the first session start refreshes the mod's APK files in the game (`refreshIfUpdated`; files
  the user changed are left alone). Hand-pushed test DLLs therefore count as "changed": push again after updates.
- Balatro: Lovely v0.10.0 (`winmm.dll`, MIT, licence alongside) + the `GameNativeDuo-split` Lua mod (engine
  `balatro-love`: bridge, native touch, rules for Hand / Consumables / Hand info / Menus). NATIVE split at 75% by default
  (`DuoGameMod.splitScale`: the present cost of the frame grows with its size). Objects really live below the room, so
  vanilla's "on screen" checks are patched to `GNDuo.room_h()` / `GNDuo.vanilla_y()`; shaders are recompiled per
  screen. Full list of vanilla assumptions and the long-run checklist: `duo/notes/2026-10-20-balatro.md`. Testing must
  set `G.F_NO_ACHIEVEMENTS` and seeded runs: GameNative uploads achievements to Steam live.
- Stardew Valley: SMAPI + `duo/mods-src/stardew` (world continues across both screens, UI on the bottom). Never force
  `Game1.ShouldDrawOnBuffer` on the title/loading screens: it hangs the GPU (see `duo/notes/`).
- Physical screen alignment (`ScreenCalibration`: hinge gap, pixel-density scale, shift) is per device, defaults in
  `DeviceProfile` (Thor: gap 249, scale 1.142, shift 0), tuned in-game with a two-finger long press on the bottom
  screen, saved in `files/duo/calibration/<profile>.json`, and written into `layout.json` on every session start and
  Dual screen toggle (mods re-read it live).
- Unity games: BepInEx 5 (MIT, loads via `winhttp.dll`, override `winhttp=n,b`) + the generic plugin in
  `duo/mods-src/unity`, both shared by every Unity game (`assets/duo/mods/shared/`). `CameraPlacer` holds game cameras
  in the top part and the game's own UI cameras (UI-layer only, named like a UI camera, or named by a quirk) in the
  bottom part, every frame, writing only on change (tk2d cameras through their settings); `UiRouter` moves
  screen-space canvases to our bottom UI camera and applies the UI layout to uGUI elements and to sprite HUDs under a
  game UI camera; `Quirks.cs` holds per-game patches found by type name (Subnautica, Team Cherry). Needs the Mono
  backend (`*_Data/Managed`, no `GameAssembly.dll`). Crosshairs/reticles stay on top by name. Compile-check against a
  game: `-p:ManagedPath=<game>_Data/Managed` (Unity 2021+ needs its `netstandard.dll`, picked up automatically).
  Bundled: Slime Rancher, Subnautica, Hollow Knight (plus its own plugin `duo/mods-src/unity/GameNativeDuo.HollowKnight`:
  live map on the bottom screen, its settings are the "Live map" element of the UI layout, see
  `duo/notes/2026-10-27-hollow-knight.md`); community (experimental): Silksong, Risk of Rain 2.
  The shared DLL is built against Slime Rancher's Managed (oldest Unity). HK tests: `hk-test.txt` harness (no saves).
- **UI layout** (per game, user-editable): each UI element on the bottom screen, the top screen or hidden, resized and
  shifted. `duo/ui/` (store, controller, editor), user layouts in `files/duo/ui/<game>.json`, presets bundled as
  `assets/duo/mods/<mod>/ui-layout.json`, handed to the mod as `ui.json`. The editor opens with a two-finger long press
  on the bottom screen -> "Edit UI layout". A mod can add its own element with settings (`screens`, `options` in
  `elements.json`, the choice in the rule's `options`; contract "Plugin elements"): both editors list it first.
- **Bottom screen look** (part of the UI layout, so it is shared with layouts): background `game` / `blur` / `color` /
  `image` with dim (drawn by the mod on the GPU, announced in HELLO capabilities 8/16/32: Stardew all, Unity color and
  image), and widgets (FPS, CPU, GPU, temperature, battery, clock) drawn by the app (`duo/ui/WidgetLayer.kt`, reads
  GameNative's own session metrics, redraws once a second, nothing when none are placed).
- **Where to edit**: two-finger long press on the bottom screen (Edit UI layout, Edit on PC, Line up screens), or the
  GameNative Duo tab of GameNative's in-game menu (Back button; `duo/ui/DuoQuickMenu.kt`). Note: GameNative pauses
  the game while its menu is open, so the mod's hellos stop; use `UiLayoutController.modSeen`, not `canEdit`, there.
- **PC editor** (`duo/pc/`): started from either menu, a small HTTP server on the device's Wi-Fi address (port 8642,
  6-character pairing code shown on the bottom screen with a QR code, stops after 20 wrong codes, never in the
  background). The web app is `assets/duo/pc-editor/index.html` (one file, no dependencies): both screens from
  PixelCopy snapshots, drag elements between screens, background, widgets, and Share (opens the community repo's
  share form on the PC, the same link as the app's Share button; background images stay private).
- **Native touch**: bottom-screen fingers go over the bridge into the engine's UI (Unity: `EventSystem`; Stardew: the
  game's mouse via a `Mouse.GetState` postfix, X pointer untouched), never through the X mouse. Games without a bridge
  mod get mouse clicks. Setting "Native touch on the bottom screen".
- **Split resolution** (settings: 100/75/50%): the tall window is twice a 1080p frame at 100%; 75% is about one.
  Changing it resizes the container (and the mod's undo record) on save; Unity games need their in-game resolution
  set to the new window once.
- **Community packages**: repo `nono5822/GameNativeDuo-community` (schemas, validator, signed index on Pages, share bot,
  ratings from discussions). App: `duo/community/` (Community button next to the Dual screen switch; also shown for
  games that only have community packages). Code mods install after a consent dialog through `DuoModInstaller`.
  Design: `duo/docs/community-packages.md`. Index signing key: repo secret + `~/.config/gamenative-duo/` (maintainer).
- `layout.json` (`%AppData%\GameNativeDuo\` in the prefix) is the contract with in-game mods: `screen`, `top`,
  `bottom` (window px, origin top-left), plus `gap`, `bottomScale`, `bottomShift`, `bridge.port`. Mods act only when
  the window matches `screen`. `ui.json` (UI layout) and `elements.json` (for the editor) sit next to it.
- Mods only add code to games the user owns; never anything that touches DRM or decrypts game files.

## Branding

App icon and `colorPrimary` are overridden in `app/src/modern/res/` (flavour resources win over `main`, no upstream
edit); colours, icon geometry and the banner source: `duo/branding/README.md`.

## Conventions

- Logcat tag `GNDuo` (Timber). Nothing in `app.gamenative.duo` may crash or stall a game session: catch and
  log at every entry point.
- Branches: `master` mirrors upstream untouched; `devkit` is the working branch; features go in `duo/<name>`
  branches with a PR into `devkit`.
- Kotlin style is enforced by kotlinter (`.editorconfig`, android_studio style, 140 cols, trailing commas).

## Build, install, debug

```bash
./gradlew :app:assembleModernDebug          # APK: app/build/outputs/apk/modern/debug/
./gradlew :app:installModernDebug           # build + install on the connected device
adb logcat -s GNDuo                         # devkit logs
./gradlew :app:lintKotlinMain 2>&1 | grep duo/   # style check for our code (upstream has ~10k violations, so the full task always fails)
```

- CI: `.github/workflows/duo-debug-build.yml` builds the APK on pushes to `devkit`/`duo/**` (artifact on the run);
  `duo-upstream-check.yml` nightly test-merges upstream `master` (needs `devkit` as the default branch).
- All builds sign with `duo/debug.keystore` (debug-only, deliberately committed) so CI and local APKs update
  each other. For local builds: `cp duo/debug.keystore ~/.android/debug.keystore` (back up your own first).
- A `local.properties` with dummy `POSTHOG_API_KEY`/`POSTHOG_HOST` is enough to build.
- Official GameNative can be installed alongside and logs the same tags (`evshim`, `WinHandler`, ...). Check
  which app a log line comes from: `adb logcat --pid=$(adb shell pidof app.gamenative.duo)`.

### Mods and native code

```bash
# In-game mods (references come from the game; pull them from the device's game folder first):
dotnet build -c Release -p:GamePath=<Stardew folder incl. SMAPI>      # duo/mods-src/stardew/...
dotnet build -c Release -p:ManagedPath=<Game_Data/Managed> -p:BepInExPath=<BepInEx/core>   # duo/mods-src/unity/...
# then copy the DLL into app/src/main/assets/duo/mods/<id>/ (the bundled copy is what the Dual screen toggle installs)
```

- Game files on the device: `adb shell run-as app.gamenative.duo ...` under
  `/data/user/0/app.gamenative.duo/Steam/steamapps/common/<game>`; the prefix is
  `files/imagefs/home/xuser-<containerId>/.wine/drive_c`. Logs: SMAPI `.../AppData/Roaming/StardewValley/ErrorLogs/`,
  BepInEx `<game>/BepInEx/LogOutput.log`.
- `libvulkan_renderer.so` (bottom-screen output) is rebuilt per `duo/native/README.md` after any change in
  `app/src/main/cpp/winlator/`.
- Wireless adb: `export ANDROID_SERIAL="$(duo/agents/thor-wifi.sh)"` (plug in once per Thor boot, then unplug; or
  `thor-wifi.sh pair HOST:PORT CODE` once with Wireless debugging). Gradle installs and all `adb` calls then go over Wi-Fi.
- Screenshots per screen: `adb exec-out screencap -p -d 4630946441858561667` (top), `-d 4630946482288158084` (bottom).
- Touch the bottom screen from adb: `adb shell input -d 4 tap|swipe ...`; multi-touch (e.g. the two-finger menu) needs
  `sendevent` on `/dev/input/event5` (`fts_ts_3`), see `duo/notes/2026-10-19-ui-layout-native-touch.md`.

### Debugging freezes and GPU hangs

- A frozen screen with `BufferQueueProducer ... dequeueBuffer: attempting to exceed the max dequeued buffer count`
  in logcat means a GPU context died. Check `adb shell dmesg | grep -A1 "GPU hang detected"`: the pid shows whose
  context it was. The game's DXVK work is attributed to the **app's** pid too, so "our pid" does not mean "our
  renderer". 3 hangs within 2 s ban the context (`gpu fault threshold exceeded`); fewer recover silently.
  `CP: AHB bus error` lines are constant background noise.
- Reproduce without the user: launch a game by intent and poll dmesg for the new pid:
  `adb shell am start -a app.gamenative.LAUNCH_GAME -n app.gamenative.duo/app.gamenative.MainActivity --ei app_id <steam id> --es game_source STEAM`.
  Bisect by switching mod features off between launches (this is how the Stardew hang was found).

## Local Qwen worker

A local Qwen3.8-27B runs on the PC (`duo/agents/start-qwen.sh`) and is reachable through the `qwen` MCP server.
It is there to keep bulk text out of your context. It is a 3-bit model: treat everything it writes as a draft.

**Logs: never read a long log yourself.**
1. Save logs to files first: `adb logcat -d > duo/agents/runs/<run>/logcat.txt`, pull `BepInEx/LogOutput.log`
   with `run-as`, `adb shell dmesg > .../dmesg.txt`.
2. `triage_log` on them (exact, free). It knows the GPU-hang, dex-verify, BepInEx, SMAPI and Harmony patterns
   and folds the `Frames (...)` lines into one table.
3. Only if the cause is not obvious: `qwen_summarize_log` with a `focus` saying what the test checked.
4. Before a fix or a conclusion that matters, read the cited lines yourself (Read with offset/limit).

**Delegate to Qwen**
- Unit tests for pure logic in `app.gamenative.duo` (`ScreenCalibration`, `LayoutFile`, `UiLayout`, `RegionView`,
  `CommunityIndex`, `DeviceProfile`): `qwen_draft` with the class and one existing test as context, then
  `./gradlew :app:testModernDebugUnitTest --tests 'app.gamenative.duo.*'`, then read the diff.
- Compile errors confined to one file under 800 lines: `qwen_fix_build` (max 3 attempts) before fixing by hand.
- New Unity game catalog entries: `qwen_draft` with `DuoModCatalog.kt` and an existing entry's preset as context.

**Keep for yourself**
- Harmony patches and engine adapters (the `ref` argument rule, hidden fields like `Toolbar.yPositionOnScreen`).
- `duo/native`, the renderer, swapchain and GPU-hang work, `DuoBridge` protocol changes.
- `XServerScreen.kt` and every upstream file in the "Every upstream file we modify" list.
- Performance conclusions and anything that decides what to test next.

**Rules**
- Never commit Qwen-written code without a passing build or test and a read of the diff.
- If a qwen tool says the server is unreachable, carry on yourself; do not retry in a loop.
- Usage is logged to `duo/agents/qwen-usage.jsonl` (tokens Qwen processed instead of you).
- End every unattended run with a note in `duo/notes/<date>-agent-run.md` in the usual shape: what was tested,
  bugs found and fixed, what still needs a person.

## Known issues / open questions

- Work in progress and its full history: `duo/notes/` (start with the newest file). Ranked list of games to support:
  `duo/futuregames.md` (from GameNative's Thor play data).
- `cp`/`mv` are aliased to interactive mode in this shell: use `\cp -f` / `\mv -f` in scripts.
- Unity games remember their last resolution: after turning Dual screen on, set the in-game resolution to the tall
  window (1920x2160) once, or the game renders centred and unsplit. Could be automated (Unity's `-screen-width`/
  `-screen-height` arguments or its registry keys).
- Unity split, untested: aiming may be off if a game aims at the window centre (the hinge) instead of the camera's.
- A plain `View` measured "wrap content" takes all the height it is offered: give spacers a fixed size
  (`DuoUi.add` does).
- Kotlin runs property initializers and `init` blocks in file order: an `init` that uses properties declared below it
  sees them null.
- `formatKotlinMain` rewrites the whole module (upstream too): restore every non-duo file it touched with `git checkout`.
- Harmony does not write `__args` changes back to value arguments: change arguments with `ref` parameters.
- Stardew died at "Waiting for game to launch" on the first launch after each APK install (2/2, later launches fine;
  before SMAPI reads mods, so not our mod). Retry. See `duo/notes/2026-10-20-device-testing.md`.
- Bridge sockets must bind IPv4 127.0.0.1 explicitly: `InetAddress.getLoopbackAddress()` is `::1` on the Thor.

- AGP 8.8's bundled D8 turns upstream's giant `XServerScreen` composable (on full, non-incremental compiles: CLI, CI)
  into dex that ART rejects (`VerifyError ... register vN has type Conflict`) the moment a game launches. Incremental
  Android Studio builds dodged it by luck. Fixed by pinning `com.android.tools:r8` in the root `build.gradle.kts`.
  Check a build with `adb shell cmd package compile -m verify -f app.gamenative.duo` + `adb logcat | grep 'Verification error'`.
  Upstream candidate.
- The CI artifact did not install over a local build (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so CI and local signing
  currently differ despite `duo/debug.keystore`; investigate before relying on CI APKs.

- Upstream v1.4.0 added a **Gamescope display server** (`Container.displayServer`, Graphics tab), the default for
  new Bionic containers (older ones migrate to `x11`). Under Gamescope the X renderer never presents, so screen
  split, bottom-screen output and region views do not work: the Dual screen toggle sets `x11` (undo record
  `previousDisplayServer`) and `DualScreenHost` logs a warning. A user who switches back to Gamescope by hand loses
  the split; a Gamescope-native bottom-screen path is future work.
- Deep-link schemes (`gamenative://`, `app.gamenative://` for Nexus login) are shared with official GameNative,
  so Android may ask which app to open them with.
- Frontends (Beacon, ES-DE) must target package `app.gamenative.duo` to launch games in the fork.
- Some upstream unit tests assert `/data/data/app.gamenative` paths; under the duo id they may fail.
- The fork still uses GameNative's servers (`api.gamenative.app`, `downloads.gamenative.app`). Fine for personal
  testing; **get the maintainers' permission or run our own before any public release.**
- Never add anything that bypasses or weakens DRM.

## Upstream candidates

- The D8/R8 pin (or an AGP bump) for the `XServerScreen` VerifyError.
- `Container.getBox64Version()` label stripping: GameNative's known config for Stardew Valley stores "0.3.7 (Default)",
  so no box64 gets extracted and x86_64-Proton containers die instantly (`unable to open file .../usr/bin/box64`).

- `AppPaths` (package-independent data paths) — fixes upstream's `.gold` build too.
- A "second display host" extension point + renderer frame-export hook, so the devkit could become a plugin.
