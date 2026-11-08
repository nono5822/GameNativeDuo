using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>
/// The Duo API (duo/docs/duo-api.md) for the simulator, on 127.0.0.1 only. Every /api/ request needs the token
/// (X-Duo-Code header or ?k=); the page itself is public and asks for it, like on the device.
/// </summary>
public sealed class ApiServer : IDisposable
{
    public const int Api = 1;
    private const string Alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private readonly SimHost _sim;
    private readonly HttpListener _listener = new();
    private readonly CancellationTokenSource _stop = new();
    public readonly string Token;
    public readonly int Port;
    public Func<string, byte[]?>? Snapshot; // "top"/"bottom" -> PNG (window capture), set by the sim
    public ConformanceRunner? Check;

    public ApiServer(SimHost sim, int port, string? token = null)
    {
        _sim = sim;
        Port = port;
        Token = token ?? new string(Enumerable.Range(0, 6).Select(_ => Alphabet[RandomNumberGenerator.GetInt32(Alphabet.Length)]).ToArray());
        _listener.Prefixes.Add($"http://127.0.0.1:{port}/");
    }

    public string Url => $"http://127.0.0.1:{Port}/?k={Token}";

    public void Start()
    {
        _listener.Start();
        _ = Task.Run(Loop);
    }

    private async Task Loop()
    {
        while (!_stop.IsCancellationRequested)
        {
            HttpListenerContext ctx;
            try
            {
                ctx = await _listener.GetContextAsync();
            }
            catch (Exception)
            {
                return;
            }
            _ = Task.Run(() => Handle(ctx));
        }
    }

