using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>
/// A dual-screen device as the sim plays it: the two displays' pixels and the physical calibration the app writes
/// into layout.json (the app's `DeviceProfile` + `ScreenSplit.native`).
/// </summary>
public sealed record DeviceProfile(string Id, string Name, int TopW, int TopH, int BottomW, int BottomH, int Gap, double BottomScale, int BottomShift)
{
    public static readonly DeviceProfile Thor = new("thor", "AYN Thor", 1920, 1080, 1240, 1080, 249, 1.142, 0);

    public static readonly DeviceProfile[] All = { Thor };

    public static DeviceProfile? Find(string id) => All.FirstOrDefault(p => p.Id.Equals(id, StringComparison.OrdinalIgnoreCase));

    /// <summary>
    /// layout.json for this device at split <paramref name="scale"/> (1, 0.75, 0.5): the top display stacked on the
    /// bottom one, the bottom part centred; sizes rounded to even numbers below 100% as the app does.
    /// </summary>
    public JsonObject Layout(double scale, int bridgePort)
    {
        int S(int v) => scale >= 1 ? v : (int)Math.Round(v * scale) / 2 * 2;
        int tw = S(TopW), th = S(TopH), bw = S(BottomW), bh = S(BottomH);
        int w = Math.Max(tw, bw);
        var o = new JsonObject
        {
            ["screen"] = new JsonObject { ["w"] = w, ["h"] = th + bh },
            ["top"] = Rect((w - tw) / 2, 0, tw, th),
            ["bottom"] = Rect((w - bw) / 2, th, bw, bh),
            ["gap"] = (int)Math.Round(Gap * Math.Min(1, scale)),
            ["bottomScale"] = BottomScale,
            ["bottomShift"] = BottomShift,
        };
        if (bridgePort > 0)
            o["bridge"] = new JsonObject { ["port"] = bridgePort };
        return o;
    }

    private static JsonObject Rect(int x, int y, int w, int h) => new() { ["x"] = x, ["y"] = y, ["w"] = w, ["h"] = h };
}
