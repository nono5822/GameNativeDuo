using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>`duo`: one program, a CLI and a local server (duo/docs/devkit-platform.md, "Dev tools").</summary>
public static class Program
{
    public const string Version = "0.1.0";

    private const string Help = """
        duo - GameNative Duo's developer tool

          duo sim [--dir DIR | --prefix WINEPREFIX] [--profile thor] [--scale 1|0.75|0.5] [--game ID] [--port 8642]
                Simulates the app for a game on this PC: writes layout.json / ui.json into the game's
                %AppData%\GameNativeDuo, runs the bridge, serves the editor UI and the Duo API.
          duo info | elements | logs [-f] | check | tap X Y | command NAME [JSON] | state [PREFIX]
          duo screenshot [top|bottom] [--out FILE]
                Talk to a running sim or device (--url http://HOST:8642 --code CODE; defaults: the last sim).
          duo test SCRIPT          Run a test script against the sim or device.
          duo new --engine love|unity|smapi|native ID    Start a mod from a template.
          duo build [DIR] [--game FOLDER]
                The mod's own build: dotnet for a .csproj (FOLDER holds the game's assemblies), make, or a
                syntax check of its Lua files.
          duo dev --device MOD-ID [DIR]
                Push a mod folder to a device over adb; it loads at the next game start (developer mode on).
          duo pack DIR [--write] [--sign [--key FILE]]
                Check a package folder against the community schemas; --write fills in hashes, --sign
                signs manifest.json with your author key (sets author.key the first time).
          duo submit               How to send a packed mod or layout to the community repository.
          duo keygen [--out FILE]  A new author key (SSH ed25519), by default in ~/.config/gamenative-duo/.
          duo stubs DIR --out SET.zip [--only A.dll,B.dll]
                Reference stubs of a game's assemblies (no code, no assets) for building mods in CI (refs.json).
          duo mcp                  MCP server (stdio) over the Duo API, for Claude Code and other agents.
          duo version              This tool's version and the Duo SDK version its templates and simulator use.
        """;

    public static async Task<int> Main(string[] args)
    {
        if (args.Length == 0 || args[0] is "-h" or "--help" or "help")
        {
            Console.WriteLine(Help);
            return 0;
        }
        try
        {
            return args[0] switch
            {
                "version" or "--version" => Print($"duo {Version} (Duo SDK {SdkVersion()})"),
                "sim" => await Sim.Run(new Args(args.Skip(1))),
                _ => await Commands.Run(args[0], new Args(args.Skip(1))),
            };
        }
        catch (DuoException ex)
        {
            Console.Error.WriteLine("duo: " + ex.Message);
            return 2;
        }
    }

    /// <summary>
    /// The version of the SDK this build carries (what `duo new` copies into a mod and what the simulator speaks): read
    /// from the embedded SDK source, so it cannot drift from it.
    /// </summary>
    public static string SdkVersion()
    {
        using var s = System.Reflection.Assembly.GetExecutingAssembly().GetManifestResourceStream("sdk/cs/Duo.cs");
        if (s == null)
            return "unknown";
        using var r = new StreamReader(s);
        var m = System.Text.RegularExpressions.Regex.Match(r.ReadToEnd(), "SdkVersion\\s*=\\s*\"([^\"]+)\"");
        return m.Success ? m.Groups[1].Value : "unknown";
    }

    private static int Print(string s)
    {
        Console.WriteLine(s);
        return 0;
    }
}

public sealed class DuoException(string message) : Exception(message);

/// <summary>--name value options and positional arguments.</summary>
public sealed class Args
{
    public readonly List<string> Positional = new();
    private readonly Dictionary<string, string> _options = new();
    private readonly HashSet<string> _flags = new();

    public Args(IEnumerable<string> args)
    {
        var list = args.ToList();
        for (int i = 0; i < list.Count; i++)
        {
            string a = list[i];
            if (a.StartsWith("--", StringComparison.Ordinal))
            {
                if (i + 1 < list.Count && !list[i + 1].StartsWith("--", StringComparison.Ordinal))
                    _options[a[2..]] = list[++i];
                else
                    _flags.Add(a[2..]);
            }
            else if (a.StartsWith('-') && a.Length == 2)
                _flags.Add(a[1..]);
            else
                Positional.Add(a);
        }
    }

    public string? this[string name] => _options.TryGetValue(name, out var v) ? v : null;

    public bool Flag(string name) => _flags.Contains(name);
}
