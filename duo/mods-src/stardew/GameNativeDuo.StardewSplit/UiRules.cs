using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;

namespace GameNativeDuo.StardewSplit;

internal enum DuoScreen
{
    /// <summary>No choice made: where the mod puts it (the bottom screen).</summary>
    Default,
    Bottom,
    Top,
    Hidden,
}

/// <summary>What the user chose for one UI element. Offsets are fractions of the target screen's size (+x right, +y down).</summary>
internal sealed record UiRule(DuoScreen Screen, float Scale, float X, float Y)
{
    public bool ChangesTransform => Scale != 1f || X != 0f || Y != 0f;

    public static string ScreenId(DuoScreen s) => s switch
    {
        DuoScreen.Top => "top",
        DuoScreen.Hidden => "hidden",
        _ => "bottom",
    };
}

/// <summary>
/// GameNative Duo's UI layout for this game, %AppData%\GameNativeDuo\ui.json (format "gamenative-duo/ui-layout", see
/// duo/docs/engine-adapter-contract.md). Element ids for Stardew Valley: "hud/toolbar", "hud/clock", "hud/buffs",
/// "hud/bars", "hud/chat".
/// </summary>
internal sealed class UiRules
{
    public static readonly UiRules Empty = new();

    private readonly Dictionary<string, UiRule> _rules = new();

    public static string FilePath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GameNativeDuo", "ui.json");

    public int Count => _rules.Count;

    public UiRule? Get(string id) => _rules.TryGetValue(id, out UiRule? r) ? r : null;

    public static UiRules Load(out string error)
    {
        error = "";
        var rules = new UiRules();
        try
        {
            if (!File.Exists(FilePath))
                return rules;
            using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(FilePath));
            if (!doc.RootElement.TryGetProperty("elements", out JsonElement elements) || elements.ValueKind != JsonValueKind.Object)
                return rules;
            foreach (JsonProperty p in elements.EnumerateObject())
            {
                JsonElement o = p.Value;
                if (o.ValueKind != JsonValueKind.Object)
                    continue;
                rules._rules[p.Name] = new UiRule(
                    Screen: Str(o, "screen") switch
                    {
                        "top" => DuoScreen.Top,
                        "bottom" => DuoScreen.Bottom,
                        "hidden" => DuoScreen.Hidden,
                        _ => DuoScreen.Default,
                    },
                    Scale: Math.Clamp(Num(o, "scale", 1f), 0.25f, 4f),
                    X: Math.Clamp(Num(o, "x", 0f), -2f, 2f),
                    Y: Math.Clamp(Num(o, "y", 0f), -2f, 2f));
            }
        }
        catch (Exception ex)
        {
            error = $"could not read {FilePath}: {ex.Message}";
        }
        return rules;
    }

    private static string? Str(JsonElement o, string key) =>
        o.TryGetProperty(key, out JsonElement v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static float Num(JsonElement o, string key, float fallback) =>
        o.TryGetProperty(key, out JsonElement v) && v.ValueKind == JsonValueKind.Number && float.IsFinite(v.GetSingle()) ? v.GetSingle() : fallback;
}
