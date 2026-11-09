using System.Globalization;
using GameNativeDuo.UnitySplit;

namespace GameNativeDuo.HollowKnight
{
    /// <summary>
    /// The live map's settings, from the UI layout (the editor on the bottom screen and the PC editor): the element
    /// <see cref="ElementId"/> is on (bottom screen) or off (hidden), its size and position, and its options. Nothing
    /// to set up: without a rule every setting has its default. Re-read whenever the app rewrites ui.json.
    /// </summary>
    internal static class Settings
    {
        internal const string ElementId = "GameNativeDuo/LiveMap";

        internal static bool Enabled = true;
        internal static float Scale = 1f, X, Y;
        internal static LiveMap.Zoom StartZoom = LiveMap.Zoom.Knight;
        internal static bool DriftBack = true;
        internal static float Dim = 0.72f;
        internal static bool KnightAlways;
        internal static bool MapButtonZooms = true;
        internal static bool WorldOnTop = true;

        /// <summary>Bottom-screen pixels kept free above the map for the masks, soul and geo (the map's default top edge).</summary>
        internal const int HudReservePx = 150;

        /// <summary>How much of the map the Knight zoom shows (half its height, in map units).</summary>
        internal const float KnightZoomSize = 4f;

        private static int _version = -1;

        /// <summary>
        /// The element's options as the editors show them (see engine-adapter-contract.md, "Plugin elements"): the first
        /// value of each is the default.
        /// </summary>
        internal const string OptionsJson =
            "\"screens\":[\"bottom\",\"hidden\"]," +
            "\"options\":[" +
            "{\"id\":\"zoom\",\"label\":\"Start zoom\",\"values\":[[\"knight\",\"Knight\"],[\"area\",\"Area\"],[\"world\",\"Whole map\"]]}," +
            "{\"id\":\"follow\",\"label\":\"After you pan\",\"values\":[[\"back\",\"Back to the Knight\"],[\"stay\",\"Stay\"]]}," +
            "{\"id\":\"dim\",\"label\":\"Under menus\",\"values\":[[\"medium\",\"Dim\"],[\"light\",\"Dim a little\"],[\"strong\",\"Dim a lot\"],[\"hide\",\"Hide\"]]}," +
            "{\"id\":\"knight\",\"label\":\"Knight on the map\",\"values\":[[\"compass\",\"With Wayward Compass\"],[\"always\",\"Always\"]]}," +
            "{\"id\":\"button\",\"label\":\"Map button\",\"values\":[[\"zoom\",\"Switches zoom\"],[\"game\",\"Game's quick map\"]]}," +
            "{\"id\":\"inventory\",\"label\":\"Top screen in inventory\",\"values\":[[\"world\",\"Game\"],[\"dark\",\"Dark\"]]}" +
            "]";

        /// <summary>Reads the element's rule again when ui.json changed (cheap: a version check per frame).</summary>
        internal static bool Refresh()
        {
            Split split = Split.Instance;
            if (split == null || split.Router == null || split.Router.RulesVersion == _version)
                return false;
            _version = split.Router.RulesVersion;
            UiRule rule = split.Router.Rule(ElementId);
            Enabled = rule == null || rule.Screen != DuoScreen.Hidden;
            Scale = rule != null ? rule.Scale : 1f;
            X = rule != null ? rule.X : 0f;
            Y = rule != null ? rule.Y : 0f;
            string zoom = rule != null ? rule.Option("zoom", "knight") : "knight";
            StartZoom = zoom == "world" ? LiveMap.Zoom.World : zoom == "area" ? LiveMap.Zoom.Area : LiveMap.Zoom.Knight;
            DriftBack = rule == null || rule.Option("follow", "back") != "stay";
            switch (rule != null ? rule.Option("dim", "medium") : "medium")
            {
                case "light": Dim = 0.45f; break;
                case "strong": Dim = 0.9f; break;
                case "hide": Dim = 1f; break;
                default: Dim = 0.72f; break;
            }
            KnightAlways = rule != null && rule.Option("knight", "compass") == "always";
            MapButtonZooms = rule == null || rule.Option("button", "zoom") != "game";
            WorldOnTop = rule == null || rule.Option("inventory", "world") != "dark";
            Plugin.Log.LogInfo("Live map settings: " + (Enabled ? "on" : "off") + ", scale " + Scale.ToString("0.##", CultureInfo.InvariantCulture) +
                ", shift " + X.ToString("0.###", CultureInfo.InvariantCulture) + "/" + Y.ToString("0.###", CultureInfo.InvariantCulture) +
                ", zoom " + StartZoom + ", " + (DriftBack ? "drifts back" : "stays") + ", dim " + Dim + ", knight " + (KnightAlways ? "always" : "compass") +
                ", button " + (MapButtonZooms ? "zoom" : "game") + ", inventory " + (WorldOnTop ? "world" : "dark"));
            return true;
        }
    }
}
