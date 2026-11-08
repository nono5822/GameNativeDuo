using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>The client commands: talk to a running sim or device over the Duo API.</summary>
public static class Commands
{
    public static Task<int> Run(string name, Args a) => throw new DuoException("unknown command " + name + " (duo --help)");

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
