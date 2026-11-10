using System.Net.Http.Headers;
using System.Text;
using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>A Duo API client: a running `duo sim` (found through its session file) or a device (--url, --code).</summary>
public sealed class DuoClient
{
    private readonly HttpClient _http;
    private readonly string _code;
    private bool _paired;
    public readonly string Url;

    public DuoClient(string url, string code)
    {
        Url = url.TrimEnd('/');
        _code = code;
        _http = new HttpClient { BaseAddress = new Uri(Url), Timeout = TimeSpan.FromSeconds(40) };
    }

    /// <summary>
    /// Pairs once (duo-api.md, "Pairing"): the code goes to POST /api/pair, a device then waits for Allow on its bottom
    /// screen, and the session token it answers with is sent as X-Duo-Token from then on. A server from before
    /// pairing (404) still gets the code as a header.
    /// </summary>
    private async Task Pair()
    {
        if (_paired)
            return;
        bool told = false;
        for (int i = 0; i < 90; i++)
        {
            var r = await _http.PostAsync("/api/pair", new StringContent(new JsonObject { ["code"] = _code }.ToJsonString(), Encoding.UTF8, "application/json"));
            string text = await r.Content.ReadAsStringAsync();
            JsonObject? o = null;
            try
            {
                o = JsonNode.Parse(text) as JsonObject;
            }
            catch (System.Text.Json.JsonException)
            {
            }
            switch ((int)r.StatusCode)
            {
                case 200 when o != null && o["token"] != null:
                    _http.DefaultRequestHeaders.Add("X-Duo-Token", (string)o["token"]!);
                    _paired = true;
                    return;
                case 202:
                    if (!told)
                        Console.Error.WriteLine("Tap Allow on the device's bottom screen...");
                    told = true;
                    await Task.Delay(1000);
                    continue;
                case 404:
                    _http.DefaultRequestHeaders.Add("X-Duo-Code", _code);
                    _paired = true;
                    return;
                case 429:
                    throw new DuoException("too many wrong codes from this computer: the device asks you to wait");
                default:
                    throw new DuoException((string?)o?["error"] ?? "the code was not accepted");
            }
        }
        throw new DuoException("nobody tapped Allow on the device");
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
        await Pair();
        var r = await _http.GetAsync(path);
        return await Read(r, path);
    }

    public async Task<JsonObject> Post(string path, JsonNode? body = null)
    {
        await Pair();
        var r = await _http.PostAsync(path, new StringContent(body?.ToJsonString() ?? "{}", Encoding.UTF8, "application/json"));
        return await Read(r, path);
    }

    public async Task<byte[]> Bytes(string path)
    {
        await Pair();
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
