using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>
/// elements.json as the editor page wants it (the app's `PcEditorServer.elementJson`): depth, hasChildren, move,
/// scale range, and the same "limits" sentence the device shows (`EditCapabilities.limits`).
/// </summary>
public static class Elements
{
    public static JsonArray ForEditor(JsonObject? dump, string owner)
    {
        var result = new JsonArray();
        if (dump?["elements"] is not JsonArray list)
            return result;
        var depth = new int[list.Count];
        var hasChildren = new bool[list.Count];
        for (int i = 0; i < list.Count; i++)
        {
            int parent = (int?)list[i]?["parent"] ?? -1;
            if (parent >= 0 && parent < i)
            {
                depth[i] = depth[parent] + 1;
                hasChildren[parent] = true;
            }
        }
        for (int i = 0; i < list.Count; i++)
        {
            if (list[i] is not JsonObject e)
                continue;
            var f = e["features"] as JsonObject;
            var screens = (f?["screens"] ?? e["screens"]) as JsonArray;
            bool move = (bool?)f?["move"] ?? true;
            JsonNode? scale = f?["scale"] switch
            {
                JsonValue v when v.TryGetValue<bool>(out var b) && !b => false,
                JsonObject r => new JsonObject { ["min"] = (double?)r["min"] ?? 0.25, ["max"] = (double?)r["max"] ?? 4 },
                _ => new JsonObject { ["min"] = 0.25, ["max"] = 4.0 },
            };
            bool present = (bool?)e["present"] ?? true;
            var o = new JsonObject
            {
                ["id"] = (string?)e["id"], ["name"] = (string?)e["name"], ["parent"] = (int?)e["parent"] ?? -1, ["depth"] = depth[i],
                ["screen"] = (string?)e["screen"] ?? "bottom",
                ["x"] = (double?)e["x"] ?? 0, ["y"] = (double?)e["y"] ?? 0, ["w"] = (double?)e["w"] ?? 0, ["h"] = (double?)e["h"] ?? 0,
                ["graphic"] = (bool?)e["graphic"] ?? true, ["hasChildren"] = hasChildren[i], ["present"] = present,
                ["move"] = move, ["scale"] = scale,
            };
            if ((string?)e["when"] is { Length: > 0 } when)
                o["when"] = when;
            if (screens != null)
                o["screens"] = screens.DeepClone();
            if (e["options"] is JsonArray opts)
                o["options"] = opts.DeepClone();
            var limits = Limits(o, owner);
            if (limits != null)
                o["limits"] = limits;
            result.Add((JsonNode)o);
        }
        return result;
    }

    /// <summary>One sentence on what the editors cannot do to an element, or null (same words as the device).</summary>
    public static string? Limits(JsonObject e, string owner)
    {
        string name = (string?)e["name"] ?? "";
        bool canMove = (bool?)e["move"] ?? true;
        bool canScale = e["scale"] is JsonObject;
        var screens = (e["screens"] as JsonArray)?.Select(s => (string?)s).ToList();
        if (!((bool?)e["present"] ?? true))
        {
            string when = ((string?)e["when"] ?? "not on screen right now").TrimEnd('.');
            when = when.Length > 0 ? char.ToLowerInvariant(when[0]) + when[1..] : when;
            var later = new List<string>();
            if (canMove) later.Add("move");
            if (canScale) later.Add("resize");
            string then = later.Count == 0 ? "" : "; " + string.Join(" or ", later) + " it when it shows";
            return $"{name} shows {when}. Choose its screen now{then}.";
        }
        var verbs = new List<string>();
        if (!canMove) verbs.Add("move");
        if (!canScale) verbs.Add("resize");
        if (screens != null && !screens.Contains("hidden")) verbs.Add("hide");
        var places = new List<string>();
        if (screens != null && !screens.Contains("top")) places.Add("top");
        if (screens != null && !screens.Contains("bottom")) places.Add("bottom");
        string place = string.Join(" or ", places);
        if (verbs.Count == 0 && places.Count == 0)
            return null;
        if (verbs.Count == 0)
            return $"{owner} cannot put {name} on the {place} screen.";
        if (places.Count == 0)
            return $"{owner} cannot {Join(verbs)} {name}.";
        return $"{owner} cannot {Join(verbs)} {name} or put it on the {place} screen.";
    }

    private static string Join(List<string> v) => v.Count switch
    {
        1 => v[0],
        2 => v[0] + " or " + v[1],
        _ => string.Join(", ", v.Take(v.Count - 1)) + " or " + v[^1],
    };
}
