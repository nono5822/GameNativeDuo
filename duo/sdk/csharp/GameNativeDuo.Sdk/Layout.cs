using System;
using System.Collections.Generic;
using System.IO;

namespace GameNativeDuo.Sdk
{
    /// <summary>A rectangle in window pixels, origin top-left.</summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    struct DuoRect : IEquatable<DuoRect>
    {
        public int X, Y, W, H;

        public DuoRect(int x, int y, int w, int h)
        {
            X = x;
            Y = y;
            W = w;
            H = h;
        }

        public bool Contains(float x, float y) => x >= X && y >= Y && x < X + W && y < Y + H;

        public bool Equals(DuoRect o) => o.X == X && o.Y == Y && o.W == W && o.H == H;

        public override bool Equals(object obj) => obj is DuoRect && Equals((DuoRect)obj);

        public override int GetHashCode() => ((X * 31 + Y) * 31 + W) * 31 + H;

        public override string ToString() => string.Format("{0},{1} {2}x{3}", X, Y, W, H);
    }

    /// <summary>Where GameNative Duo's files live in the prefix: %AppData%\GameNativeDuo (settable for tests and the simulator).</summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    static class DuoPaths
    {
        private static string _dir;

        public static string Dir
        {
            get { return _dir ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GameNativeDuo"); }
            set { _dir = value; }
        }

        public static string LayoutFile => Path.Combine(Dir, "layout.json");
        public static string BridgeFile => Path.Combine(Dir, "bridge.json");
        public static string UiFile => Path.Combine(Dir, "ui.json");
        public static string ElementsFile => Path.Combine(Dir, "elements.json");
        public static string FeaturesFile => Path.Combine(Dir, "features.json");
        public static string CompanionApiFile => Path.Combine(Dir, "companion-api.json");

        /// <summary>Writes <paramref name="text"/> through a temporary file, so the app never reads half a file.</summary>
        public static void WriteAtomic(string path, string text)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            string tmp = path + ".tmp";
            File.WriteAllText(tmp, text);
            if (File.Exists(path))
                File.Delete(path);
            File.Move(tmp, path);
        }
    }

    /// <summary>
    /// Where the top and bottom screens sit inside the game window, from layout.json, e.g.
    /// {"screen":{"w":1920,"h":2160},"top":{"x":0,"y":0,"w":1920,"h":1080},"bottom":{"x":340,"y":1080,"w":1240,"h":1080},
    ///  "gap":249,"bottomScale":1.142,"bottomShift":0,"bridge":{"port":40123}}.
    /// Act only while the window <see cref="Matches"/> it: a stale file is then harmless.
    /// </summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    sealed class DuoLayout
    {
        public int ScreenW, ScreenH;
        public DuoRect Top, Bottom;

        /// <summary>World rows hidden by the hinge and bezels, in window pixels (0 when absent).</summary>
        public int Gap;

        /// <summary>Bottom-screen pixels per top-screen pixel of the same physical length (1 when absent).</summary>
        public float BottomScale = 1f;

        /// <summary>Horizontal offset of the bottom screen's centre from the top's, window pixels.</summary>
        public int BottomShift;

        /// <summary>The app's bridge port this session, 0 without one.</summary>
        public int BridgePort;

        public static DuoLayout Load(out string error) => Load(DuoPaths.LayoutFile, out error);

        public static DuoLayout Load(string path, out string error)
        {
            error = "";
            try
            {
                if (!File.Exists(path))
                {
                    error = path + " not found";
                    return null;
                }
                return Parse(File.ReadAllText(path));
            }
            catch (Exception ex)
            {
                error = "could not read " + path + ": " + ex.Message;
                return null;
            }
        }

        public static DuoLayout Parse(string text)
        {
            object json = MiniJson.Parse(text);
            var screen = Need(json, "screen");
            var root = json as Dictionary<string, object>;
            return new DuoLayout
            {
                ScreenW = (int)MiniJson.Num(screen, "w", 0),
                ScreenH = (int)MiniJson.Num(screen, "h", 0),
                Top = Rect(Need(json, "top")),
                Bottom = Rect(Need(json, "bottom")),
                Gap = (int)MiniJson.Num(root, "gap", 0),
                BottomScale = (float)MiniJson.Num(root, "bottomScale", 1),
                BottomShift = (int)MiniJson.Num(root, "bottomShift", 0),
                BridgePort = (int)MiniJson.Num(MiniJson.Obj(json, "bridge"), "port", 0),
            };
        }

        /// <summary>The bridge port from bridge.json (sessions without a split have no layout.json), or 0.</summary>
        public static int ReadBridgePort()
        {
            try
            {
                if (!File.Exists(DuoPaths.BridgeFile))
                    return 0;
                return (int)MiniJson.Num(MiniJson.Parse(File.ReadAllText(DuoPaths.BridgeFile)) as Dictionary<string, object>, "port", 0);
            }
            catch (Exception)
            {
                return 0;
            }
        }

        /// <summary>True when the game window is the tall window this layout was made for.</summary>
        public bool Matches(int width, int height) => Math.Abs(width - ScreenW) <= 2 && Math.Abs(height - ScreenH) <= 2;

        public DuoRect Region(DuoScreen screen) => screen == DuoScreen.Top ? Top : Bottom;

        public bool SameGeometry(DuoLayout o) =>
            o != null && o.ScreenW == ScreenW && o.ScreenH == ScreenH && o.Top.Equals(Top) && o.Bottom.Equals(Bottom) &&
            o.Gap == Gap && o.BottomScale == BottomScale && o.BottomShift == BottomShift;

        public override string ToString() =>
            string.Format("screen {0}x{1}, top {2}, bottom {3}, bridge port {4}", ScreenW, ScreenH, Top, Bottom, BridgePort);

        private static Dictionary<string, object> Need(object json, string key)
        {
            var o = MiniJson.Obj(json, key);
            if (o == null)
                throw new FormatException("no \"" + key + "\" object");
            return o;
        }

        private static DuoRect Rect(Dictionary<string, object> o) =>
            new DuoRect((int)MiniJson.Num(o, "x", 0), (int)MiniJson.Num(o, "y", 0), (int)MiniJson.Num(o, "w", 0), (int)MiniJson.Num(o, "h", 0));
    }
}
