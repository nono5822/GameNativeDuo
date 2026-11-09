using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>
/// `duo mcp`: an MCP server (stdio, newline-delimited JSON-RPC 2.0) over the Duo API, so an agent runs the loop a
/// developer runs: build, install, tap, screenshot, read elements and logs, set rules, send commands, check. It talks
/// to the last `duo sim` or to a device (DUO_URL / DUO_CODE), found again on every call so the sim may start later.
/// </summary>
public static class Mcp
{
    private const string Protocol = "2025-06-18";

    private static readonly (string name, string description, JsonObject schema)[] Tools =
    {
        ("info", "Which server (sim or device), the game, and the connected mod (engine, SDK version, capabilities).", Schema()),
        ("elements", "The mod's UI elements as it reports them: id, name, screen, rectangle in window pixels, features (screens/move/scale), present/when.", Schema()),
        ("set_rule", "Set the user's rule for one element (screen top|bottom|hidden, scale, x/y shift as fractions of the screen); rule null clears it.",
            Schema(("id", "string", "element id"), ("rule", "object", "{\"screen\":\"top\",\"scale\":1.2,\"x\":0.05,\"y\":0} or null"))),
        ("tap", "Tap the bottom screen at (x, y) in bottom-screen pixels (Thor: 1240x1080), delivered like a finger.",
            Schema(("x", "number", ""), ("y", "number", ""))),
        ("swipe", "Drag on the bottom screen from (x1,y1) to (x2,y2) over ms milliseconds.",
            Schema(("x1", "number", ""), ("y1", "number", ""), ("x2", "number", ""), ("y2", "number", ""), ("ms", "number", "default 300"))),
        ("screenshot", "The top or bottom screen as an image.", Schema(("screen", "string", "top or bottom"))),
        ("logs", "Mod log lines (LOG packets; device: also GNDuo logcat) after sequence number since (0 = all kept). Returns next.",
            Schema(("since", "number", "sequence number from the last call"))),
        ("command", "Send a command to the mod (what a companion button or hotkey does). Answers ok, refused, unknown, failed or timeout.",
            Schema(("cmd", "string", "e.g. hk.map.zoom"), ("args", "object", "arguments object"))),
        ("state", "Companion state the mod published, optionally subscribing to keys first (the mod sends only subscribed keys).",
            Schema(("prefix", "string", "key prefix filter"), ("subscribe", "array", "keys to subscribe to"))),
        ("check", "Run the conformance check: every declared feature of every element on screen is tried and judged.", Schema()),
        ("test", "Run a duo test script file (tap, swipe, command, rule, expect element/state/command, screenshot).",
            Schema(("path", "string", "script file"))),
        ("build", "Build the mod in a folder (dotnet for .csproj, make for native, Lua syntax check).", Schema(("dir", "string", "folder"), ("game", "string", "game references folder (optional)"))),
        ("install_device", "Push a mod folder to the device's dev folder (developer mode loads it at the next session start).",
            Schema(("id", "string", "mod id"), ("dir", "string", "folder"))),
    };

    public static async Task<int> Run(Args a)
    {
        var stdin = Console.OpenStandardInput();
        using var reader = new StreamReader(stdin);
        var stdout = Console.OpenStandardOutput();
        while (await reader.ReadLineAsync() is { } line)
        {
            if (line.Trim().Length == 0)
                continue;
            JsonObject? msg;
            try
            {
                msg = JsonNode.Parse(line) as JsonObject;
            }
            catch (System.Text.Json.JsonException)
            {
                await Write(stdout, Error(null, -32700, "parse error"));
                continue;
            }
            if (msg == null)
                continue;
            var reply = await Handle(msg, a);
            if (reply != null)
                await Write(stdout, reply);
        }
        return 0;
    }

    public static async Task<JsonObject?> Handle(JsonObject msg, Args a)
    {
        var id = msg["id"]?.DeepClone();
        string method = (string?)msg["method"] ?? "";
        if (id == null)
            return null; // a notification (initialized, cancelled): nothing to answer
        switch (method)
        {
            case "initialize":
                return Result(id, new JsonObject
                {
                    ["protocolVersion"] = (string?)msg["params"]?["protocolVersion"] ?? Protocol,
                    ["capabilities"] = new JsonObject { ["tools"] = new JsonObject() },
                    ["serverInfo"] = new JsonObject { ["name"] = "gamenative-duo", ["version"] = Program.Version },
                    ["instructions"] = "Develop and test GameNative Duo dual-screen mods. Start `duo sim` (or set DUO_URL/DUO_CODE for a device), " +
                        "run the game windowed at layout.json's size, then use elements/tap/screenshot/check. Coordinates for tap/swipe are bottom-screen pixels.",
                });
            case "ping":
                return Result(id, new JsonObject());
            case "tools/list":
                return Result(id, new JsonObject
                {
                    ["tools"] = new JsonArray(Tools.Select(t => (JsonNode)new JsonObject
                    {
                        ["name"] = t.name, ["description"] = t.description, ["inputSchema"] = t.schema.DeepClone(),
                    }).ToArray()),
                });
            case "tools/call":
            {
                string name = (string?)msg["params"]?["name"] ?? "";
                var args = msg["params"]?["arguments"] as JsonObject ?? new JsonObject();
                try
                {
                    return Result(id, await Call(name, args, a));
                }
                catch (Exception ex) when (ex is DuoException or HttpRequestException or TaskCanceledException or IOException or FormatException)
                {
                    return Result(id, new JsonObject { ["content"] = Text("Error: " + ex.Message), ["isError"] = true });
                }
            }
            default:
                return Error(id, -32601, "unknown method " + method);
        }
    }

