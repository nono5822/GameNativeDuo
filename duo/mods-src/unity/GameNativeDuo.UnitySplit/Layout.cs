using System;
using System.Collections.Generic;
using System.IO;
using UnityEngine;

namespace GameNativeDuo.UnitySplit
{
    /// <summary>
    /// Where the top and bottom screens sit inside the game window, from GameNative Duo's
    /// %AppData%\GameNativeDuo\layout.json, e.g.
    /// {"screen":{"w":1920,"h":2160},"top":{"x":0,"y":0,"w":1920,"h":1080},"bottom":{"x":340,"y":1080,"w":1240,"h":1080},
    ///  "bridge":{"port":40123},...}.
    /// Rectangles are in window pixels with the origin at the top-left. "bridge" is the UDP port GameNative Duo listens
    /// on for this session (see <see cref="Bridge"/>); absent when the app has no bridge.
    /// </summary>
    internal sealed class Layout
    {
        public int ScreenW, ScreenH;
        public RectInt Top, Bottom;
        public int BridgePort;

        public static string Dir =>
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GameNativeDuo");

        public static string FilePath => Path.Combine(Dir, "layout.json");

        public static Layout Load(out string error)
        {
            error = "";
            try
            {
                if (!File.Exists(FilePath))
                {
                    error = FilePath + " not found";
                    return null;
                }
                object json = MiniJson.Parse(File.ReadAllText(FilePath));
                var screen = Need(json, "screen");
                var bridge = MiniJson.Obj(json, "bridge");
                return new Layout
                {
                    ScreenW = (int)MiniJson.Num(screen, "w", 0),
                    ScreenH = (int)MiniJson.Num(screen, "h", 0),
                    Top = Rect(Need(json, "top")),
                    Bottom = Rect(Need(json, "bottom")),
                    BridgePort = (int)MiniJson.Num(bridge, "port", 0),
                };
            }
            catch (Exception ex)
            {
                error = "could not read " + FilePath + ": " + ex.Message;
                return null;
            }
        }

        /// <summary>True when the game window is the tall window this layout was made for.</summary>
        public bool Matches(int width, int height) => Math.Abs(width - ScreenW) <= 2 && Math.Abs(height - ScreenH) <= 2;

        /// <summary>A window rectangle as a camera viewport rectangle (normalised, origin bottom-left).</summary>
        public Rect Viewport(RectInt r) =>
            new Rect((float)r.x / ScreenW, 1f - (float)(r.y + r.height) / ScreenH, (float)r.width / ScreenW, (float)r.height / ScreenH);

        public RectInt Region(DuoScreen screen) => screen == DuoScreen.Top ? Top : Bottom;

        public bool SameGeometry(Layout o) =>
            o != null && o.ScreenW == ScreenW && o.ScreenH == ScreenH && o.Top.Equals(Top) && o.Bottom.Equals(Bottom);

        public override string ToString() =>
            string.Format("screen {0}x{1}, top {2}, bottom {3}, bridge port {4}", ScreenW, ScreenH, Top, Bottom, BridgePort);

        private static Dictionary<string, object> Need(object json, string key)
        {
            var o = MiniJson.Obj(json, key);
            if (o == null)
                throw new FormatException("no \"" + key + "\" object");
            return o;
        }

        private static RectInt Rect(Dictionary<string, object> o) =>
            new RectInt((int)MiniJson.Num(o, "x", 0), (int)MiniJson.Num(o, "y", 0), (int)MiniJson.Num(o, "w", 0), (int)MiniJson.Num(o, "h", 0));
    }
}
