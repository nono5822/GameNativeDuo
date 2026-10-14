# Stardew Valley screen split: status and everything tried (2026-10-14)

Branch: `duo/stardew` (built on `duo/mod-library` = PR #3, plus the box64 fix from `duo/box64-label-fix`).
**Work in progress: the bottom screen does not show anything yet.**

## Goal (from the user)

Top screen: the game world, rendered natively for the top screen (1920x1080, more of the map visible, like an
ultrawide mod). Bottom screen: the HUD and all menus at the bottom screen's native resolution, with the world
continuing behind them; full touch (tap = click, drag-and-drop in the inventory, no mouse cursor appearing).

## What works

- Stardew runs in the fork (needed the box64 fix, see below). SMAPI 4.5.2 loads under GameNative.
- The Dual screen toggle installs SMAPI (bundled, LGPL, licence + notice alongside), our mod, switches the launch exe to
  `StardewModdingAPI.exe`, sets screen size 1920x2160 (`SplitLayout.NATIVE`), writes `layout.json`, and undoes all of
  it when switched off.
- The SMAPI mod (`duo/mods-src/stardew`) puts the world in the top 1920x1080 and the HUD/menus in the bottom
  1240x1080 region of the game window, correctly laid out (verified by screenshots and by a debug thumbnail).
- With a single renderer on the top screen, the top screen stays live (verified with a moving debug marker).

## Current blocker

Bottom screen stays black with the new single-renderer output. Log of the last run:
```
duo: second screen output 1240x1080, 6 images, present mode 1      <- swapchain created on the bottom surface
Second screen output attached (1240x1080)
```
So setup succeeds but frames either are not presented or not visible. Next steps to try:
1. Add a rate-limited log in `duoRenderSecondary()` (DuoSecondaryOutput.cpp): is it called, does `GetFenceStatus`
   return SUCCESS, does `AcquireNextImageKHR` (timeout 0) return SUCCESS/NOT_READY, what does `QueuePresentKHR` return.
2. Check the SurfaceView is visible: it sits under the Presentation window's `FrameLayout` with a black background;
   try `setZOrderOnTop(true)` / no parent background, and confirm via `dumpsys SurfaceFlinger` that the layer exists.
3. If acquire never succeeds with timeout 0 on Adreno, try a small timeout (1-2 ms) or FIFO.

## Timeline of attempts (what each test proved)

1. **Phase 1 (FILL layout, 1280x1835, no layout mod):** SMAPI loads, world fills both screens with different
   scaling. Top and bottom both live. (Two renderers: upstream renderer + a second "mirror" VulkanRenderer.)
2. **Native layout + mod:** UI correct on the bottom, top screen black. Added diagnostics:
   - world render target has content and is opaque (`lit 488/2079, opaque 2080`) -> not an alpha problem
     (an `OpaqueColorOnly` blend was added anyway; harmless).
   - a quarter-size thumbnail of the world drawn on the bottom region showed the farm -> mod drawing works.
   - magenta markers in the top region appeared on the top screen -> top crop works.
   - a per-frame moving marker + frame counter: **top screen frozen at frame 14** while the bottom kept updating.
3. **Root cause of the freeze:** the top renderer's Vulkan device faults (`Adreno-GSL log_gpu_snapshot` x3, then
   `BufferQueueProducer ... dequeueBuffer: attempting to exceed the max dequeued buffer count` forever; `QueueSubmit`
   fails silently and acquired images leak). With the mirror disabled (`debug.gnduo.nomirror=1` dev switch, since
   removed) the top screen stayed live -> **a second VkDevice reading the same game AHBs faults the first one.**
   Balatro and phase 1 happened to survive it (smaller buffers / timing / UBWC, unknown which).
4. **Tried:** changing the first-use AHB layout transition from `UNDEFINED` to `GENERAL` (theory: UNDEFINED lets the
   driver reset UBWC metadata of a buffer others still read). **Did not help** - top still froze when the bottom
   started. Reverted.
5. **Current design (single renderer, two outputs):** `DuoSecondaryOutput.cpp` adds a second swapchain on the bottom
   screen's surface to the *same* `VulkanRendererContext` (same device/queue/pipeline/imported textures) and redraws
   `frameDraws` with the bottom crop after each main present, never blocking (skips if fence busy / no free image).
   Java: `VulkanRenderer.duoSetSecondary/duoClearSecondary`; `SplitBottomPresentation` is now a plain `SurfaceView`
   that retries attaching every 500 ms until the native renderer is initialised (first attempt came 24 ms after
   session start and was refused). Result: top stays live (no more fault), bottom attaches but shows nothing.

## Side findings (fixed or noted)

- **D8 VerifyError** in upstream `XServerScreen` on full (CLI/CI) builds -> pinned `com.android.tools:r8:8.13.17`
  (committed, upstream candidate).
- **Box64 never extracted for Stardew:** GameNative's known config stores `box64Version = "0.3.7 (Default)"` (a dropdown
  label); fixed in `Container.getBox64Version()` (branch `duo/box64-label-fix`, upstream candidate).
- `libredirect` (native) still rewrites `/data/data/com.winlator/...` to `/data/data/app.gamenative/...` (official
  app's dir) - another hardcoded package path, not yet fixed.
- CI APK is signed with a different key than local builds (cannot install over each other).
- Steam session conflicts: launches hang at `signalAppLaunchIntent` or get `ForceCloseApp` (LoggedInElsewhere) when
  another Steam client on the account is active.
- `libvulkan_renderer.so` is a prebuilt in `app/src/modern/jniLibs`; this branch replaces it with our build (see
  `duo/native/README.md`). Upstream's original is the one in git history before this branch.

## Debug-only bits still in the code (remove before merging)

- `duo/mods-src/stardew/.../Patches.cs`: `LogDiagnostics`, the world thumbnail, magenta markers, moving green marker
  and frame counter in `RenderScreenBuffer_Prefix` (marked `DEBUG`). The bundled asset DLL is an older build without
  them; the device currently has the debug build in `Mods/GameNativeDuo-split/`.

## Useful commands

- Screenshots: `adb exec-out screencap -p -d 4630946441858561667` (top), `-d 4630946482288158084` (bottom).
- SMAPI log: `files/imagefs/home/xuser-STEAM_413150/.wine/drive_c/users/xuser/AppData/Roaming/StardewValley/ErrorLogs/SMAPI-latest.txt`
  (read with `adb shell run-as app.gamenative.duo sh <<'X' ... X`).
- Build the mod: `dotnet build -c Release -p:GamePath=<folder with Stardew DLLs + SMAPI>` (pull the game's
  `*.dll *.exe *.json` from the device; .NET SDK + `dotnet-runtime` needed on Arch).
- Verify the APK: `adb shell cmd package compile -m verify -f app.gamenative.duo` then grep logcat for `Verification error`.
- Note: debug installs go through an app bundle; `app/build/outputs/apk/...` is not what gets installed.
