using System;
using System.IO;
using System.Text.RegularExpressions;
using UnityEngine;

namespace GameNativeDuo.UnitySplit
{
    /// <summary>
    /// Where the top and bottom screens sit inside the game window, from GameNative Duo's
    /// %AppData%\GameNativeDuo\layout.json, e.g.
    /// {"screen":{"w":1920,"h":2160},"top":{"x":0,"y":0,"w":1920,"h":1080},"bottom":{"x":340,"y":1080,"w":1240,"h":1080},...}.
    /// Rectangles are in window pixels with the origin at the top-left. Parsed by hand: old Unity Mono runtimes have
    /// no JSON library.
    /// </summary>
    internal sealed class Layout
    {
        public int ScreenW, ScreenH;
        public RectInt Top, Bottom;

        public static string FilePath =>
            Path.Combine(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GameNativeDuo"), "layout.json");

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
                string json = File.ReadAllText(FilePath);
                var screen = Object(json, "screen");
                return new Layout
                {
                    ScreenW = Int(screen, "w"),
                    ScreenH = Int(screen, "h"),
                    Top = Rect(Object(json, "top")),
                    Bottom = Rect(Object(json, "bottom")),
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

        public override string ToString() =>
            string.Format("screen {0}x{1}, top {2}, bottom {3}", ScreenW, ScreenH, Top, Bottom);

        private static string Object(string json, string key)
        {
            Match m = Regex.Match(json, "\"" + key + "\"\\s*:\\s*\\{([^}]*)\\}");
            if (!m.Success)
                throw new FormatException("no \"" + key + "\" object");
            return m.Groups[1].Value;
        }

        private static int Int(string obj, string key)
        {
            Match m = Regex.Match(obj, "\"" + key + "\"\\s*:\\s*(-?\\d+)");
            if (!m.Success)
                throw new FormatException("no \"" + key + "\" number");
            return int.Parse(m.Groups[1].Value);
        }

        private static RectInt Rect(string obj) => new RectInt(Int(obj, "x"), Int(obj, "y"), Int(obj, "w"), Int(obj, "h"));
    }
}
