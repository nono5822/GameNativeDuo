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

internal enum BackgroundMode
{
    /// <summary>The world continues on the bottom screen (the default).</summary>
    Game,
    Blur,
    Color,
    Image,
}

/// <summary>What the bottom screen shows behind the UI. <see cref="Dim"/> 0..0.9, <see cref="Blur"/> 0..1.</summary>
internal sealed record Background(BackgroundMode Mode, Microsoft.Xna.Framework.Color Color, float Dim, float Blur, bool HasImage)
{
    public static readonly Background Default = new(BackgroundMode.Game, new Microsoft.Xna.Framework.Color(16, 19, 34), 0f, 0.5f, false);

    public static string ImagePath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GameNativeDuo", "background.jpg");
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

    public Background Background { get; private set; } = Background.Default;

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
            if (doc.RootElement.TryGetProperty("background", out JsonElement bg) && bg.ValueKind == JsonValueKind.Object)
                rules.Background = ParseBackground(bg);
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

    private static Background ParseBackground(JsonElement o)
    {
        var color = Background.Default.Color;
        string? hex = Str(o, "color");
        if (hex != null && hex.Length == 7 && hex[0] == '#' && uint.TryParse(hex.AsSpan(1), System.Globalization.NumberStyles.HexNumber, null, out uint rgb))
            color = new Microsoft.Xna.Framework.Color((int)(rgb >> 16) & 0xFF, (int)(rgb >> 8) & 0xFF, (int)rgb & 0xFF);
        return new Background(
            Mode: Str(o, "mode") switch
            {
                "blur" => BackgroundMode.Blur,
                "color" => BackgroundMode.Color,
                "image" => BackgroundMode.Image,
                _ => BackgroundMode.Game,
            },
            Color: color,
            Dim: Math.Clamp(Num(o, "dim", 0f), 0f, 0.9f),
            Blur: Math.Clamp(Num(o, "blur", 0.5f), 0f, 1f),
            HasImage: !string.IsNullOrEmpty(Str(o, "image")));
    }

    private static string? Str(JsonElement o, string key) =>
        o.TryGetProperty(key, out JsonElement v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static float Num(JsonElement o, string key, float fallback) =>
        o.TryGetProperty(key, out JsonElement v) && v.ValueKind == JsonValueKind.Number && float.IsFinite(v.GetSingle()) ? v.GetSingle() : fallback;
}
