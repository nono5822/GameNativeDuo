using System.Diagnostics;
using System.Text.Json.Nodes;
using GameNativeDuo.Tool;
using Xunit;

/// <summary>`duo pack --sign`: the signature the community validator checks (ssh-keygen -Y, same namespace).</summary>
public class AuthorKeysTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("duo-keys-").FullName;

    public void Dispose() => Directory.Delete(_dir, true);

    private string Keygen(string name)
    {
        string path = Path.Combine(_dir, name);
        var info = new ProcessStartInfo("ssh-keygen") { RedirectStandardOutput = true };
        foreach (string a in new[] { "-q", "-t", "ed25519", "-N", "", "-f", path })
            info.ArgumentList.Add(a);
        Process.Start(info)!.WaitForExit();
        return path;
    }

    [Fact]
    public void SignCheckAndRefuseAnotherKey()
    {
        string key = Keygen("a"), other = Keygen("b");
        string pkg = Directory.CreateDirectory(Path.Combine(_dir, "pkg")).FullName;
        var m = new JsonObject { ["id"] = "me.mod", ["packageVersion"] = "1.0.0" };
        Assert.Equal("not signed yet (duo pack --sign)", AuthorKeys.Check(pkg, (JsonObject)m.DeepClone().Also(o => o["author"] = new JsonObject { ["key"] = "x" })));

        Assert.True(AuthorKeys.Adopt(m, key));
        File.WriteAllText(Path.Combine(pkg, "manifest.json"), m.ToJsonString());
        AuthorKeys.Sign(pkg, key);
        Assert.Null(AuthorKeys.Check(pkg, m));

        File.AppendAllText(Path.Combine(pkg, "manifest.json"), " ");
        Assert.NotNull(AuthorKeys.Check(pkg, m));
        Assert.Throws<DuoException>(() => AuthorKeys.Adopt(m, other));
    }
}

internal static class JsonTestExtensions
{
    public static JsonNode Also(this JsonNode n, Action<JsonObject> f)
    {
        f((JsonObject)n);
        return n;
    }
}
