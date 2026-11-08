using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;

namespace GameNativeDuo.Sdk
{
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    enum DuoScreen
    {
        /// <summary>No choice made: the mod's default.</summary>
        Default,
        Bottom,
        Top,
        Hidden,
    }

    /// <summary>What the user chose for one UI element. Offsets are fractions of the target screen's size (+x right, +y down).</summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    sealed class UiRule
    {
        public DuoScreen Screen;
        public float Scale = 1f;
        public float X, Y;

        /// <summary>Added by the mod's own heuristics (e.g. crosshairs stay on top), not by the user.</summary>
        public bool Implicit;

        /// <summary>The element's own settings (option id -> value id), for elements that declare options; null when none.</summary>
        public Dictionary<string, string> Options;

        /// <summary>The value chosen for option <paramref name="id"/>, or <paramref name="fallback"/>.</summary>
        public string Option(string id, string fallback)
        {
            string v;
            return Options != null && Options.TryGetValue(id, out v) && !string.IsNullOrEmpty(v) ? v : fallback;
        }

        public bool ChangesTransform => Screen == DuoScreen.Hidden || Scale != 1f || X != 0f || Y != 0f;

        public bool SameAs(UiRule o) => o != null && o.Screen == Screen && o.Scale == Scale && o.X == X && o.Y == Y;

        public static DuoScreen ParseScreen(string s)
        {
            switch (s)
            {
                case "top": return DuoScreen.Top;
                case "bottom": return DuoScreen.Bottom;
                case "hidden": return DuoScreen.Hidden;
                default: return DuoScreen.Default;
            }
        }

        public static string ScreenId(DuoScreen s)
        {
            switch (s)
            {
                case DuoScreen.Top: return "top";
                case DuoScreen.Hidden: return "hidden";
                default: return "bottom";
            }
        }
    }

    /// <summary>The bottom screen's background from ui.json (engine-adapter-contract.md): mode, color, dim, blur.</summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    sealed class UiBackground
    {
        public static readonly UiBackground Default = new UiBackground();

        /// <summary>game, blur, color or image.</summary>
        public string Mode = "game";

        /// <summary>0xRRGGBB.</summary>
        public uint Rgb = 0x101322;

        public float Dim;
        public float Blur = 0.5f;
        public bool HasImage;

        public bool UsesColor => Mode == "color" || Mode == "image";
        public bool UsesImage => Mode == "image" && HasImage;

        public static string ImagePath => Path.Combine(DuoPaths.Dir, "background.jpg");
    }

    /// <summary>
    /// The user's UI layout for this game, ui.json, written by the app:
    /// {"format":"gamenative-duo/ui-layout","version":1,"game":"STEAM_433340","engine":"unity-ugui",
    ///  "elements":{"HUD/Canvas/Crosshair":{"screen":"top","scale":1.2,"x":0,"y":0,"options":{...}}},"background":{...}}.
    /// </summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    sealed class UiFile
    {
        public static readonly UiFile Empty = new UiFile();

        public readonly Dictionary<string, UiRule> Rules = new Dictionary<string, UiRule>();
        public UiBackground Background = UiBackground.Default;

        public static UiFile Load(out string error)
        {
            error = "";
            try
            {
                if (!File.Exists(DuoPaths.UiFile))
                    return new UiFile();
                return Parse(File.ReadAllText(DuoPaths.UiFile));
            }
            catch (Exception ex)
            {
                error = "could not read " + DuoPaths.UiFile + ": " + ex.Message;
                return new UiFile();
            }
        }

        public static UiFile Parse(string text)
        {
            var result = new UiFile();
            object root = MiniJson.Parse(text);
            var bg = MiniJson.Obj(root, "background");
            if (bg != null)
            {
                var b = new UiBackground
                {
                    Mode = MiniJson.Str(bg, "mode", "game"),
                    Dim = Clamp((float)MiniJson.Num(bg, "dim", 0), 0f, 0.9f, 0f),
                    Blur = Clamp((float)MiniJson.Num(bg, "blur", 0.5), 0f, 1f, 0.5f),
                    HasImage = MiniJson.Str(bg, "image", "").Length > 0,
                };
                string hex = MiniJson.Str(bg, "color", "");
                uint rgb;
                if (hex.Length == 7 && hex[0] == '#' && uint.TryParse(hex.Substring(1), NumberStyles.HexNumber, null, out rgb))
                    b.Rgb = rgb;
                result.Background = b;
            }
            var elements = MiniJson.Obj(root, "elements");
            if (elements == null)
                return result;
            foreach (var pair in elements)
            {
                var o = pair.Value as Dictionary<string, object>;
                if (o == null)
                    continue;
                var rule = new UiRule
                {
                    Screen = UiRule.ParseScreen(MiniJson.Str(o, "screen", "default")),
                    Scale = Clamp((float)MiniJson.Num(o, "scale", 1), 0.1f, 5f, 1f),
                    X = Clamp((float)MiniJson.Num(o, "x", 0), -2f, 2f, 0f),
                    Y = Clamp((float)MiniJson.Num(o, "y", 0), -2f, 2f, 0f),
                };
                var options = MiniJson.Obj(o, "options");
                if (options != null)
                {
                    rule.Options = new Dictionary<string, string>();
                    foreach (var opt in options)
                    {
                        if (opt.Value is string)
                            rule.Options[opt.Key] = (string)opt.Value;
                        else if (opt.Value is double)
                            rule.Options[opt.Key] = ((double)opt.Value).ToString(CultureInfo.InvariantCulture);
                        else if (opt.Value is bool)
                            rule.Options[opt.Key] = (bool)opt.Value ? "true" : "false";
                    }
                }
                result.Rules[pair.Key] = rule;
            }
            return result;
        }

        private static float Clamp(float v, float min, float max, float fallback) => float.IsNaN(v) ? fallback : Math.Max(min, Math.Min(max, v));
    }
}
