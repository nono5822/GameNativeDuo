using System.Diagnostics;
using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>
/// Author keys for community packages (devkit-platform.md, "Author keys"): an SSH ed25519 key signs manifest.json into
/// manifest.json.sig through `ssh-keygen -Y` (namespace gamenative-duo-package), exactly what the community validator
/// checks (tools/duo_index.py). No crypto of our own: OpenSSH ships with Windows 10+, macOS and Linux.
/// </summary>
public static class AuthorKeys
{
    public const string SigFile = "manifest.json.sig";
    public const string Namespace = "gamenative-duo-package";

    /// <summary>~/.config/gamenative-duo/author_ed25519 (%APPDATA%\gamenative-duo on Windows).</summary>
    public static string DefaultKey => Path.Combine(
        OperatingSystem.IsWindows()
            ? Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData)
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".config"),
        "gamenative-duo", "author_ed25519");

    /// <summary>`duo keygen [--out PATH]`: a new author key (ssh-keygen asks for an optional passphrase).</summary>
    public static int Keygen(Args a)
    {
        string path = a["out"] ?? DefaultKey;
        if (File.Exists(path))
            throw new DuoException($"{path} exists already: keep using it (losing it means users are asked again)");
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        if (Run("ssh-keygen", new[] { "-t", "ed25519", "-C", "gamenative-duo author", "-f", path }, null, interactive: true).code != 0)
            throw new DuoException("ssh-keygen failed");
        Console.WriteLine($"Author key: {path}\nPublic key (author.key): {PublicKeyLine(path + ".pub")}\nBack up the private key: an update signed by another key makes every user agree again.");
        return 0;
    }

    /// <summary>"ssh-ed25519 AAAA..." from a .pub file, without its comment.</summary>
    public static string PublicKeyLine(string pubFile)
    {
        if (!File.Exists(pubFile))
            throw new DuoException($"{pubFile} not found (duo keygen makes a key)");
        var parts = File.ReadAllText(pubFile).Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length < 2 || parts[0] != "ssh-ed25519")
            throw new DuoException($"{pubFile}: not an ssh-ed25519 key (make one with duo keygen)");
        return parts[0] + " " + parts[1];
    }

    /// <summary>
    /// Sets author.key in <paramref name="m"/> when absent (true: the manifest must be written before signing); refuses
    /// a different key: that needs a handover from the current one.
    /// </summary>
    public static bool Adopt(JsonObject m, string keyFile)
    {
        string key = PublicKeyLine(keyFile + ".pub");
        if (m["author"] is not JsonObject author)
        {
            m["author"] = new JsonObject { ["key"] = key };
            return true;
        }
        if ((string?)author["key"] != key)
            throw new DuoException($"{keyFile} is not this package's author.key: sign with that key, or hand the package over first (tools/duo_index.py handover in the community repo)");
        return false;
    }

    /// <summary>Writes manifest.json.sig for the manifest as it is on disk now.</summary>
    public static void Sign(string dir, string keyFile)
    {
        string manifest = Path.Combine(dir, "manifest.json");
        string sig = manifest + ".sig";
        File.Delete(sig);
        if (Run("ssh-keygen", new[] { "-Y", "sign", "-q", "-f", keyFile, "-n", Namespace, manifest }, null, interactive: true).code != 0)
            throw new DuoException("ssh-keygen could not sign (wrong passphrase?)");
    }

    /// <summary>Null when the signature matches the manifest and its author.key, else why not.</summary>
    public static string? Check(string dir, JsonObject m)
    {
        string? key = (string?)m["author"]?["key"];
        string sig = Path.Combine(dir, SigFile);
        if (key == null)
            return File.Exists(sig) ? $"{SigFile} without author.key" : null;
        if (!File.Exists(sig))
            return "not signed yet (duo pack --sign)";
        string allowed = Path.GetTempFileName();
        try
        {
            File.WriteAllText(allowed, "author " + key + "\n");
            var (code, _) = Run("ssh-keygen", new[] { "-Y", "verify", "-f", allowed, "-I", "author", "-n", Namespace, "-s", sig },
                File.ReadAllBytes(Path.Combine(dir, "manifest.json")));
            return code == 0 ? null : "the signature does not match manifest.json (sign again after every change: duo pack --sign)";
        }
        finally
        {
            File.Delete(allowed);
        }
    }

    private static (int code, string output) Run(string file, string[] args, byte[]? stdin, bool interactive = false)
    {
        var info = new ProcessStartInfo(file) { RedirectStandardInput = stdin != null, RedirectStandardOutput = !interactive, RedirectStandardError = !interactive };
        foreach (string arg in args)
            info.ArgumentList.Add(arg);
        try
        {
            using var p = Process.Start(info)!;
            Task<string>? output = interactive ? null : p.StandardOutput.ReadToEndAsync();
            Task<string>? errors = interactive ? null : p.StandardError.ReadToEndAsync();
            if (stdin != null)
            {
                p.StandardInput.BaseStream.Write(stdin);
                p.StandardInput.Close();
            }
            p.WaitForExit();
            return (p.ExitCode, (output?.Result ?? "") + (errors?.Result ?? ""));
        }
        catch (System.ComponentModel.Win32Exception)
        {
            throw new DuoException($"{file} not found: install OpenSSH (Windows: Settings, Optional features, OpenSSH Client)");
        }
    }
}
