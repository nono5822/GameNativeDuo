using System.Security.Cryptography;
using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>The client commands: talk to a running sim or device over the Duo API, plus the offline ones.</summary>
public static class Commands
{
    private static readonly System.Text.Json.JsonSerializerOptions Pretty = new() { WriteIndented = true };

    public static async Task<int> Run(string name, Args a)
    {
        switch (name)
        {
            case "capture": return CaptureWindow(a);
            case "new": return Templates.New(a);
            case "pack": return Pack(a);
            case "stubs": return Stubs.Run(a);
            case "build": return Build(a);
            case "submit": return Submit(a);
            case "dev": return await Dev(a);
            case "mcp": return await Mcp.Run(a);
        }
        var c = DuoClient.From(a);
        switch (name)
        {
            case "info":
                Console.WriteLine((await c.Get("/api/info")).ToJsonString(Pretty));
                return 0;
            case "elements":
            {
                var o = await c.Get("/api/elements");
                if (a.Flag("json"))
                {
                    Console.WriteLine(o.ToJsonString(Pretty));
                    return 0;
                }
                if (o["elements"] is not JsonArray list)
                    throw new DuoException((string?)o["error"] ?? "no element list");
                foreach (var e in list)
                {
                    string extra = (bool?)e!["present"] == false ? $"  (absent: {e["when"]})" : "";
                    Console.WriteLine($"{e["screen"],-7} {e["x"],5},{e["y"],-5} {e["w"],5}x{e["h"],-5} {e["id"]}{extra}");
                }
                return 0;
            }
            case "logs":
            {
                long since = a.Flag("f") || a.Flag("follow") ? 0 : 0;
                do
                {
                    var r = await c.Get($"/api/logs?since={since}&wait={(a.Flag("f") || a.Flag("follow") ? 20 : 0)}");
                    since = (long)r["next"]!;
                    foreach (var e in (JsonArray)r["events"]!)
                        Console.WriteLine(Describe((JsonObject)e!));
                } while (a.Flag("f") || a.Flag("follow"));
                return 0;
            }
            case "state":
            {
                string prefix = a.Positional.FirstOrDefault() ?? "";
                if (a["subscribe"] is { } keys)
                    await c.Post("/api/companion/subscribe", new JsonObject { ["keys"] = new JsonArray(keys.Split(',').Select(k => (JsonNode)k.Trim()).ToArray()) });
                Console.WriteLine((await c.Get("/api/companion/state?prefix=" + Uri.EscapeDataString(prefix)))["values"]!.ToJsonString(Pretty));
                return 0;
            }
            case "command":
            {
                if (a.Positional.Count == 0)
                    throw new DuoException("duo command NAME [JSON-ARGS]");
                var args = a.Positional.Count > 1 ? JsonNode.Parse(a.Positional[1]) : new JsonObject();
                var r = await c.Post("/api/command", new JsonObject { ["cmd"] = a.Positional[0], ["args"] = args });
                Console.WriteLine($"{r["status"]}: {r["message"]}");
                return (string?)r["status"] == "ok" ? 0 : 1;
            }
            case "tap":
            {
                if (a.Positional.Count < 2)
                    throw new DuoException("duo tap X Y   (bottom-screen pixels)");
                await c.Post("/api/touch", new JsonObject { ["x"] = double.Parse(a.Positional[0]), ["y"] = double.Parse(a.Positional[1]), ["action"] = "tap" });
                return 0;
            }
            case "screenshot":
            {
                string which = a.Positional.FirstOrDefault() ?? "bottom";
                string path = a["out"] ?? which + ".png";
                File.WriteAllBytes(path, await c.Bytes($"/api/snapshot/{which}.jpg"));
                Console.WriteLine("wrote " + path);
                return 0;
            }
            case "check":
                return await Check(c, a.Flag("json"));
            case "test":
                return await TestScript.Run(c, a.Positional.FirstOrDefault() ?? throw new DuoException("duo test SCRIPT"));
            default:
                throw new DuoException("unknown command " + name + " (duo --help)");
        }
    }

