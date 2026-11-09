using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>`duo sim`: the simulator and its server until Ctrl+C.</summary>
public static class Sim
{
    /// <summary>Where `duo sim` records its URL and token, so other `duo` commands find it.</summary>
    public static string SessionFile => Path.Combine(Path.GetTempPath(), "gamenative-duo-sim.json");

    public static async Task<int> Run(Args a)
    {
        var profile = DeviceProfile.Find(a["profile"] ?? "thor") ?? throw new DuoException("unknown profile " + a["profile"]);
        double scale = double.TryParse(a["scale"], System.Globalization.CultureInfo.InvariantCulture, out var s) ? s : 1;
        string dir = a["dir"] ?? DefaultDir(a["prefix"]);
        int port = int.TryParse(a["port"], out var p) ? p : 8642;
        using var sim = new SimHost(dir, profile, scale, a["game"] ?? "");
        using var server = new ApiServer(sim, port);
        server.Check = new ConformanceRunner(sim);
        server.Snapshot = which => Capture.Screen(sim, which, a["window"]);
        server.Start();
        File.WriteAllText(SessionFile, new JsonObject { ["url"] = $"http://127.0.0.1:{port}", ["code"] = server.Token }.ToJsonString());
        Console.WriteLine($"duo sim: {profile.Name} at {scale * 100:0}%: window {sim.ScreenW}x{sim.ScreenH}");
        Console.WriteLine($"  files:  {dir}");
        Console.WriteLine($"  bridge: 127.0.0.1:{sim.Bridge.Port}");
        Console.WriteLine($"  editor: {server.Url}");
        Console.WriteLine($"Run the game windowed at {sim.ScreenW}x{sim.ScreenH}. Ctrl+C stops.");
        var done = new TaskCompletionSource();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            done.TrySetResult();
        };
        using var term = System.Runtime.InteropServices.PosixSignalRegistration.Create(System.Runtime.InteropServices.PosixSignal.SIGTERM, e =>
        {
            e.Cancel = true;
            done.TrySetResult();
        });
        long seen = 0;
        while (!done.Task.IsCompleted)
        {
            var (next, events) = sim.Events.Since(seen, TimeSpan.FromMilliseconds(500));
            seen = next;
            foreach (var e in events)
                Console.WriteLine(Commands.Describe((JsonObject)e!));
        }
        try
        {
            File.Delete(SessionFile);
        }
        catch (IOException)
        {
        }
        return 0;
    }

    /// <summary>%AppData%\GameNativeDuo on Windows; inside a Wine/Proton prefix with --prefix; else ./GameNativeDuo.</summary>
    public static string DefaultDir(string? prefix)
    {
        if (prefix != null)
        {
            foreach (var user in new[] { "steamuser", Environment.UserName })
            {
                string d = Path.Combine(prefix, "drive_c", "users", user, "AppData", "Roaming");
                if (Directory.Exists(d))
                    return Path.Combine(d, "GameNativeDuo");
            }
            throw new DuoException("no drive_c/users/<user>/AppData/Roaming in " + prefix);
        }
        if (OperatingSystem.IsWindows())
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GameNativeDuo");
        return Path.Combine(Directory.GetCurrentDirectory(), "GameNativeDuo");
    }
}
