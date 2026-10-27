using BepInEx.Configuration;

namespace GameNativeDuo.HollowKnight
{
    /// <summary>BepInEx/config/app.gamenative.duo.hollowknight.cfg, read when the game starts.</summary>
    internal static class Settings
    {
        internal static ConfigEntry<bool> LiveMapEnabled;
        internal static ConfigEntry<bool> MapButtonZooms;
        internal static ConfigEntry<int> HudReservePx;
        internal static ConfigEntry<float> KnightZoomSize;
        internal static ConfigEntry<bool> WorldOnTop;

        public static void Bind(ConfigFile cfg)
        {
            LiveMapEnabled = cfg.Bind("Live map", "Enabled", true,
                "Show the map on the bottom screen while you play.");
            MapButtonZooms = cfg.Bind("Live map", "MapButtonZooms", true,
                "While the live map is shown, the map button switches its zoom (Knight / area / whole map) and the Knight keeps " +
                "moving. Off: the map button opens the game's own quick map, as usual.");
            HudReservePx = cfg.Bind("Live map", "HudReservePx", 150,
                "Bottom-screen pixels kept free above the map for the masks, soul and geo.");
            KnightZoomSize = cfg.Bind("Live map", "KnightZoomSize", 4f,
                "How much of the map the Knight zoom shows (half its height, in map units).");
            WorldOnTop = cfg.Bind("Screens", "WorldOnTopDuringInventory", true,
                "Keep drawing the game on the top screen while the inventory is open on the bottom screen (the game keeps " +
                "running then). Off: the top screen goes dark, as the game does on one screen.");
            cfg.ConfigReloaded += (s, e) => Plugin.Log.LogInfo("Settings reloaded");
        }
    }
}
