using System.Reflection;
using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>
/// `duo new --engine love|unity|smapi|native ID [--game steam-APPID]`: a working mod folder built on the SDK, with a
/// community manifest.json (`duo pack` checks it). Sources come from the SDK and its examples, embedded in duo.
/// </summary>
public static class Templates
{
    public static int New(Args a)
    {
        string engine = a["engine"] ?? throw new DuoException("duo new --engine love|unity|smapi|native ID");
        string id = a.Positional.FirstOrDefault() ?? throw new DuoException("duo new --engine " + engine + " ID   (e.g. yourname.mygame-dual-screen)");
        string dir = a["dir"] ?? id;
        if (Directory.Exists(dir) && Directory.EnumerateFileSystemEntries(dir).Any())
            throw new DuoException(dir + " exists and is not empty");
        Directory.CreateDirectory(dir);
        string name = id.Contains('.') ? id[(id.IndexOf('.') + 1)..] : id;
        var files = new List<string>();
        switch (engine)
        {
            case "love":
                Write(dir, "src/main.lua", Res("tpl/love/main.lua").Replace("love-example", "love-" + name), files);
                Write(dir, "src/duo.lua", Res("sdk/duo.lua"), files);
                break;
            case "unity":
                Write(dir, "src/Plugin.cs", Res("tpl/unity/Plugin.cs").Replace("example.duo.unity", id).Replace("unity-example", "unity-" + name), files);
                Write(dir, $"src/{name}.csproj", UnityProject, files);
                CopySdk(dir, files);
                Write(dir, "profile.json", ProfileStub(a["game"]), files);
                break;
            case "smapi":
                Write(dir, "src/ModEntry.cs", SmapiEntry.Replace("smapi-example", "smapi-" + name), files);
                Write(dir, "src/manifest.json", SmapiManifest.Replace("ID", id).Replace("NAME", name), files);
                Write(dir, $"src/{name}.csproj", SmapiProject, files);
                CopySdk(dir, files);
                break;
            case "native":
                Write(dir, "src/dllmain.c", Res("tpl/native/dllmain.c").Replace("#include \"../duo.h\"", "#include \"duo.h\"").Replace("native-template", "native-" + name), files);
                Write(dir, "src/duo.h", Res("sdk/duo.h"), files);
                Write(dir, "src/Makefile", "CC ?= x86_64-w64-mingw32-gcc\n" + name + ".asi: dllmain.c duo.h\n\t$(CC) -shared -O2 -Wall -o $@ dllmain.c -lws2_32 -static-libgcc\n", files);
                break;
            default:
                throw new DuoException("engines: love, unity, smapi, native");
        }
        Write(dir, "manifest.json", Manifest(id, name, engine, a["game"]).ToJsonString(new() { WriteIndented = true }) + "\n", files);
        Console.WriteLine($"Created {dir}/ ({engine}):");
        foreach (var f in files)
            Console.WriteLine("  " + f);
        Console.WriteLine("Next: duo build " + Path.Combine(dir, "src") + ", duo sim, run the game windowed; then duo check, duo pack " + dir + " --write.");
        return 0;
    }

