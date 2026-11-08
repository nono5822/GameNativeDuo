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
                Talk to a running sim or device (--url http://HOST:8642 --code CODE; defaults: the last sim).
          duo test SCRIPT          Run a test script against the sim or device.
          duo new --engine love|unity|smapi|native ID    Start a mod from a template.
          duo pack DIR             Check a package folder against the community schemas.
          duo mcp                  MCP server (stdio) over the Duo API, for Claude Code and other agents.
          duo version
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
                "version" or "--version" => Print("duo " + Version),
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
