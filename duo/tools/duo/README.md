# duo

The developer tool for GameNative Duo mods. It starts a mod from a template, builds it, runs it against a simulator
on your PC (no handheld needed), talks to a real device, and checks and packs the result for the community
repository.

## Install

Download the file for your system from the [latest release](https://github.com/nono5822/GameNativeDuo/releases/latest):

| System | File |
|---|---|
| Windows (x64) | `duo-windows-x64.exe` |
| Linux (x64) | `duo-linux-x64` |
| macOS (Apple silicon) | `duo-macos-arm64` |
| macOS (Intel) | `duo-macos-x64` |

It is one file with nothing to install beside it. Rename it to `duo` (`duo.exe` on Windows) and put it somewhere on
your `PATH`. On Linux and macOS, make it executable first:

```bash
chmod +x duo-linux-x64 && mv duo-linux-x64 ~/.local/bin/duo
```

macOS may refuse to open a downloaded file that is not from an identified developer. If it does, run
`xattr -d com.apple.quarantine duo` once. `duo-tool-sha256.txt` on the release lists the checksums.

Check it:

```
$ duo version
duo 0.1.0 (Duo SDK 1.0)
```

The second number is the Duo SDK the tool carries: the one `duo new` copies into a mod and the simulator speaks.
The app shows its own SDK version in "Dual screen status"; a mod built on an older SDK keeps working.

### From source

With the [.NET 10 SDK](https://dotnet.microsoft.com/download):

```bash
dotnet run --project duo/tools/duo/src -- version         # run without installing
dotnet publish duo/tools/duo/src -c Release -r linux-x64  # or win-x64, osx-arm64, osx-x64: one native file
```

## One example: a LÖVE mod on the simulator

```
$ duo new --engine love score
Created score/ (love):
  src/main.lua
  src/duo.lua
  manifest.json

$ duo sim
duo sim: AYN Thor at 100%: window 1920x2160
  files:  ...
  bridge: 127.0.0.1:41709
  editor: http://127.0.0.1:8642/?k=BXRHX2
Run the game windowed at 1920x2160. Ctrl+C stops.
```

Leave the simulator running and start the game in a window of that size (for the template: `love score/src`). The
mod finds the simulator through the same `layout.json` the app writes on a handheld. Then, in another terminal:

```
$ duo info          # which mod connected, its engine, SDK and capabilities
$ duo elements      # the elements it declared for the layout editor
$ duo tap 620 540   # a finger on the bottom screen
$ duo check         # tries every feature the mod declares and reports what does not work
```

The `editor:` address opens the same layout editor the app serves to a PC, on the simulator.

## Commands

| Command | What it does |
|---|---|
| `duo new --engine love\|unity\|smapi\|native ID` | A working mod folder built on the SDK, with a manifest. |
| `duo build [DIR] [--game FOLDER]` | The mod's own build: `dotnet` for a `.csproj` (`FOLDER` holds the game's assemblies), `make`, or a syntax check of Lua files. |
| `duo sim [--profile thor] [--scale 1\|0.75\|0.5] [--dir DIR \| --prefix WINEPREFIX] [--port 8642]` | The app's side on your PC: writes `layout.json` and `ui.json`, runs the bridge, serves the editor and the Duo API. |
| `duo info`, `elements`, `logs [-f]`, `state [PREFIX]` | Read from the running simulator or device. |
| `duo tap X Y`, `duo command NAME [JSON]` | Send a touch or a command to the mod. |
| `duo screenshot [top\|bottom] [--out FILE]` | A picture of one screen. |
| `duo check` | The conformance check: every declared feature of every element. |
| `duo test SCRIPT` | Run a test script (taps, commands, `expect` lines); exit code 0 when every expectation held. |
| `duo dev --device MOD-ID [DIR]` | Push a mod folder to a handheld over adb; it loads at the next game start (developer mode on in the app). |
| `duo pack DIR [--write] [--sign [--key FILE]]` | Check a package against the community schemas; `--write` fills in hashes, `--sign` signs the manifest with your author key. |
| `duo keygen [--out FILE]` | A new author key (SSH ed25519). |
| `duo stubs DIR --out SET.zip` | Reference stubs of a game's assemblies (no code, no assets), for building a mod in CI. |
| `duo submit` | How to send a packed mod or layout to the community repository. |
| `duo mcp` | An MCP server (stdio) over the Duo API, for Claude Code and other agents. |
| `duo version`, `duo help` | The versions, and this list. |

The commands that read or send talk to the last `duo sim` started on this PC. For a handheld, start "Edit on PC" in
the game and pass its address and code: `--url http://HOST:8642 --code CODE` (or `DUO_URL` and `DUO_CODE`).

## More

- The SDKs the templates use: [`duo/sdk/`](../../sdk/README.md)
- The HTTP API behind these commands: [`duo/docs/duo-api.md`](../../docs/duo-api.md)
- What a mod must implement: [`duo/docs/engine-adapter-contract.md`](../../docs/engine-adapter-contract.md)
- Packages and code trust: [`duo/docs/community-packages.md`](../../docs/community-packages.md)
- Tests of the tool itself: `dotnet test duo/tools/duo/tests`
