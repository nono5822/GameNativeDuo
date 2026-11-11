using System.Text;
using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>
/// The simulator: plays the app's part for a game running on this PC. Writes layout.json, bridge.json and ui.json into
/// the game's %AppData%\GameNativeDuo\ (<see cref="Dir"/>), runs the bridge, keeps the user's UI layout, answers the
/// Duo API (duo/docs/duo-api.md) through <see cref="ApiServer"/>.
/// </summary>
public sealed class SimHost : IDisposable
{
    public readonly string Dir;
    public readonly EventLog Events = new();
    public readonly AppBridge Bridge;
    public DeviceProfile Profile { get; private set; }
    public double Scale { get; private set; }
    public string Game { get; set; }
    public JsonObject Layout { get; private set; } = new();

    /// <summary>The user's UI layout (ui.json's format): rules per element, background, widgets.</summary>
    public JsonObject Ui { get; private set; }

    private uint _nextRequest = 1;
    private readonly Dictionary<uint, TaskCompletionSource<bool>> _dumps = new();
    private readonly Dictionary<uint, TaskCompletionSource<(byte, string)>> _commands = new();
    private readonly object _lock = new();

    public SimHost(string dir, DeviceProfile profile, double scale = 1, string game = "")
    {
        Dir = dir;
        Directory.CreateDirectory(dir);
        Profile = profile;
        Scale = scale;
        Game = game;
        Bridge = new AppBridge(Events);
        Bridge.ElementsWrittenReceived += r => Complete(_dumps, r, true);
        Bridge.CommandResultReceived += (r, s, m) => Complete(_commands, r, (s, m));
        Ui = LoadUi();
        WriteLayout();
        WriteFile("bridge.json", new JsonObject { ["port"] = Bridge.Port, ["token"] = Bridge.Token }.ToJsonString());
        WriteUi(false);
        Events.Log("duo", "I", $"sim: {profile.Name} at {scale * 100:0}%, files in {dir}, bridge on 127.0.0.1:{Bridge.Port}");
    }

    public int ScreenW => (int)Layout["screen"]!["w"]!;
    public int ScreenH => (int)Layout["screen"]!["h"]!;

    public int[] Rect(string which)
    {
        var r = Layout[which]!;
        return new[] { (int)r["x"]!, (int)r["y"]!, (int)r["w"]!, (int)r["h"]! };
    }

    public void SetProfile(DeviceProfile profile, double scale)
    {
        Profile = profile;
        Scale = scale;
        WriteLayout();
    }

    private void WriteLayout()
    {
        Layout = Profile.Layout(Scale, Bridge.Port);
        if (Layout["bridge"] is JsonObject bridge)
            bridge["token"] = Bridge.Token;
        WriteFile("layout.json", Layout.ToJsonString(new() { WriteIndented = true }));
    }

    // ---- UI layout ---------------------------------------------------------------------------------------------------

    private JsonObject LoadUi()
    {
        try
        {
            if (File.Exists(Path.Combine(Dir, "ui.json")) && JsonNode.Parse(File.ReadAllText(Path.Combine(Dir, "ui.json"))) is JsonObject o)
                return o;
        }
        catch (Exception ex)
        {
            Events.Log("duo", "W", "ui.json unreadable, starting empty: " + ex.Message);
        }
        return new JsonObject { ["format"] = "gamenative-duo/ui-layout", ["version"] = 1, ["game"] = Game, ["elements"] = new JsonObject() };
    }

    public JsonObject Rules
    {
        get
        {
            if (Ui["elements"] is not JsonObject e)
                Ui["elements"] = e = new JsonObject();
            return e;
        }
    }

    /// <summary>Sets one element's rule (null removes it) and tells the mod.</summary>
    public void SetRule(string id, JsonObject? rule, bool save = true)
    {
        lock (_lock)
        {
            if (rule == null || rule.Count == 0)
                Rules.Remove(id);
            else
                Rules[id] = rule.DeepClone();
        }
        WriteUi(save);
    }

    public JsonObject? Rule(string id) => Rules[id] as JsonObject;

    public void SetUiKey(string key, JsonNode? value)
    {
        lock (_lock)
            Ui[key] = value?.DeepClone();
        WriteUi(true);
    }