    private async Task Handle(HttpListenerContext ctx)
    {
        var req = ctx.Request;
        var res = ctx.Response;
        try
        {
            string path = req.Url!.AbsolutePath;
            if (req.HttpMethod == "GET" && (path == "/" || path == "/index.html"))
            {
                using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("index.html")!;
                using var ms = new MemoryStream();
                s.CopyTo(ms);
                await Send(res, 200, "text/html; charset=utf-8", ms.ToArray());
                return;
            }
            string? given = req.Headers["X-Duo-Code"] ?? req.QueryString["k"];
            if (given == null || !CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(given.ToUpperInvariant()), Encoding.ASCII.GetBytes(Token)))
            {
                await Json(res, new JsonObject { ["error"] = "wrong code" }, 403);
                return;
            }
            string body = "";
            if (req.HasEntityBody)
            {
                using var r = new StreamReader(req.InputStream, Encoding.UTF8);
                body = await r.ReadToEndAsync();
            }
            await Route(req.HttpMethod + " " + path, req, res, body);
        }
        catch (Exception ex)
        {
            try
            {
                await Json(res, new JsonObject { ["error"] = ex.Message }, 500);
            }
            catch (Exception)
            {
            }
        }
    }

    private async Task Route(string route, HttpListenerRequest req, HttpListenerResponse res, string body)
    {
        JsonObject In() => JsonNode.Parse(body.Length == 0 ? "{}" : body) as JsonObject ?? new JsonObject();
        var b = _sim.Bridge;
        switch (route)
        {
            case "GET /api/info":
                await Json(res, new JsonObject
                {
                    ["api"] = Api, ["server"] = "sim", ["app"] = "duo " + Program.Version, ["sdk"] = "1.0", ["dev"] = true,
                    ["game"] = _sim.Game, ["title"] = _sim.Game, ["profile"] = _sim.Profile.Id, ["scale"] = _sim.Scale,
                    ["mod"] = new JsonObject { ["engine"] = b.Engine, ["sdk"] = b.Sdk, ["caps"] = b.Capabilities, ["connected"] = b.Connected, ["port"] = b.ModPort },
                });
                break;
            case "GET /api/state":
                await Json(res, await State());
                break;
            case "GET /api/snapshot/top.jpg":
            case "GET /api/snapshot/bottom.jpg":
            case "GET /api/snapshot/top.png":
            case "GET /api/snapshot/bottom.png":
            {
                var png = Snapshot?.Invoke(route.Contains("top") ? "top" : "bottom");
                if (png == null)
                    await Send(res, 503, "text/plain", Encoding.UTF8.GetBytes("no picture: no game window captured"));
                else
                    await Send(res, 200, "image/png", png);
                break;
            }
            case "POST /api/rule":
            {
                var o = In();
                _sim.SetRule((string)o["id"]!, o["rule"] as JsonObject);
                await Ok(res);
                break;
            }
            case "POST /api/background":
                _sim.SetUiKey("background", In());
                await Ok(res);
                break;
            case "POST /api/widgets":
                _sim.SetUiKey("widgets", JsonNode.Parse(body));
                await Ok(res);
                break;
            case "POST /api/reset":
                _sim.ResetUi();
                await Ok(res);
                break;
            case "GET /api/elements":
            case "POST /api/elements/refresh":
            {
                var dump = await _sim.RequestElements(TimeSpan.FromSeconds(3));
                var o = dump?.DeepClone() as JsonObject ?? new JsonObject { ["error"] = "the mod did not answer" };
                o["features"] = _sim.ReadJson("features.json");
                await Json(res, o, dump == null ? 504 : 200);
                break;
            }
            case "GET /api/bridge":
                await Json(res, b.Snapshot());
                break;
            case "POST /api/bridge/send":
            {
                string type = (string?)In()["type"] ?? "";
                if (type == "RULES_CHANGED")
                    b.SendRulesChanged();
                else if (type == "DUMP_ELEMENTS")
                    _ = _sim.RequestElements(TimeSpan.FromSeconds(3));
                else
                {
                    await Json(res, new JsonObject { ["error"] = "send RULES_CHANGED or DUMP_ELEMENTS" }, 400);
                    break;
                }
                await Ok(res);
                break;
            }
            case "POST /api/command":
            {
                var o = In();
                var (status, message) = await _sim.RunCommand(new JsonObject { ["cmd"] = (string?)o["cmd"], ["args"] = o["args"]?.DeepClone() ?? new JsonObject() }.ToJsonString());
                await Json(res, new JsonObject { ["status"] = status, ["message"] = message });
                break;
            }
            case "GET /api/companion/state":
            {
                string prefix = req.QueryString["prefix"] ?? "";
                var values = new JsonObject();
                lock (b.StateValues)
                {
                    foreach (var pair in b.StateValues.Where(p => p.Key.StartsWith(prefix, StringComparison.Ordinal)).OrderBy(p => p.Key, StringComparer.Ordinal))
                        values[pair.Key] = pair.Value?.DeepClone();
                }
                await Json(res, new JsonObject { ["values"] = values });
                break;
            }
            case "POST /api/companion/subscribe":
            {
                var keys = (In()["keys"] as JsonArray)?.Select(k => (string?)k).Where(k => !string.IsNullOrEmpty(k)).Cast<string>() ?? Enumerable.Empty<string>();
                b.SendSubscribe(keys);
                await Ok(res);
                break;
            }
            case "POST /api/touch":
            {
                var o = In();
                _sim.Touch((string?)o["action"] ?? "tap", (int?)o["pointer"] ?? 0, (double?)o["x"] ?? 0, (double?)o["y"] ?? 0);
                await Ok(res);
                break;
            }
            case "GET /api/events":
            case "GET /api/logs":
            {
                long since = long.TryParse(req.QueryString["since"], out var s) ? s : 0;
                int wait = int.TryParse(req.QueryString["wait"], out var w) ? Math.Clamp(w, 0, 25) : 0;
                var (next, events) = await Task.Run(() => _sim.Events.Since(since, TimeSpan.FromSeconds(wait), route.EndsWith("logs") ? "log" : null));
                await Json(res, new JsonObject { ["next"] = next, ["events"] = events });
                break;
            }
            case "POST /api/check":
                if (Check == null || !Check.Start())
                    await Json(res, new JsonObject { ["error"] = "a check is running" }, 409);
                else
                    await Json(res, new JsonObject { ["started"] = true });
                break;
            case "GET /api/check":
                await Json(res, Check?.Status() ?? new JsonObject { ["running"] = false });
                break;
            case "POST /api/sim/profile":
            {
                var o = In();
                var p = DeviceProfile.Find((string?)o["profile"] ?? "") ?? _sim.Profile;
                _sim.SetProfile(p, (double?)o["scale"] ?? _sim.Scale);
                await Ok(res);
                break;
            }
            default:
                await Json(res, new JsonObject { ["error"] = "not found" }, 404);
                break;
        }
    }

    /// <summary>The Layout tab's state, in the device's shape (PcEditorServer.state).</summary>
    private async Task<JsonObject> State()
    {
        var dump = await _sim.RequestElements(TimeSpan.FromSeconds(3));
        var b = _sim.Bridge;
        string owner = (b.Engine.Length > 0 ? b.Engine : "The game") + "'s mod";
        uint caps = b.Capabilities;
        bool editable = b.ModPort != 0 && (caps & 4) != 0;
        return new JsonObject
        {
            ["game"] = _sim.Game, ["title"] = _sim.Game, ["engine"] = b.Engine,
            ["window"] = new JsonObject { ["w"] = _sim.ScreenW, ["h"] = _sim.ScreenH },
            ["top"] = new JsonArray(_sim.Rect("top").Select(v => (JsonNode)v).ToArray()),
            ["bottom"] = new JsonArray(_sim.Rect("bottom").Select(v => (JsonNode)v).ToArray()),
            ["caps"] = new JsonObject
            {
                ["elements"] = editable, ["owner"] = owner, ["source"] = editable ? "mod" : "none",
                ["reasonOff"] = editable ? null : "No mod has connected to the simulator yet.",
                ["game"] = editable, ["blur"] = (caps & 16) != 0, ["color"] = (caps & 8) != 0, ["image"] = (caps & 8) != 0,
            },
            ["layout"] = _sim.Ui.DeepClone(),
            ["hasImage"] = false,
            ["widgetTypes"] = new JsonArray(),
            ["elements"] = Elements.ForEditor(dump, owner),
        };
    }

    private static Task Ok(HttpListenerResponse res) => Json(res, new JsonObject { ["ok"] = true });

    private static Task Json(HttpListenerResponse res, JsonNode o, int status = 200) =>
        Send(res, status, "application/json", Encoding.UTF8.GetBytes(o.ToJsonString()));

    private static async Task Send(HttpListenerResponse res, int status, string type, byte[] body)
    {
        res.StatusCode = status;
        res.ContentType = type;
        res.Headers["Cache-Control"] = "no-store";
        res.ContentLength64 = body.Length;
        await res.OutputStream.WriteAsync(body);
        res.Close();
    }

    public void Dispose()
    {
        _stop.Cancel();
        try
        {
            _listener.Stop();
        }
        catch (ObjectDisposedException)
        {
        }
    }
}
