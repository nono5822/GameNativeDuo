using System.Net.Http.Headers;
using System.Text;
using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>A Duo API client: a running `duo sim` (found through its session file) or a device (--url, --code).</summary>
public sealed class DuoClient
{
    private readonly HttpClient _http;
    public readonly string Url;

    public DuoClient(string url, string code)
    {
        Url = url.TrimEnd('/');
        _http = new HttpClient { BaseAddress = new Uri(Url), Timeout = TimeSpan.FromSeconds(40) };
        _http.DefaultRequestHeaders.Add("X-Duo-Code", code);
    }

    /// <summary>--url/--code, else DUO_URL/DUO_CODE, else the last `duo sim` on this PC.</summary>
    public static DuoClient From(Args a)
    {
        string? url = a["url"] ?? Environment.GetEnvironmentVariable("DUO_URL");
        string? code = a["code"] ?? Environment.GetEnvironmentVariable("DUO_CODE");
        if ((url == null || code == null) && File.Exists(Sim.SessionFile))
        {
            var s = JsonNode.Parse(File.ReadAllText(Sim.SessionFile))!;
            url ??= (string?)s["url"];
            code ??= (string?)s["code"];
        }
        if (url == null || code == null)
            throw new DuoException("no sim running and no --url/--code (or DUO_URL/DUO_CODE) for a device");
        if (!url.StartsWith("http", StringComparison.Ordinal))
            url = "http://" + (url.Contains(':') ? url : url + ":8642");
        return new DuoClient(url, code);
    }

    public async Task<JsonObject> Get(string path)
    {
        var r = await _http.GetAsync(path);
        return await Read(r, path);
    }

    public async Task<JsonObject> Post(string path, JsonNode? body = null)
    {
        var r = await _http.PostAsync(path, new StringContent(body?.ToJsonString() ?? "{}", Encoding.UTF8, "application/json"));
        return await Read(r, path);
    }

    public async Task<byte[]> Bytes(string path)
    {
        var r = await _http.GetAsync(path);
        if (!r.IsSuccessStatusCode)
            throw new DuoException($"{path}: {(int)r.StatusCode} {await r.Content.ReadAsStringAsync()}");
        return await r.Content.ReadAsByteArrayAsync();
    }

    private static async Task<JsonObject> Read(HttpResponseMessage r, string path)
    {
        string text = await r.Content.ReadAsStringAsync();
        JsonObject? o = null;
        try
        {
            o = JsonNode.Parse(text) as JsonObject;
        }
        catch (System.Text.Json.JsonException)
        {
        }
        if ((int)r.StatusCode == 403)
            throw new DuoException((string?)o?["error"] ?? "the code was not accepted");
        if (!r.IsSuccessStatusCode && o?["error"] != null && (int)r.StatusCode != 504)
            throw new DuoException($"{path}: {o["error"]}");
        return o ?? throw new DuoException($"{path}: {(int)r.StatusCode} {text}");
    }
}
