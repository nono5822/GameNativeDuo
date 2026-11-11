# Contributing to GameNative Duo

GameNative Duo is a fork of [GameNative](https://github.com/utkarshdalal/GameNative) that adds dual-screen support.
This page is about the fork. A problem that also happens in official GameNative (a game that does not start, a login
that fails) belongs with [GameNative](https://github.com/utkarshdalal/GameNative), not here.

## Ways to help

- **Report a bug or ask for a game**: open an [issue](https://github.com/nono5822/GameNativeDuo/issues/new/choose).
  Say which device, which game, and what both screens showed.
- **Share a layout**: arrange a game's interface in the layout editor and use Share. Layouts are data, not code, and
  go to the [community repository](https://github.com/nono5822/GameNativeDuo-community).
- **Make a mod for a game**: start from the SDKs in [`duo/sdk/`](duo/sdk/README.md) and the `duo` tool in
  [`duo/tools/duo`](duo/tools/duo). Mods are published through the community repository too.
- **Work on the app**: see below.

## Changing the app

1. Pick an [issue](https://github.com/nono5822/GameNativeDuo/issues), or open one first so the change can be
   discussed before you write it. Each issue says what "done" means.
2. Branch from `devkit` (the working branch) as `duo/<short-name>`. `master` mirrors upstream and is never changed
   here.
3. Keep the pull request small: one issue, one change. Open it into `devkit` and fill in the template. It asks what
   changed and why, which upstream files were touched, how it was tested, and what was **not** tested. Write
   `Closes #N`.
4. Say plainly what you could not check. Most of this project only shows its bugs on a real dual-screen handheld, so
   "not tried on a device" is useful to know and fine to write.

### Rules that keep the fork maintainable

- New code goes in new files under `app/src/main/java/app/gamenative/duo/`. Upstream files get one-line hooks marked
  `// GameNativeDuo`, and each one is listed in the table in [`CLAUDE.md`](CLAUDE.md). Upstream changes daily; every
  line we change in their files is a future merge conflict.
- Nothing in `app.gamenative.duo` may crash or stall a game: catch and log at every entry point (logcat tag `GNDuo`).
- The CPU is the bottleneck of an emulated game. No frame copies on the CPU, no polling, nothing that runs every
  frame without need.
- Mods only add code to games the player owns. Nothing that touches DRM or decrypts game files.
- Kotlin style: 140 columns, trailing commas, sorted imports (`.editorconfig`).

### Building and testing

```bash
./gradlew :app:assembleModernDebug                                              # the APK
./gradlew :app:testModernDebugUnitTest --tests 'app.gamenative.duo.*'           # the fork's unit tests
dotnet test duo/tools/duo/tests && dotnet test duo/sdk/csharp/GameNativeDuo.Sdk.Tests   # the tool and the C# SDK
```

A `local.properties` with dummy `POSTHOG_API_KEY` and `POSTHOG_HOST` values is enough to build. Architecture, the
device's details and debugging notes are in [`CLAUDE.md`](CLAUDE.md); the history of each feature is in
[`duo/notes/`](duo/notes).

## Licence

Contributions are accepted under the repository's licence (see [`LICENSE`](LICENSE)). The SDKs in `duo/sdk/` are MIT
so that any mod may use them.