    public void ResetUi()
    {
        lock (_lock)
            Ui = new JsonObject { ["format"] = "gamenative-duo/ui-layout", ["version"] = 1, ["game"] = Game, ["elements"] = new JsonObject() };
        WriteUi(true);
    }

    private void WriteUi(bool notify)
    {
        string text;
        lock (_lock)
            text = Ui.ToJsonString(new() { WriteIndented = true });
        WriteFile("ui.json", text);
        if (notify)
            Bridge.SendRulesChanged();
    }

    // ---- elements ----------------------------------------------------------------------------------------------------

    /// <summary>Asks the mod for its element list and reads elements.json; null when it does not answer in time.</summary>
    public async Task<JsonObject?> RequestElements(TimeSpan timeout)
    {
        if (Bridge.ModPort == 0)
            return ReadJson("elements.json");
        uint request;
        var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (_dumps)
        {
            request = _nextRequest++;
            _dumps[request] = tcs;
        }
        Bridge.SendDump(request);
        var done = await Task.WhenAny(tcs.Task, Task.Delay(timeout));
        lock (_dumps)
            _dumps.Remove(request);
        if (done != tcs.Task)
            return null;
        // Mods built on SDK 1.0 delete the file before moving the new one in: a read in between finds nothing.
        for (int attempt = 0; ; attempt++)
        {
            var json = ReadJson("elements.json");
            if (json != null || attempt == 3)
                return json;
            await Task.Delay(25);
        }
    }

    /// <summary>Runs a command on the mod: status name and message ("timeout" when it does not answer in 3 s).</summary>
    public async Task<(string status, string message)> RunCommand(string json)
    {
        if (Bridge.ModPort == 0)
            return ("timeout", "no mod connected");
        uint request;
        var tcs = new TaskCompletionSource<(byte, string)>(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (_commands)
        {
            request = _nextRequest++;
            _commands[request] = tcs;
        }
        Bridge.SendCommand(request, json);
        var done = await Task.WhenAny(tcs.Task, Task.Delay(3000));
        lock (_commands)
            _commands.Remove(request);
        if (done != tcs.Task)
            return ("timeout", "the mod did not answer");
        var (s, m) = tcs.Task.Result;
        return (s switch { 0 => "ok", 1 => "refused", 2 => "unknown", _ => "failed" }, m);
    }

    /// <summary>A bottom-screen finger in bottom-screen pixels, sent as TOUCH in window pixels.</summary>
    public void Touch(string action, int pointer, double bx, double by)
    {
        var b = Rect("bottom");
        // The bottom part of the window is the bottom display's pixels times the split scale.
        float x = (float)(b[0] + bx * b[2] / Profile.BottomW), y = (float)(b[1] + by * b[3] / Profile.BottomH);
        switch (action)
        {
            case "tap":
                Bridge.SendTouch(0, (byte)pointer, x, y);
                Bridge.SendTouch(2, (byte)pointer, x, y);
                break;
            case "down": Bridge.SendTouch(0, (byte)pointer, x, y); break;
            case "move": Bridge.SendTouch(1, (byte)pointer, x, y); break;
            default: Bridge.SendTouch(2, (byte)pointer, x, y); break;
        }
    }

    // ---- files -------------------------------------------------------------------------------------------------------

    public JsonObject? ReadJson(string name)
    {
        try
        {
            string path = Path.Combine(Dir, name);
            return File.Exists(path) ? JsonNode.Parse(File.ReadAllText(path)) as JsonObject : null;
        }
        catch (Exception)
        {
            return null;
        }
    }

    private void WriteFile(string name, string text)
    {
        string path = Path.Combine(Dir, name), tmp = path + ".tmp";
        File.WriteAllText(tmp, text, new UTF8Encoding(false));
        File.Move(tmp, path, true);
    }

    private static void Complete<T>(Dictionary<uint, TaskCompletionSource<T>> map, uint request, T value)
    {
        TaskCompletionSource<T>? tcs;
        lock (map)
            map.TryGetValue(request, out tcs);
        tcs?.TrySetResult(value);
    }

    public void Dispose()
    {
        Bridge.Dispose();
        try
        {
            File.Delete(Path.Combine(Dir, "bridge.json"));
        }
        catch (IOException)
        {
        }
    }
}