    private static async Task<JsonObject> Call(string name, JsonObject args, Args a)
    {
        switch (name)
        {
            case "build":
                return Captured(() => Commands.Run("build", new Args(new[] { (string?)args["dir"] ?? "." }.Concat(args["game"] is { } g ? new[] { "--game", (string)g! } : Array.Empty<string>()))).GetAwaiter().GetResult());
            case "install_device":
                return Captured(() => Commands.Run("dev", new Args(new[] { "--device", (string?)args["id"] ?? throw new DuoException("id"), (string?)args["dir"] ?? "." })).GetAwaiter().GetResult());
        }
        var c = DuoClient.From(a);
        switch (name)
        {
            case "info":
                return Json(await c.Get("/api/info"));
            case "elements":
                return Json(await c.Get("/api/elements"));
            case "set_rule":
                return Json(await c.Post("/api/rule", new JsonObject { ["id"] = (string?)args["id"], ["rule"] = args["rule"]?.DeepClone() }));
            case "tap":
                return Json(await c.Post("/api/touch", new JsonObject { ["x"] = Num(args["x"]) ?? 0, ["y"] = Num(args["y"]) ?? 0, ["action"] = "tap" }));
            case "swipe":
            {
                string script = Path.GetTempFileName();
                File.WriteAllText(script, $"swipe {args["x1"]} {args["y1"]} {args["x2"]} {args["y2"]} {Num(args["ms"]) ?? 300}");
                await TestScript.Run(c, script);
                File.Delete(script);
                return Json(new JsonObject { ["ok"] = true });
            }
            case "screenshot":
            {
                string which = (string?)args["screen"] == "top" ? "top" : "bottom";
                var bytes = await c.Bytes($"/api/snapshot/{which}.jpg");
                bool png = bytes.Length > 4 && bytes[0] == 137 && bytes[1] == 80;
                return new JsonObject
                {
                    ["content"] = new JsonArray(new JsonObject { ["type"] = "image", ["data"] = Convert.ToBase64String(bytes), ["mimeType"] = png ? "image/png" : "image/jpeg" }),
                };
            }
            case "logs":
                return Json(await c.Get($"/api/logs?since={(long)(Num(args["since"]) ?? 0)}"));
            case "command":
                return Json(await c.Post("/api/command", new JsonObject { ["cmd"] = (string?)args["cmd"], ["args"] = args["args"]?.DeepClone() ?? new JsonObject() }));
            case "state":
                if (args["subscribe"] is JsonArray keys)
                {
                    await c.Post("/api/companion/subscribe", new JsonObject { ["keys"] = keys.DeepClone() });
                    await Task.Delay(300);
                }
                return Json(await c.Get("/api/companion/state?prefix=" + Uri.EscapeDataString((string?)args["prefix"] ?? "")));
            case "check":
                return Captured(() => Commands.Check(c, json: true).GetAwaiter().GetResult());
            case "test":
                return Captured(() => TestScript.Run(c, (string?)args["path"] ?? throw new DuoException("path")).GetAwaiter().GetResult());
            default:
                throw new DuoException("unknown tool " + name);
        }
    }

    /// <summary>Runs a CLI command with its console output captured as the tool result (stdout is the MCP channel).</summary>
    private static JsonObject Captured(Func<int> run)
    {
        var old = Console.Out;
        var sw = new StringWriter();
        Console.SetOut(sw);
        int rc;
        try
        {
            rc = run();
        }
        finally
        {
            Console.SetOut(old);
        }
        return new JsonObject { ["content"] = Text(sw.ToString().TrimEnd() + $"\n(exit code {rc})"), ["isError"] = rc != 0 };
    }

    /// <summary>A number however the node was made (parsed JSON or built in code).</summary>
    private static double? Num(JsonNode? n) =>
        n == null ? null : double.TryParse(n.ToJsonString().Trim('"'), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var d) ? d : null;

    private static JsonObject Json(JsonObject o) => new() { ["content"] = Text(o.ToJsonString(new() { WriteIndented = true })) };

    private static JsonArray Text(string s) => new(new JsonObject { ["type"] = "text", ["text"] = s });

    private static JsonObject Schema(params (string name, string type, string description)[] props)
    {
        var p = new JsonObject();
        foreach (var (n, t, d) in props)
        {
            var o = new JsonObject { ["type"] = t == "object" ? new JsonArray("object", "null") : (JsonNode)t };
            if (d.Length > 0)
                o["description"] = d;
            p[n] = o;
        }
        return new JsonObject { ["type"] = "object", ["properties"] = p };
    }

    private static JsonObject Result(JsonNode id, JsonObject result) => new() { ["jsonrpc"] = "2.0", ["id"] = id, ["result"] = result };

    private static JsonObject Error(JsonNode? id, int code, string message) =>
        new() { ["jsonrpc"] = "2.0", ["id"] = id, ["error"] = new JsonObject { ["code"] = code, ["message"] = message } };

    private static async Task Write(Stream s, JsonObject o)
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(o.ToJsonString() + "\n");
        await s.WriteAsync(bytes);
        await s.FlushAsync();
    }
}
