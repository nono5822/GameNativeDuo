using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>
/// The conformance check (uniform-editing.md), as the device runs it (`ConformanceCheck.kt`): for each element on
/// screen and each feature it declares, apply a test rule, ask for the list again, compare, put the user's rule back.
/// The judging rules are the same numbers as the app's, so a mod that passes here passes on a Thor.
/// </summary>
public sealed class ConformanceRunner
{
    public const double Shift = 0.1;
    private const int MaxTests = 40;
    private static readonly TimeSpan Settle = TimeSpan.FromMilliseconds(400);

    private readonly SimHost _sim;
    private volatile bool _running;
    private string _progress = "";
    private JsonObject? _report;

    public ConformanceRunner(SimHost sim) => _sim = sim;

    public bool Start()
    {
        if (_running)
            return false;
        _running = true;
        _ = Task.Run(async () =>
        {
            try
            {
                _report = await Run();
            }
            catch (Exception ex)
            {
                _report = new JsonObject { ["error"] = ex.Message };
            }
            finally
            {
                _running = false;
            }
            _sim.Events.Add("check", new JsonObject { ["event"] = "done", ["passed"] = (bool?)_report?["passed"] ?? false });
        });
        return true;
    }

    public JsonObject Status() => new() { ["running"] = _running, ["progress"] = _progress, ["report"] = _report?.DeepClone() };

    public async Task<JsonObject> Run()
    {
        var dump = await _sim.RequestElements(TimeSpan.FromSeconds(3)) ?? throw new InvalidOperationException("the mod did not answer");
        var list = Elements.ForEditor(dump, "mod");
        var tests = new List<(JsonObject e, string feature, double factor)>();
        var skipped = new JsonArray();
        foreach (var n in list)
        {
            var e = (JsonObject)n!;
            if (!(bool)e["graphic"]! || ((int)e["depth"]! == 0 && (bool)e["hasChildren"]!))
                continue;
            if (!(bool)e["present"]!)
            {
                skipped.Add((JsonNode?)(string?)e["name"]);
                continue;
            }
            if (tests.Count >= MaxTests)
                break;
            double factor = ScaleFactor(e) ?? 1;
            foreach (var f in PlanFor(e))
                tests.Add((e, f, factor));
        }
        var results = new JsonArray();
        int i = 0;
        foreach (var (e, feature, factor) in tests)
        {
            string id = (string)e["id"]!;
            _progress = $"Checking {e["name"]}: {feature} ({++i}/{tests.Count})";
            _sim.Events.Add("check", new JsonObject { ["event"] = "progress", ["text"] = _progress });
            var original = _sim.Rule(id)?.DeepClone() as JsonObject;
            _sim.SetRule(id, TestRule(e, feature, factor, original), save: true);
            await Task.Delay(Settle);
            var after = Elements.ForEditor(await _sim.RequestElements(TimeSpan.FromSeconds(3)), "mod").FirstOrDefault(x => (string?)x?["id"] == id) as JsonObject;
            var region = _sim.Rect((string)e["screen"]! == "top" ? "top" : "bottom");
            var (passed, detail) = after == null ? (false, "gone") : Judge(feature, e, after, region[2], factor);
            var r = new JsonObject { ["id"] = id, ["name"] = (string?)e["name"], ["feature"] = feature, ["passed"] = passed };
            if (!passed)
                r["detail"] = detail;
            results.Add((JsonNode)r);
            _sim.SetRule(id, original, save: true);
            await Task.Delay(Settle);
        }
        bool all = results.Count > 0 && results.All(r => (bool)r!["passed"]!);
        _progress = all ? "All checks passed" : results.Count == 0 ? "Nothing to check" : "Some checks failed";
        return new JsonObject
        {
            ["game"] = _sim.Game, ["engine"] = _sim.Bridge.Engine, ["time"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            ["passed"] = all, ["results"] = results, ["skipped"] = skipped,
        };
    }

    public static double? ScaleFactor(JsonObject e)
    {
        if (e["scale"] is not JsonObject r)
            return null;
        if ((double)r["max"]! >= 1.25)
            return 1.25;
        if ((double)r["min"]! <= 0.8)
            return 0.8;
        return null;
    }

    private static bool Allows(JsonObject e, string screen) =>
        e["screens"] is not JsonArray s || s.Any(x => (string?)x == screen);

    public static List<string> PlanFor(JsonObject e)
    {
        var plan = new List<string>();
        string screen = (string)e["screen"]!;
        string other = screen == "top" ? "bottom" : "top";
        if (Allows(e, other) && (screen == "top" || screen == "bottom"))
            plan.Add("screen");
        if (Allows(e, "hidden"))
            plan.Add("hide");
        if ((bool)e["move"]!)
            plan.Add("move");
        if (ScaleFactor(e) != null)
            plan.Add("resize");
        return plan;
    }

    private static JsonObject TestRule(JsonObject e, string feature, double factor, JsonObject? original)
    {
        var r = original?.DeepClone() as JsonObject ?? new JsonObject();
        string screen = (string)e["screen"]!;
        string pinned = (string?)r["screen"] is { Length: > 0 } s && s != "default" ? s : screen;
        switch (feature)
        {
            case "screen":
                r["screen"] = screen == "top" ? "bottom" : "top";
                r["x"] = 0;
                r["y"] = 0;
                break;
            case "hide":
                r["screen"] = "hidden";
                break;
            case "move":
                r["screen"] = pinned;
                r["x"] = ((double?)r["x"] ?? 0) + Shift;
                break;
            default:
                r["screen"] = pinned;
                r["scale"] = ((double?)r["scale"] ?? 1) * factor;
                break;
        }
        return r;
    }

    public static (bool, string) Judge(string feature, JsonObject before, JsonObject after, double regionW, double factor)
    {
        switch (feature)
        {
            case "screen":
                return ((string?)after["screen"] != (string?)before["screen"] && (string?)after["screen"] != "hidden", $"still on the {after["screen"]} screen");
            case "hide":
                return ((string?)after["screen"] == "hidden" || (double)after["w"]! <= 1 || (double)after["h"]! <= 1, "still visible");
            case "move":
            {
                double moved = (double)after["x"]! - (double)before["x"]!, expected = Shift * regionW;
                return (moved > expected * 0.5 && moved < expected * 1.5, $"moved {(int)moved} px, expected about {(int)expected}");
            }
            default:
            {
                double w = (double)before["w"]!, ratio = w > 0 ? (double)after["w"]! / w : 0;
                return (Math.Abs(ratio - factor) < 0.12, $"size x{ratio:0.00}, expected x{factor}");
            }
        }
    }
}
