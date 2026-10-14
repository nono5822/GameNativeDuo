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
| `com/winlator/renderer/VulkanRenderer.java` | `duoViewRect` crop in `updateTransform`, `setDuoViewRect`, `duoSetSecondary`/`duoClearSecondary` + their natives |
| `cpp/winlator/VulkanRendererContext.{h,cpp}` | declarations, `duoRenderSecondary()` after both present paths, `duoWaitSecondary()` before the `deleteQueue` flush, `duoDestroySecondary()` in the destructor (new code in `DuoSecondaryOutput.cpp`) |
| `cpp/CMakeLists.txt` | `DuoSecondaryOutput.cpp` source line |
| `modern/jniLibs/arm64-v8a/libvulkan_renderer.so` | **rebuilt by us** (see `duo/native/README.md`); rebuild after upstream renderer changes |
| `AndroidManifest.xml` | 3 launcher labels -> `@string/duo_app_name` |
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
3. **Window routing**: send specific X windows (by title/class) to the bottom screen. Frames already arrive
   zero-copy via DRI3 `AHardwareBuffer`s + the Present extension.
4. **Bridge**: local socket between Windows-side mods (inside Wine) and Android panels; compact binary
   messages, state capped ~30/s, commands back to the game; native panels from a JSON schema, WebView optional.
5. **Engine adapters**: Ren'Py, RPG Maker MV/MZ, Unity (uGUI/UI Toolkit), Godot, Unreal (UMG); later a DXVK
   "UI split" that sends UI draws to a separate layer.
6. **Community packages**: Git-based index, per-game presets that auto-apply (data-only by default, code mods
   need consent), device profiles, signing, compatibility reports.

Performance rules: the CPU is the bottleneck (Box64/FEX), so keep devkit work off it: low-priority threads,
no CPU frame copies, data over pixels, idle at 0 fps when nothing changes.

## Dual-screen mods (per game)

- Bundled in `app/src/main/assets/duo/mods/<id>/` (the source of truth), listed in `duo/mods/DuoModCatalog.kt`.
- The "Dual screen" toggle next to Play runs `DuoModInstaller`: copies files into the game folder and the Wine
  prefix, sets the container's screen size (`ScreenSplit.recommendedFor`) and `WINEDLLOVERRIDES`, turns on screen
  split, and records the previous values in `files/duo/mods/<containerId>.json` so turning it off restores them.
- Balatro: Lovely v0.10.0 (`winmm.dll`, MIT, licence alongside) + the `GameNativeDuo-split` Lua mod.
- Stardew Valley: SMAPI + `duo/mods-src/stardew` (world continues across both screens, UI on the bottom). Never force
  `Game1.ShouldDrawOnBuffer` on the title/loading screens: it hangs the GPU (see `duo/notes/`).
- Physical screen alignment (`ScreenCalibration`: hinge gap, pixel-density scale, shift) is per device, defaults in
  `DeviceProfile`, tuned in-game with a two-finger long press on the bottom screen, and written into `layout.json`.
- Mods only add code to games the user owns; never anything that touches DRM or decrypts game files.

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

## Known issues / open questions

- Work in progress and its full history: `duo/notes/` (start with the newest file).

- AGP 8.8's bundled D8 turns upstream's giant `XServerScreen` composable (on full, non-incremental compiles: CLI, CI)
  into dex that ART rejects (`VerifyError ... register vN has type Conflict`) the moment a game launches. Incremental
  Android Studio builds dodged it by luck. Fixed by pinning `com.android.tools:r8` in the root `build.gradle.kts`.
  Check a build with `adb shell cmd package compile -m verify -f app.gamenative.duo` + `adb logcat | grep 'Verification error'`.
  Upstream candidate.
- The CI artifact did not install over a local build (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so CI and local signing
  currently differ despite `duo/debug.keystore`; investigate before relying on CI APKs.

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