    /// <summary>Runs the conformance check and prints the report; exit code 0 only when every check passed (CI).</summary>
    public static async Task<int> Check(DuoClient c, bool json)
    {
        var start = await c.Post("/api/check");
        if ((bool?)start["started"] != true)
            throw new DuoException((string?)start["error"] ?? "the check did not start");
        string last = "";
        JsonObject status;
        while (true)
        {
            await Task.Delay(300);
            status = await c.Get("/api/check");
            string p = (string?)status["progress"] ?? "";
            if (p != last && !json)
                Console.Error.WriteLine(p);
            last = p;
            if (!(bool)status["running"]!)
                break;
        }
        var report = status["report"] as JsonObject ?? throw new DuoException("no report");
        if (json)
        {
            Console.WriteLine(report.ToJsonString(Pretty));
            return (bool?)report["passed"] == true ? 0 : 1;
        }
        if (report["error"] != null)
            throw new DuoException((string)report["error"]!);
        foreach (var r in ((JsonArray)report["results"]!).OrderBy(r => (bool)r!["passed"]!))
            Console.WriteLine($"{((bool)r!["passed"]! ? "✓" : "✗")} {r["name"]}: {r["feature"]}{(r["detail"] != null ? " (" + r["detail"] + ")" : "")}");
        foreach (var s in (JsonArray)report["skipped"]!)
            Console.WriteLine($"· {s}: not on screen, not checked");
        bool passed = (bool?)report["passed"] == true;
        Console.WriteLine(passed ? "Passed" : "Failed");
        return passed ? 0 : 1;
    }

    // ---- offline ---------------------------------------------------------------------------------------------------

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

    /// <summary>
    /// `duo pack DIR [--write]`: checks a package folder (manifest.json, community package format) before it goes to the
    /// community repo; --write fills in the files' sha256. The repo's own validator (tools/duo_index.py) has the last word.
    /// </summary>
    public static int Pack(Args a)
    {
        string dir = a.Positional.FirstOrDefault() ?? ".";
        string path = Path.Combine(dir, "manifest.json");
        if (!File.Exists(path))
            throw new DuoException("no manifest.json in " + Path.GetFullPath(dir) + " (duo new writes one)");
        var m = JsonNode.Parse(File.ReadAllText(path)) as JsonObject ?? throw new DuoException("manifest.json is not an object");
        var problems = PackProblems(m, dir, a.Flag("write"));
        if (a.Flag("write"))
            File.WriteAllText(path, m.ToJsonString(Pretty) + "\n");
        foreach (var p in problems)
            Console.WriteLine("✗ " + p);
        Console.WriteLine(problems.Count == 0 ? $"✓ {m["id"]} {m["packageVersion"]}: ready (the community repo's validator runs on the PR)" : $"{problems.Count} problem(s)");
        return problems.Count == 0 ? 0 : 1;
    }

