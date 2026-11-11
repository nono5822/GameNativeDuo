# GameNative Duo SDKs

Libraries for dual-screen mods: a mod built on them puts its UI on the screens the user chooses, takes bottom-screen
touches, lists its elements in the layout editors, feeds companion cards and answers their buttons, without packing a
bridge packet by hand. MIT, so closed-source mods may use them. The protocol they implement is
`duo/docs/engine-adapter-contract.md`; the plan they come from is `duo/docs/devkit-platform.md`.

| SDK | For | File(s) | Tests |
|---|---|---|---|
| C# `GameNativeDuo.Sdk` | Unity (BepInEx), Stardew (SMAPI), any .NET mod; net46 + netstandard2.0, no engine references | `csharp/GameNativeDuo.Sdk/` | `dotnet test csharp/GameNativeDuo.Sdk.Tests` |
| Lua `duo.lua` | LÖVE / LuaJIT games (Balatro uses it) | `lua/duo.lua` | `luajit lua/tests/test_duo.lua` (from the repo root) |
| C `duo.h` | Native games through an ASI loader; header-only, Winsock or POSIX | `c/duo.h`, template `c/native-asi/` | `gcc -std=c99 -D_DEFAULT_SOURCE c/tests/test_duo.c` |

New here? Start with [your first dual-screen mod](../docs/quickstart.md).

## One API, three languages

| Idea | C# | Lua | C |
|---|---|---|---|
| Start, then call every frame | `Duo.Start("unity-mygame")`, `Duo.Tick(w, h)` | `duo.start{engine=...}`, `duo.update(w, h)` | `duo_start`, `duo_tick` |
| Where the screens are | `Duo.Layout`, `Duo.IsSplit`, `Duo.LayoutChanged` | `duo.layout`, `duo.is_split()` | `duo_get_layout()`, `duo_is_split()` |
| Bottom-screen fingers | `Duo.OnTouch(t => ...)` | `duo.on_touch(fn)` | `duo_on_touch(fn, user)` |
| An element for the editors | `Duo.Elements.Register(id, name, rectFn, features)` | `duo.elements.register(id, name, fn, opts)` | write `elements.json` in `duo_on_dump` |
| The user's rule | `Duo.Rule(id)`, `Duo.RulesVersion` | `duo.rule(id)`, `duo.rules_version` | read `ui.json` in `duo_on_rules` |
| Companion state | `Duo.Publish(key, value)`, `Duo.Wants(key)` | `duo.publish`, `duo.wants` | `duo_publish(key, json)`, `duo_wants` |
| Companion buttons, hotkeys | `Duo.OnCommand(name, args => CommandResult.Ok())` | `duo.on_command(name, fn)` | `duo_on_command`, `duo_command_result` |
| Logs in the app (logcat `GNDuo`, dev tools) | `Duo.Log(text)` | `duo.log(text)` | `duo_log(level, text)` |
| A short buzz | `Duo.Haptic(ms)` | `duo.haptic(ms)` | `duo_haptic(ms, strength)` |

The SDKs say hello with `engine/sdkX.Y`, so the app's Dual screen status can tell a user that a mod was built for an
older GameNative Duo. Everything new is a capability bit: a mod built on an older SDK keeps working.

## Rules every mod follows

- Act only while the window matches `layout.json`'s `screen` (the SDKs' `IsSplit`); a stale file is then harmless.
- The bridge's receive side only queues; game objects are touched on the game thread, inside `Tick`/`update`.
- State: only subscribed keys are sent (idle cost zero); keys go under your companion api's name (`mygame.`).
- Commands go through the game's own UI paths, never memory or save-file writes. Nothing that touches DRM.

## Examples (about 50 lines each)

- `examples/love-basic/main.lua`: a LÖVE game with a score element on the bottom screen, touch and a command.
- `examples/unity-basic/DuoExample.cs`: a BepInEx plugin that lists a game's UI object, applies the user's rule and
  publishes state.

## Using the C# SDK

Embed the sources (what the bundled mods do: one DLL, nothing to clash with when two plugins use different SDK
versions):

```xml
<PropertyGroup><DefineConstants>$(DefineConstants);DUO_SDK_EMBEDDED</DefineConstants></PropertyGroup>
<ItemGroup><Compile Include="path/to/GameNativeDuo.Sdk/*.cs" Link="Sdk/%(Filename)%(Extension)" /></ItemGroup>
```

or reference the package: `dotnet pack -c Release csharp/GameNativeDuo.Sdk` writes `GameNativeDuo.Sdk.1.0.0.nupkg`.

Not yet split out: `GameNativeDuo.Sdk.Unity` (UiRouter, CameraPlacer, TouchInjector as components) and
`GameNativeDuo.Sdk.Smapi`. Until then the Unity and Stardew adapters in `duo/mods-src/` are the reference.
