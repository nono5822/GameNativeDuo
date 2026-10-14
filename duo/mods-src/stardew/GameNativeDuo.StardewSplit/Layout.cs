using System;
using System.IO;
using System.Text.Json;
using Microsoft.Xna.Framework;
using RectangleF = System.Drawing.RectangleF;

namespace GameNativeDuo.StardewSplit;

/// <summary>
/// Where the top and bottom screens sit inside the game window. GameNative Duo writes this to
/// %AppData%\GameNativeDuo\layout.json at session start, e.g.
/// {"screen":{"w":1920,"h":2160},"top":{"x":0,"y":0,"w":1920,"h":1080},"bottom":{"x":340,"y":1080,"w":1240,"h":1080},"gap":0,"bottomScale":1.0,"bottomShift":0}.
/// </summary>
internal sealed class Layout
{
    public Point Screen;
    public Rectangle Top;
    public Rectangle Bottom;

    /// <summary>
    /// World rows hidden between the screens, in window pixels: the physical hinge and bezels, so the world looks
    /// continuous across them instead of stretched. Optional, 0 by default.
    /// </summary>
    public int Gap;

    /// <summary>
    /// Bottom screen pixels per top screen pixel of the same physical length (pixel density ratio), so the world has
    /// the same physical size on both screens. Optional, 1 by default.
    /// </summary>
    public float BottomScale = 1f;

    /// <summary>Horizontal offset of the bottom screen's centre from the top screen's, in window pixels. Optional, 0 by default.</summary>
    public int BottomShift;

    public static string FilePath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GameNativeDuo", "layout.json");

    public static Layout? Load(out string error)
    {
        error = "";
        try
        {
            if (!File.Exists(FilePath))
            {
                error = $"{FilePath} not found";
                return null;
            }
            using JsonDocument doc = JsonDocument.Parse(File.ReadAllText(FilePath));
            JsonElement root = doc.RootElement;
            JsonElement screen = root.GetProperty("screen");
            return new Layout
            {
                Screen = new Point(screen.GetProperty("w").GetInt32(), screen.GetProperty("h").GetInt32()),
                Top = ReadRect(root.GetProperty("top")),
                Bottom = ReadRect(root.GetProperty("bottom")),
                Gap = root.TryGetProperty("gap", out JsonElement gap) ? Math.Max(0, gap.GetInt32()) : 0,
                BottomScale = root.TryGetProperty("bottomScale", out JsonElement scale) ? Math.Clamp(scale.GetSingle(), 0.5f, 2f) : 1f,
                BottomShift = root.TryGetProperty("bottomShift", out JsonElement shift) ? shift.GetInt32() : 0,
            };
        }
        catch (Exception ex)
        {
            error = $"could not read {FilePath}: {ex.Message}";
            return null;
        }
    }

    /// <summary>True when the game window is the tall window this layout was made for.</summary>
    public bool Matches(int windowWidth, int windowHeight) =>
        Math.Abs(windowWidth - Screen.X) <= 2 && Math.Abs(windowHeight - Screen.Y) <= 2;

    /// <summary>
    /// The part of the world the bottom screen shows, in window pixels: centred under the bottom screen, starting
    /// after the gap rows, and <see cref="BottomScale"/> times smaller than the screen so it is magnified to it.
    /// </summary>
    public RectangleF BottomWorld
    {
        get
        {
            float w = Bottom.Width / BottomScale, h = Bottom.Height / BottomScale;
            return new RectangleF(Bottom.X + (Bottom.Width - w) / 2f + BottomShift, Bottom.Y + Gap, w, h);
        }
    }

    /// <summary>Size of the world drawn for both screens, in window pixels.</summary>
    public Point World
    {
        get
        {
            RectangleF b = BottomWorld;
            return new Point(
                (int)Math.Ceiling(Math.Max(Math.Max(Screen.X, Top.Right), b.Right)),  // b.Left < 0 is clamped by the sampler
                (int)Math.Ceiling(Math.Max(Math.Max(Screen.Y, Top.Bottom), b.Bottom)));
        }
    }

    public override string ToString() => $"screen {Screen.X}x{Screen.Y}, top {Top}, bottom {Bottom}, gap {Gap}, bottom scale {BottomScale}, bottom shift {BottomShift}";

    private static Rectangle ReadRect(JsonElement e) =>
        new(e.GetProperty("x").GetInt32(), e.GetProperty("y").GetInt32(), e.GetProperty("w").GetInt32(), e.GetProperty("h").GetInt32());
}
