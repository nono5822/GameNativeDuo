using System.Net;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;
using GameNativeDuo.Sdk;
using GameNativeDuo.Tool;
using Xunit;

/// <summary>`duo sim` and the C# SDK against each other, through the Duo API: what an agent or the page does.</summary>
public class SimTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "duo-sim-test-" + Guid.NewGuid().ToString("N"));
    private readonly SimHost _sim;
    private readonly ApiServer _server;
    private readonly HttpClient _http = new();
    private readonly CancellationTokenSource _stop = new();
    private readonly Thread _game;
    private float _shiftX;
    private int _touches;

    public SimTests()
    {
        _sim = new SimHost(_dir, DeviceProfile.Thor, 1, "TEST_1");
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        int port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        _server = new ApiServer(_sim, port, "TOKEN1") { };
        _server.Check = new ConformanceRunner(_sim);
        _server.Start();
        _http.BaseAddress = new Uri($"http://127.0.0.1:{port}");
        _http.DefaultRequestHeaders.Add("X-Duo-Code", "TOKEN1");

        // The "game": the SDK facade ticking on its own thread, with one element that honours the user's rule.
        DuoPaths.Dir = _dir;
        Duo.Start("test-game");
        Duo.OnTouch(t => { if (t.Action == Bridge.TouchDown) Interlocked.Increment(ref _touches); });
        Duo.OnCommand("test.ping", args => CommandResult.Ok("pong"));
        Duo.Elements.Register("Score", "Score", () =>
        {
            var b = Duo.Layout!.Bottom;
            var rule = Duo.Rule("Score");
            int x = b.X + 100 + (int)((rule?.X ?? 0) * b.W);
            if (rule?.Screen == DuoScreen.Hidden)
                return new DuoRect(x, b.Y + 100, 0, 0);
            return new DuoRect(x, b.Y + 100, 200, 50);
        }, new ElementFeatures { Scale = false, Screens = new[] { DuoScreen.Bottom, DuoScreen.Hidden } });
        Duo.Publish("test.hp", 5);
        _game = new Thread(() =>
        {
            while (!_stop.IsCancellationRequested)
            {
                Duo.Tick(_sim.ScreenW, _sim.ScreenH);
                Thread.Sleep(10);
            }
        }) { IsBackground = true };
        _game.Start();
    }

    public void Dispose()
    {
        _stop.Cancel();
        _game.Join();
        Duo.Stop();
        _server.Dispose();
        _sim.Dispose();
        Directory.Delete(_dir, true);
    }

    private async Task<JsonObject> Get(string path) => (JsonObject)JsonNode.Parse(await _http.GetStringAsync(path))!;

    private async Task<JsonObject> Post(string path, string json)
    {
        var r = await _http.PostAsync(path, new StringContent(json, Encoding.UTF8, "application/json"));
        return (JsonObject)JsonNode.Parse(await r.Content.ReadAsStringAsync())!;
    }

    private async Task WaitConnected()
    {
        for (int i = 0; i < 300 && !_sim.Bridge.Connected; i++)
            await Task.Delay(10);
        Assert.True(_sim.Bridge.Connected, "the SDK said hello to the sim");
        Assert.True(_sim.Bridge.TokenSeen, "with the session token from layout.json");
    }

    [Fact]
    public void HelloRules()
    {
        const string t = "00112233445566778899aabbccddeeff";
        // The token always wins, also from a new port (a restarted game).
        Assert.True(AppBridge.AcceptsHello(t, t, samePort: false, modAlive: true, tokenSeen: true));
        Assert.False(AppBridge.AcceptsHello(t, "ffeeddccbbaa99887766554433221100", samePort: true, modAlive: true, tokenSeen: false));
        // No token (a mod on SDK 1.0): the first mod and its repeats, never over a live mod, never after a token.
        Assert.True(AppBridge.AcceptsHello(t, null, samePort: false, modAlive: false, tokenSeen: false));
        Assert.True(AppBridge.AcceptsHello(t, null, samePort: true, modAlive: true, tokenSeen: false));
        Assert.False(AppBridge.AcceptsHello(t, null, samePort: false, modAlive: true, tokenSeen: false));
        Assert.False(AppBridge.AcceptsHello(t, null, samePort: true, modAlive: true, tokenSeen: true));
    }

    [Fact]
    public async Task EverythingAnAgentNeeds()
    {
        // Wrong code: refused; the page itself: public.
        var anon = new HttpClient { BaseAddress = _http.BaseAddress };
        Assert.Equal(HttpStatusCode.Forbidden, (await anon.GetAsync("/api/info")).StatusCode);
        Assert.Contains("<html", await anon.GetStringAsync("/"), StringComparison.OrdinalIgnoreCase);

        await WaitConnected();
        var info = await Get("/api/info");
        Assert.Equal("sim", (string?)info["server"]);
        Assert.Equal("test-game", (string?)info["mod"]!["engine"]);
        Assert.Equal(Duo.SdkVersion, (string?)info["mod"]!["sdk"]);

        var elements = await Get("/api/elements");
        Assert.Equal("Score", (string?)elements["elements"]![0]!["id"]);
        Assert.NotNull(elements["features"]);

        var state = await Get("/api/state");
        var score = state["elements"]![0]!;
        Assert.Equal(false, (bool?)score["scale"]);
        Assert.Equal("test-game's mod cannot resize Score or put it on the top screen.", (string?)score["limits"]);

        var cmd = await Post("/api/command", "{\"cmd\":\"test.ping\"}");
        Assert.Equal("ok", (string?)cmd["status"]);
        Assert.Equal("pong", (string?)cmd["message"]);
        Assert.Equal("unknown", (string?)(await Post("/api/command", "{\"cmd\":\"nope\"}"))["status"]);

        await Post("/api/companion/subscribe", "{\"keys\":[\"test.hp\"]}");
        JsonNode? hp = null;
        for (int i = 0; i < 100 && hp == null; i++)
        {
            hp = (await Get("/api/companion/state?prefix=test."))["values"]!["test.hp"];
            await Task.Delay(20);
        }
        Assert.Equal(5, (int?)hp);

        await Post("/api/touch", "{\"x\":620,\"y\":540,\"action\":\"tap\"}");
        for (int i = 0; i < 100 && _touches == 0; i++)
            await Task.Delay(10);
        Assert.Equal(1, _touches);

        await Post("/api/rule", "{\"id\":\"Score\",\"rule\":{\"x\":0.05}}");
        Assert.Contains("\"x\": 0.05", File.ReadAllText(Path.Combine(_dir, "ui.json")));

        var events = await Get("/api/events?since=0");
        Assert.Contains(events["events"]!.AsArray(), e => (string?)e!["kind"] == "bridge");
    }

    [Fact]
    public async Task TestScriptsAndTheCheckCommand()
    {
        await WaitConnected();
        var client = new DuoClient(_http.BaseAddress!.ToString(), "TOKEN1");
        string script = Path.Combine(_dir, "t.duo");
        File.WriteAllText(script, string.Join("\n",
            "# a script like hk-test.txt",
            "tap 620 540",
            "subscribe test.hp",
            "wait 0.3",
            "expect state test.hp = 5",
            "expect command test.ping ok",
            "expect element Score on bottom",
            "rule Score {\"screen\":\"hidden\"}",
            "wait 0.3",
            "expect element Score on hidden"));
        Assert.Equal(0, await TestScript.Run(client, script));
        File.WriteAllText(script, "expect element Score on top");
        Assert.Equal(1, await TestScript.Run(client, script));
        Assert.Equal(1, _touches);
        await client.Post("/api/rule", new JsonObject { ["id"] = "Score", ["rule"] = null });
        Assert.Equal(0, await Commands.Check(client, json: false));
    }

    [Fact]
    public async Task McpServerDrivesTheSim()
    {
        await WaitConnected();
        var args = new Args(new[] { "--url", _http.BaseAddress!.ToString(), "--code", "TOKEN1" });
        async Task<JsonObject> Rpc(string method, JsonObject? p = null) =>
            (await Mcp.Handle(new JsonObject { ["jsonrpc"] = "2.0", ["id"] = 1, ["method"] = method, ["params"] = p ?? new JsonObject() }, args))!;
        var init = await Rpc("initialize", new JsonObject { ["protocolVersion"] = "2025-06-18" });
        Assert.Equal("gamenative-duo", (string?)init["result"]!["serverInfo"]!["name"]);
        var tools = init = await Rpc("tools/list");
        Assert.Contains(tools["result"]!["tools"]!.AsArray(), t => (string?)t!["name"] == "screenshot");
        Assert.Null(await Mcp.Handle(new JsonObject { ["jsonrpc"] = "2.0", ["method"] = "notifications/initialized" }, args));

        var call = await Rpc("tools/call", new JsonObject { ["name"] = "elements", ["arguments"] = new JsonObject() });
        Assert.Contains("\"Score\"", (string?)call["result"]!["content"]![0]!["text"]);
        call = await Rpc("tools/call", new JsonObject { ["name"] = "tap", ["arguments"] = new JsonObject { ["x"] = 10, ["y"] = 10 } });
        Assert.Null(call["result"]!["isError"]);
        call = await Rpc("tools/call", new JsonObject { ["name"] = "command", ["arguments"] = new JsonObject { ["cmd"] = "test.ping" } });
        Assert.Contains("pong", (string?)call["result"]!["content"]![0]!["text"]);
        call = await Rpc("tools/call", new JsonObject { ["name"] = "screenshot", ["arguments"] = new JsonObject { ["screen"] = "top" } });
        Assert.Equal(true, (bool?)call["result"]!["isError"]); // no game window to capture in a test: a clear error, not a crash
        call = await Rpc("tools/call", new JsonObject { ["name"] = "check", ["arguments"] = new JsonObject() });
        Assert.Contains("\"passed\": true", (string?)call["result"]!["content"]![0]!["text"]);
    }

    [Fact]
    public async Task ConformanceCheckPassesForAnHonestMod()
    {
        await WaitConnected();
        Assert.True((bool?)(await Post("/api/check", "{}"))["started"]);
        JsonObject status;
        do
        {
            await Task.Delay(100);
            status = await Get("/api/check");
        } while ((bool)status["running"]!);
        var report = status["report"]!;
        Assert.True((bool)report["passed"]!, report.ToJsonString());
        Assert.Equal(new[] { "hide", "move" }, report["results"]!.AsArray().Select(r => (string)r!["feature"]!).ToArray());
    }
}

public class CaptureTests
{
    [Fact]
    public void PngIsWellFormed()
    {
        var bgra = new byte[4 * 4 * 4];
        for (int i = 0; i < bgra.Length; i += 4)
            bgra[i + 2] = 255; // red
        var png = Capture.Png(bgra, 4, 1, 1, 2, 2);
        Assert.Equal(new byte[] { 137, 80, 78, 71 }, png[..4]);
        Assert.Equal(0xAE426082u, (uint)(png[^4] << 24 | png[^3] << 16 | png[^2] << 8 | png[^1])); // IEND's CRC
        Assert.Equal(0xCBF43926u, Capture.Crc32(System.Text.Encoding.ASCII.GetBytes("123456789")));
    }
}
