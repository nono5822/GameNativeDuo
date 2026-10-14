using System;
using System.IO;
using System.Text.Json;
using Microsoft.Xna.Framework;

namespace GameNativeDuo.StardewSplit;

/// <summary>
/// Where the top and bottom screens sit inside the game window. GameNative Duo writes this to
/// %AppData%\GameNativeDuo\layout.json at session start, e.g.
/// {"screen":{"w":1920,"h":2160},"top":{"x":0,"y":0,"w":1920,"h":1080},"bottom":{"x":340,"y":1080,"w":1240,"h":1080}}.
/// </summary>
internal sealed class Layout
{
    public Point Screen;
    public Rectangle Top;
    public Rectangle Bottom;

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

    public override string ToString() => $"screen {Screen.X}x{Screen.Y}, top {Top}, bottom {Bottom}";

    private static Rectangle ReadRect(JsonElement e) =>
        new(e.GetProperty("x").GetInt32(), e.GetProperty("y").GetInt32(), e.GetProperty("w").GetInt32(), e.GetProperty("h").GetInt32());
}
