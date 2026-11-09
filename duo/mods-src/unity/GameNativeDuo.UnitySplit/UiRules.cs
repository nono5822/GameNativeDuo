using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;
using UnityEngine;

namespace GameNativeDuo.UnitySplit
{
    /// <summary>
    /// GameNative Duo's UI layout for this game, %AppData%\GameNativeDuo\ui.json, written by the app (the user's own
    /// layout, or the game's bundled or community preset). Engine-neutral format:
    /// {"format":"gamenative-duo/ui-layout","version":1,"game":"STEAM_433340","engine":"unity-ugui",
    ///  "elements":{"HUD/Canvas/Crosshair":{"screen":"top","scale":1.2,"x":0,"y":0}}}.
    /// Element ids are paths of object names from the scene root, with "#n" for the n-th of same-named siblings.
    /// </summary>
    /// <summary>The bottom screen's background (see engine-adapter-contract.md): color and image here; the world is not drawn there.</summary>
    internal sealed class Background
    {
        public static readonly Background Default = new Background();

        public string Mode = "game";
        public Color Color = new Color32(16, 19, 34, 255);
        public float Dim;
        public bool HasImage;

        public bool UsesColor => Mode == "color" || Mode == "image";
        public bool UsesImage => Mode == "image" && HasImage;

        public static string ImagePath => Path.Combine(Layout.Dir, "background.jpg");
    }

    internal sealed class UiRules
    {
        public static readonly UiRules Empty = new UiRules();

        /// <summary>Names that are almost always aimed through the game view, so they stay with the game on the top screen.</summary>
        private static readonly Regex AimNames = new Regex("crosshair|reticle|reticule", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

        private readonly Dictionary<string, UiRule> _rules = new Dictionary<string, UiRule>();

        public static string FilePath => DuoPaths.UiFile;

        public int Count => _rules.Count;

        public Background Background = Background.Default;

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
            var rules = new UiRules();
            var file = UiFile.Load(out error);
            var bg = file.Background;
            rules.Background = new Background
            {
                Mode = bg.Mode,
                Color = new Color32((byte)(bg.Rgb >> 16), (byte)(bg.Rgb >> 8), (byte)bg.Rgb, 255),
                Dim = bg.Dim,
                HasImage = bg.HasImage,
            };
            foreach (var pair in file.Rules)
                rules._rules[pair.Key] = pair.Value;
            return rules;
        }

    }
}