    private static string Res(string name)
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name) ?? throw new DuoException("missing template " + name);
        using var r = new StreamReader(s);
        return r.ReadToEnd();
    }

    private static void CopySdk(string dir, List<string> files)
    {
        foreach (var n in Assembly.GetExecutingAssembly().GetManifestResourceNames().Where(n => n.StartsWith("sdk/cs/", StringComparison.Ordinal)))
            Write(dir, "src/Sdk/" + n["sdk/cs/".Length..], Res(n), files);
    }

    private static void Write(string dir, string rel, string text, List<string> files)
    {
        string path = Path.Combine(dir, rel);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, text);
        files.Add(rel);
    }

    private static JsonObject Manifest(string id, string name, string engine, string? game)
    {
        var m = new JsonObject
        {
            ["format"] = "gamenative-duo/package", ["version"] = 1, ["id"] = id,
            ["kind"] = "mod", ["packageVersion"] = "0.1.0",
            ["title"] = name, ["description"] = "What the bottom screen shows, in one sentence.",
            ["authors"] = new JsonArray("you"), ["license"] = "MIT",
            ["requires"] = new JsonObject { ["adapter"] = engine switch { "unity" => "bepinex-5", "smapi" => "smapi-4", "love" => "lovely", _ => "asi-loader" } },
            ["files"] = new JsonArray(new JsonObject { ["path"] = "build/README-replace-with-your-build-output", ["sha256"] = new string('0', 64) }),
        };
        if (game != null && game.Contains('-'))
            m["game"] = new JsonObject { ["store"] = game[..game.IndexOf('-')], ["appId"] = game[(game.IndexOf('-') + 1)..] };
        return m;
    }

    private static string ProfileStub(string? game) => new JsonObject
    {
        ["format"] = "gamenative-duo/game-profile", ["version"] = 1, ["game"] = game ?? "steam-0", ["engine"] = "unity-ugui",
        ["cameras"] = new JsonObject { ["ui"] = new JsonArray(), ["world"] = new JsonArray() },
        ["canvases"] = new JsonArray(), ["stayOnTop"] = new JsonArray(), ["elements"] = new JsonObject(),
    }.ToJsonString(new() { WriteIndented = true }) + "\n";

    private const string UnityProject = """
        <Project Sdk="Microsoft.NET.Sdk">
          <!-- Build: duo build, game option = Game_Data/Managed; BepInEx core: -p:BepInExPath=... -->
          <PropertyGroup>
            <TargetFramework>net46</TargetFramework>
            <LangVersion>latest</LangVersion>
            <DefineConstants>$(DefineConstants);DUO_SDK_EMBEDDED</DefineConstants>
            <ManagedPath Condition="'$(ManagedPath)' == ''">$(GamePath)</ManagedPath>
          </PropertyGroup>
          <ItemGroup>
            <PackageReference Include="Microsoft.NETFramework.ReferenceAssemblies" Version="1.0.3" PrivateAssets="all" />
            <Reference Include="BepInEx"><HintPath>$(BepInExPath)/BepInEx.dll</HintPath><Private>false</Private></Reference>
            <Reference Include="UnityEngine"><HintPath>$(ManagedPath)/UnityEngine.dll</HintPath><Private>false</Private></Reference>
            <Reference Include="UnityEngine.CoreModule" Condition="Exists('$(ManagedPath)/UnityEngine.CoreModule.dll')"><HintPath>$(ManagedPath)/UnityEngine.CoreModule.dll</HintPath><Private>false</Private></Reference>
            <Reference Include="UnityEngine.UI"><HintPath>$(ManagedPath)/UnityEngine.UI.dll</HintPath><Private>false</Private></Reference>
          </ItemGroup>
        </Project>
        """;

    private const string SmapiProject = """
        <Project Sdk="Microsoft.NET.Sdk">
          <!-- Build: duo build, game option = the Stardew Valley folder with SMAPI -->
          <PropertyGroup>
            <TargetFramework>net6.0</TargetFramework>
            <LangVersion>latest</LangVersion>
            <EnableModDeploy>false</EnableModDeploy>
            <EnableModZip>false</EnableModZip>
            <DefineConstants>$(DefineConstants);DUO_SDK_EMBEDDED</DefineConstants>
          </PropertyGroup>
          <ItemGroup>
            <PackageReference Include="Pathoschild.Stardew.ModBuildConfig" Version="4.4.0" />
          </ItemGroup>
        </Project>
        """;

    private const string SmapiManifest = """
        {
          "Name": "NAME",
          "Author": "you",
          "Version": "0.1.0",
          "Description": "A dual-screen mod for GameNative Duo.",
          "UniqueID": "ID",
          "EntryDll": "NAME.dll",
          "MinimumApiVersion": "4.0.0"
        }
        """;

    private const string SmapiEntry = """
        using GameNativeDuo.Sdk;
        using StardewModdingAPI;
        using StardewModdingAPI.Events;
        using StardewValley;

        /// <summary>A SMAPI mod on the Duo SDK: publishes the player's money for a companion card, answers one command.</summary>
        public sealed class ModEntry : Mod
        {
            public override void Entry(IModHelper helper)
            {
                Duo.LocalLog = text => Monitor.Log(text, LogLevel.Info);
                Duo.Start("smapi-example");
                Duo.OnCommand("example.ping", args => CommandResult.Ok("pong"));
                helper.Events.GameLoop.UpdateTicked += (_, e) =>
                {
                    Duo.Tick(Game1.graphics.PreferredBackBufferWidth, Game1.graphics.PreferredBackBufferHeight);
                    if (Context.IsWorldReady && Duo.Wants("example.money"))
                        Duo.Publish("example.money", Game1.player.Money);
                };
            }
        }
        """;
}
