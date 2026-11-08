using System.Globalization;
using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>
/// `duo test SCRIPT`: one command per line, the same against the sim and the device (generalises hk-test.txt /
/// sdv-test.txt). Exit code 0 when every `expect` held.
/// <code>
/// # comment
/// wait 2                         seconds
/// tap 620 540                    bottom-screen pixels
/// swipe 100 500 900 500 300      from, to, milliseconds
/// command hk.map.zoom {"level":"area"}
/// rule Hand {"screen":"top"}     set the user's rule (null clears it)
/// subscribe hk.geo hk.charms
/// expect element Hand on top     (or: on bottom, hidden, present, absent)
/// expect state hk.geo = 120
/// expect command hk.map.zoom ok
/// screenshot bottom shot.png
/// </code>
/// </summary>
public static class TestScript
{
    public static async Task<int> Run(DuoClient c, string path)
    {
        int failed = 0, line = 0;
        foreach (var raw in File.ReadAllLines(path))
        {
            line++;
            string s = raw.Trim();
            if (s.Length == 0 || s.StartsWith('#'))
                continue;
            string[] w = s.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            string rest = s[w[0].Length..].Trim();
            try
            {
                string? problem = await Step(c, w, rest);
                if (problem != null)
                {
                    failed++;
                    Console.WriteLine($"✗ {path}:{line}: {s}\n    {problem}");
                }
                else if (w[0] == "expect")
                    Console.WriteLine($"✓ {s}");
            }
            catch (Exception ex) when (ex is DuoException or FormatException or IndexOutOfRangeException or System.Text.Json.JsonException)
            {
                failed++;
                Console.WriteLine($"✗ {path}:{line}: {s}\n    {ex.Message}");
            }
        }
        Console.WriteLine(failed == 0 ? "All expectations held" : $"{failed} failed");
        return failed == 0 ? 0 : 1;
    }

    private static double D(string v) => double.Parse(v, CultureInfo.InvariantCulture);

    private static async Task<string?> Step(DuoClient c, string[] w, string rest)
    {
        switch (w[0])
        {
            case "wait":
                await Task.Delay(TimeSpan.FromSeconds(D(w[1])));
                return null;
            case "tap":
                await c.Post("/api/touch", new JsonObject { ["x"] = D(w[1]), ["y"] = D(w[2]), ["action"] = "tap" });
                return null;
            case "swipe":
            {
                double x1 = D(w[1]), y1 = D(w[2]), x2 = D(w[3]), y2 = D(w[4]), ms = w.Length > 5 ? D(w[5]) : 300;
                int steps = Math.Max(2, (int)(ms / 16));
                await c.Post("/api/touch", new JsonObject { ["x"] = x1, ["y"] = y1, ["action"] = "down" });
                for (int i = 1; i <= steps; i++)
                {
                    await Task.Delay((int)(ms / steps));
                    await c.Post("/api/touch", new JsonObject { ["x"] = x1 + (x2 - x1) * i / steps, ["y"] = y1 + (y2 - y1) * i / steps, ["action"] = "move" });
                }
                await c.Post("/api/touch", new JsonObject { ["x"] = x2, ["y"] = y2, ["action"] = "up" });
                return null;
            }
            case "command":
            {
                string json = rest.Length > w[1].Length ? rest[w[1].Length..].Trim() : "{}";
                await c.Post("/api/command", new JsonObject { ["cmd"] = w[1], ["args"] = JsonNode.Parse(json) });
                return null;
            }
            case "rule":
            {
                string json = rest[w[1].Length..].Trim();
                await c.Post("/api/rule", new JsonObject { ["id"] = w[1], ["rule"] = JsonNode.Parse(json) });
                return null;
            }
            case "subscribe":
                await c.Post("/api/companion/subscribe", new JsonObject { ["keys"] = new JsonArray(w.Skip(1).Select(k => (JsonNode)k).ToArray()) });
                return null;
            case "screenshot":
                File.WriteAllBytes(w.Length > 2 ? w[2] : w[1] + ".png", await c.Bytes($"/api/snapshot/{w[1]}.jpg"));
                return null;
            case "expect":
                return await Expect(c, w, rest);
            default:
                return "unknown step " + w[0];
        }
    }

    private static async Task<string?> Expect(DuoClient c, string[] w, string rest)
    {
        switch (w[1])
        {
            case "element":
            {
                string id = w[2], what = w[^1];
                var list = (await c.Get("/api/elements"))["elements"] as JsonArray ?? new JsonArray();
                var e = list.FirstOrDefault(x => (string?)x?["id"] == id);
                if (e == null)
                    return "no element " + id;
                bool present = (bool?)e["present"] ?? true;
                string screen = (string?)e["screen"] ?? "";
                bool ok = what switch
                {
                    "present" => present,
                    "absent" => !present,
                    _ => screen == what,
                };
                return ok ? null : $"{id} is on {screen}{(present ? "" : " (absent)")}";
            }
            case "state":
            {
                string key = w[2], want = rest[(rest.IndexOf('=') + 1)..].Trim();
                var values = (JsonObject)(await c.Get("/api/companion/state?prefix=" + Uri.EscapeDataString(key)))["values"]!;
                var got = values[key];
                if (got == null)
                    return key + " has no value (subscribe to it first?)";
                string gotText = got is JsonValue v && v.TryGetValue<string>(out var str) ? str : got.ToJsonString();
                return gotText == want.Trim('"') || got.ToJsonString() == want ? null : $"{key} = {got.ToJsonString()}";
            }
            case "command":
            {
                var r = await c.Post("/api/command", new JsonObject { ["cmd"] = w[2], ["args"] = new JsonObject() });
                string want = w.Length > 3 ? w[3] : "ok";
                return (string?)r["status"] == want ? null : $"{r["status"]}: {r["message"]}";
            }
            default:
                return "unknown expectation " + w[1];
        }
    }
}
