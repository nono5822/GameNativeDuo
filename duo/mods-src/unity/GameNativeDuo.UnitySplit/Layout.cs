using System;
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

        public static string Dir => DuoPaths.Dir;

        public static string FilePath => DuoPaths.LayoutFile;

        public static Layout Load(out string error)
        {
            var d = DuoLayout.Load(out error);
            if (d == null)
                return null;
            return new Layout
            {
                ScreenW = d.ScreenW,
                ScreenH = d.ScreenH,
                Top = new RectInt(d.Top.X, d.Top.Y, d.Top.W, d.Top.H),
                Bottom = new RectInt(d.Bottom.X, d.Bottom.Y, d.Bottom.W, d.Bottom.H),
                BridgePort = d.BridgePort,
            };
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
    }
}
