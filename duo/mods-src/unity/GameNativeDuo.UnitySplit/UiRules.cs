using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;

namespace GameNativeDuo.UnitySplit
{
    internal enum DuoScreen
    {
        /// <summary>No choice made: the plugin's default (the bottom screen).</summary>
        Default,
        Bottom,
        Top,
        Hidden,
    }

    /// <summary>What the user chose for one UI element. Offsets are fractions of the target screen's size (+x right, +y down).</summary>
    internal sealed class UiRule
    {
        public DuoScreen Screen;
        public float Scale = 1f;
        public float X, Y;

        /// <summary>Added by the plugin's own heuristics (e.g. crosshairs stay on top), not by the user.</summary>
        public bool Implicit;

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

    /// <summary>
    /// GameNative Duo's UI layout for this game, %AppData%\GameNativeDuo\ui.json, written by the app (the user's own
    /// layout, or the game's bundled or community preset). Engine-neutral format:
    /// {"format":"gamenative-duo/ui-layout","version":1,"game":"STEAM_433340","engine":"unity-ugui",
    ///  "elements":{"HUD/Canvas/Crosshair":{"screen":"top","scale":1.2,"x":0,"y":0}}}.
    /// Element ids are paths of object names from the scene root, with "#n" for the n-th of same-named siblings.
    /// </summary>
    internal sealed class UiRules
    {
        public static readonly UiRules Empty = new UiRules();

        /// <summary>Names that are almost always aimed through the game view, so they stay with the game on the top screen.</summary>
        private static readonly Regex AimNames = new Regex("crosshair|reticle|reticule", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

        private readonly Dictionary<string, UiRule> _rules = new Dictionary<string, UiRule>();

        public static string FilePath => Path.Combine(Layout.Dir, "ui.json");

        public int Count => _rules.Count;

        public IEnumerable<KeyValuePair<string, UiRule>> All => _rules;

        public UiRule Get(string id)
        {
            UiRule r;
            return id != null && _rules.TryGetValue(id, out r) ? r : null;
        }

        public static bool IsAimElement(string name) => AimNames.IsMatch(name);

        /// <summary>
        /// Adds a heuristic rule. A rule from the layout file wins, but one that only resizes or shifts the element
        /// (no screen) keeps the heuristic's screen.
        /// </summary>
        public bool AddImplicit(string id, DuoScreen screen)
        {
            UiRule existing;
            if (_rules.TryGetValue(id, out existing))
            {
                if (existing.Screen == DuoScreen.Default)
                    existing.Screen = screen;
                return false;
            }
            _rules[id] = new UiRule { Screen = screen, Implicit = true };
            return true;
        }

        public static UiRules Load(out string error)
        {
            error = "";
            var rules = new UiRules();
            try
            {
                if (!File.Exists(FilePath))
                    return rules;
                var elements = MiniJson.Obj(MiniJson.Parse(File.ReadAllText(FilePath)), "elements");
                if (elements == null)
                    return rules;
                foreach (var pair in elements)
                {
                    var o = pair.Value as Dictionary<string, object>;
                    if (o == null)
                        continue;
                    rules._rules[pair.Key] = new UiRule
                    {
                        Screen = UiRule.ParseScreen(MiniJson.Str(o, "screen", "default")),
                        Scale = Clamp((float)MiniJson.Num(o, "scale", 1), 0.1f, 5f, 1f),
                        X = Clamp((float)MiniJson.Num(o, "x", 0), -2f, 2f, 0f),
                        Y = Clamp((float)MiniJson.Num(o, "y", 0), -2f, 2f, 0f),
                    };
                }
            }
            catch (Exception ex)
            {
                error = "could not read " + FilePath + ": " + ex.Message;
            }
            return rules;
        }

        private static float Clamp(float v, float min, float max, float fallback) => float.IsNaN(v) ? fallback : Math.Max(min, Math.Min(max, v));
    }
}