    public static List<string> PackProblems(JsonObject m, string dir, bool write)
    {
        var problems = new List<string>();
        foreach (var k in new[] { "format", "version", "id", "kind", "packageVersion", "title", "description", "authors", "license", "files" })
        {
            if (m[k] == null)
                problems.Add("missing \"" + k + "\"");
        }
        if ((string?)m["format"] is { } f && f != "gamenative-duo/package")
            problems.Add("format must be gamenative-duo/package");
        if ((string?)m["kind"] is { } kind && !new[] { "ui-layout", "duo-settings", "device-profile", "mod", "game-profile" }.Contains(kind))
            problems.Add("unknown kind " + kind);
        if ((string?)m["id"] is { } id && !System.Text.RegularExpressions.Regex.IsMatch(id, "^[a-z0-9][a-z0-9-]*(\\.[a-z0-9][a-z0-9-]*)+$"))
            problems.Add("id must look like author.package-name (lowercase)");
        foreach (var file in m["files"] as JsonArray ?? new JsonArray())
        {
            string rel = (string?)file?["path"] ?? "";
            if (rel.Length == 0 || rel.Contains("..") || Path.IsPathRooted(rel))
            {
                problems.Add("bad file path " + rel);
                continue;
            }
            if (file!["url"] != null)
                continue;
            string full = Path.Combine(dir, rel);
            if (!File.Exists(full))
            {
                problems.Add("missing file " + rel);
                continue;
            }
            string sha = Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(full)));
            if (write)
                file["sha256"] = sha;
            else if ((string?)file["sha256"] != sha)
                problems.Add($"sha256 of {rel} does not match (duo pack --write updates it)");
        }
        return problems;
    }

    /// <summary>`duo build`: the engine's own build in the current folder.</summary>
    private static int Build(Args a)
    {
        string dir = a.Positional.FirstOrDefault() ?? ".";
        var proj = Directory.GetFiles(dir, "*.csproj").FirstOrDefault();
        if (proj != null)
            return Shell("dotnet", $"build -c Release \"{proj}\"" + (a["game"] is { } g ? $" -p:GamePath=\"{g}\" -p:ManagedPath=\"{g}\"" : ""));
        if (File.Exists(Path.Combine(dir, "Makefile")))
            return Shell("make", $"-C \"{dir}\"");
        var lua = Directory.GetFiles(dir, "*.lua");
        if (lua.Length > 0)
        {
            int bad = 0;
            foreach (var f in lua)
                bad += Shell("luajit", $"-bl \"{f}\"", quiet: true) == 0 ? 0 : 1;
            Console.WriteLine(bad == 0 ? $"✓ {lua.Length} Lua files parse" : $"{bad} Lua file(s) do not parse");
            return bad == 0 ? 0 : 1;
        }
        throw new DuoException("nothing to build here (no .csproj, Makefile or .lua)");
    }

    /// <summary>`duo dev --device ID [DIR]`: pushes a mod folder to the device's dev folder (loaded at the next session start).</summary>
    private static Task<int> Dev(Args a)
    {
        string id = a["device"] ?? throw new DuoException("duo dev --device MOD-ID [DIR]   (duo sim: build, then run the game)");
        string dir = a.Positional.FirstOrDefault() ?? ".";
        string target = $"/sdcard/Android/data/app.gamenative.duo/files/duo/mods/dev/{id}/";
        Shell("adb", $"shell mkdir -p {target}");
        int rc = Shell("adb", $"push \"{Path.GetFullPath(dir)}/.\" {target}");
        Console.WriteLine(rc == 0 ? $"pushed to {target}: restart the game (developer mode on)" : "adb failed (is the device connected? duo/agents/thor-wifi.sh)");
        return Task.FromResult(rc);
    }

    /// <summary>`duo submit`: the community repo's new-package link (a PR or the share form, after `duo pack`).</summary>
    private static int Submit(Args a)
    {
        Console.WriteLine("1. duo pack <dir> --write");
        Console.WriteLine("2. Fork https://github.com/nono5822/GameNativeDuo-community and add the folder under games/<store>-<appid>/<id>/");
        Console.WriteLine("   or: gh repo fork nono5822/GameNativeDuo-community --clone, copy, then gh pr create");
        Console.WriteLine("Layouts only: the share form at https://github.com/nono5822/GameNativeDuo-community/issues/new/choose");
        return 0;
    }

    private static int Shell(string file, string args, bool quiet = false)
    {
        try
        {
            var p = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(file, args) { RedirectStandardOutput = quiet, RedirectStandardError = quiet })!;
            if (quiet)
            {
                // Drain both pipes, or a chatty tool blocks on a full pipe forever.
                var err = p.StandardError.ReadToEndAsync();
                p.StandardOutput.ReadToEnd();
                err.Wait();
            }
            p.WaitForExit();
            return p.ExitCode;
        }
        catch (System.ComponentModel.Win32Exception)
        {
            throw new DuoException(file + " not found");
        }
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
