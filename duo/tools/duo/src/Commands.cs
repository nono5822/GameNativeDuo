using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>The client commands: talk to a running sim or device over the Duo API.</summary>
public static class Commands
{
    public static Task<int> Run(string name, Args a) => name switch
    {
        "capture" => Task.FromResult(CaptureWindow(a)),
        _ => throw new DuoException("unknown command " + name + " (duo --help)"),
    };

    /// <summary>`duo capture --window TITLE [--out file.png]`: checks that window capture works on this PC.</summary>
    private static int CaptureWindow(Args a)
    {
        var img = Capture.Window(a["window"] ?? throw new DuoException("--window TITLE"));
        if (img == null)
            throw new DuoException("no window with that title could be captured");
        string path = a["out"] ?? "capture.png";
        File.WriteAllBytes(path, img);
        Console.WriteLine("wrote " + path);
        return 0;
    }

    /// <summary>One event as a terminal line.</summary>
    public static string Describe(JsonObject e)
    {
        string kind = (string?)e["kind"] ?? "";
        return kind switch
        {
            "log" => $"[{e["source"]}] {e["level"]} {e["text"]}",
            "bridge" => $"[bridge] {e["engine"]}/sdk{e["sdk"]} connected (caps 0x{(uint?)e["caps"] ?? 0:x})",
            "elements" => "[bridge] elements changed",
            "state" => "[state] " + string.Join(", ", ((JsonArray?)e["keys"])?.Select(k => (string?)k) ?? Array.Empty<string?>()),
            "check" => "[check] " + ((string?)e["text"] ?? ((bool?)e["passed"] == true ? "passed" : "failed")),
            _ => e.ToJsonString(),
        };
    }
}
