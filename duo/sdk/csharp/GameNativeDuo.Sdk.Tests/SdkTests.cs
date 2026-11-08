using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using GameNativeDuo.Sdk;
using Xunit;

public class PacketTests
{
    [Fact]
    public void TouchAndCommandParse()
    {
        var p = new byte[16];
        p[0] = (byte)'G'; p[1] = (byte)'D'; p[2] = 1; p[3] = Bridge.Touch; p[4] = Bridge.TouchMove; p[5] = 2;
        BitConverter.GetBytes(12.5f).CopyTo(p, 8);
        BitConverter.GetBytes(1300f).CopyTo(p, 12);
        Assert.True(Bridge.Parse(p, p.Length, out var m));
        Assert.Equal(Bridge.TouchMove, m.Action);
        Assert.Equal(2, m.Pointer);
        Assert.Equal(1300f, m.Y);
        Assert.False(Bridge.Parse(p, 12, out m));

        var json = Encoding.UTF8.GetBytes("{\"cmd\":\"x\"}");
        var c = new byte[8 + json.Length];
        c[0] = (byte)'G'; c[1] = (byte)'D'; c[2] = 1; c[3] = Bridge.Command;
        BitConverter.GetBytes(7u).CopyTo(c, 4);
        json.CopyTo(c, 8);
        Assert.True(Bridge.Parse(c, c.Length, out m));
        Assert.Equal(7u, m.Request);
        Assert.Equal("{\"cmd\":\"x\"}", m.Text);
    }
}

public class StateTests
{
    [Fact]
    public void SubscriptionMatchesKeysAndChildren()
    {
        Assert.True(StatePublisher.Matches("hk.charms", "hk.charms"));
        Assert.True(StatePublisher.Matches("hk.charms", "hk.charms.0"));
        Assert.True(StatePublisher.Matches("hk.map.", "hk.map.zone"));
        Assert.False(StatePublisher.Matches("hk.charm", "hk.charms"));
    }

    [Fact]
    public void ChunksStayUnderTheDatagramLimit()
    {
        var values = new List<KeyValuePair<string, object>>();
        for (int i = 0; i < 400; i++)
            values.Add(new KeyValuePair<string, object>("k.item" + i, new string('é', 40)));
        var chunks = StatePublisher.Chunks(values, Bridge.MaxStateBytes);
        Assert.True(chunks.Count > 1);
        int keys = 0;
        foreach (var c in chunks)
        {
            Assert.True(Encoding.UTF8.GetByteCount(c) <= Bridge.MaxStateBytes);
            keys += ((Dictionary<string, object>)MiniJson.Parse(c)).Count;
        }
        Assert.Equal(400, keys);
    }

    [Fact]
    public void WritesValuesAsJson()
    {
        var d = new Dictionary<string, object> { { "a", 1 }, { "b", new List<object> { "x", true, null, 2.5 } } };
        Assert.Equal("{\"a\":1,\"b\":[\"x\",true,null,2.5]}", MiniJson.Write(d));
    }
}

public class ElementsTests
{
    [Fact]
    public void FeaturesOnlyWhenRestricted()
    {
        var all = new ElementInfo { Id = "a", Name = "A", Rect = new DuoRect(1, 2, 3, 4) };
        var limited = new ElementInfo
        {
            Id = "b", Name = "B", Present = false, When = "During a run",
            Features = new ElementFeatures { Screens = new[] { DuoScreen.Bottom, DuoScreen.Top }, Move = false, ScaleMin = 0.7f, ScaleMax = 1.2f },
        };
        string json = ElementsFile.Build("test", 1920, 2160, new[] { all, limited });
        var root = (Dictionary<string, object>)MiniJson.Parse(json);
        var list = (List<object>)root["elements"];
        var a = (Dictionary<string, object>)list[0];
        var b = (Dictionary<string, object>)list[1];
        Assert.False(a.ContainsKey("features"));
        Assert.Equal(false, b["present"]);
        var f = (Dictionary<string, object>)b["features"];
        Assert.Equal(false, f["move"]);
        Assert.Equal(1.2, MiniJson.Num((Dictionary<string, object>)f["scale"], "max", 0));
    }
}

/// <summary>The SDK against a fake app on loopback: hello, element dump, commands, state.</summary>
public class FakeAppTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "duo-sdk-test-" + Guid.NewGuid().ToString("N"));
    private readonly Socket _app = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);

    public FakeAppTests()
    {
        Directory.CreateDirectory(_dir);
        DuoPaths.Dir = _dir;
        _app.Bind(new IPEndPoint(IPAddress.Loopback, 0));
        _app.ReceiveTimeout = 200;
        int port = ((IPEndPoint)_app.LocalEndPoint).Port;
        File.WriteAllText(DuoPaths.LayoutFile,
            "{\"screen\":{\"w\":1920,\"h\":2160},\"top\":{\"x\":0,\"y\":0,\"w\":1920,\"h\":1080},\"bottom\":{\"x\":340,\"y\":1080,\"w\":1240,\"h\":1080},\"bridge\":{\"port\":" + port + "}}");
    }

    public void Dispose()
    {
        Duo.Stop();
        _app.Close();
        Directory.Delete(_dir, true);
    }

    private EndPoint _mod;

    private byte[] Receive(byte type)
    {
        var buf = new byte[9000];
        var deadline = DateTime.UtcNow.AddSeconds(3);
        while (DateTime.UtcNow < deadline)
        {
            Duo.Tick(1920, 2160);
            try
            {
                EndPoint from = new IPEndPoint(IPAddress.Any, 0);
                int n = _app.ReceiveFrom(buf, ref from);
                _mod = from;
                if (buf[3] == type)
                {
                    var r = new byte[n];
                    Array.Copy(buf, r, n);
                    return r;
                }
            }
            catch (SocketException)
            {
            }
        }
        throw new TimeoutException("no packet 0x" + type.ToString("x2"));
    }

    private void Send(byte type, byte[] body)
    {
        var p = new byte[4 + body.Length];
        p[0] = (byte)'G'; p[1] = (byte)'D'; p[2] = 1; p[3] = type;
        body.CopyTo(p, 4);
        _app.SendTo(p, _mod);
    }

    [Fact]
    public void FullRoundTrip()
    {
        DuoRect? score = new DuoRect(400, 1100, 200, 50);
        Duo.Start("test-game");
        Duo.Elements.Register("Score", "Score", () => score, new ElementFeatures { Scale = false });
        Duo.OnCommand("test.ping", args => MiniJson.Str(args, "who", "") == "me" ? CommandResult.Ok("pong") : CommandResult.NotNow("not you"));
        Duo.Publish("test.hp", 5);

        var hello = Receive(Bridge.Hello);
        uint caps = BitConverter.ToUInt32(hello, 4);
        Assert.Equal("test-game/sdk" + Duo.SdkVersion, Encoding.ASCII.GetString(hello, 8, hello.Length - 8));
        Assert.NotEqual(0u, caps & Bridge.CapState);
        Assert.NotEqual(0u, caps & Bridge.CapCommands);
        Assert.True(File.Exists(DuoPaths.FeaturesFile));

        Send(Bridge.DumpElements, BitConverter.GetBytes(42u));
        var written = Receive(Bridge.ElementsWritten);
        Assert.Equal(42u, BitConverter.ToUInt32(written, 4));
        Assert.Contains("\"id\":\"Score\"", File.ReadAllText(DuoPaths.ElementsFile));

        var cmd = Encoding.UTF8.GetBytes("{\"cmd\":\"test.ping\",\"args\":{\"who\":\"me\"}}");
        var body = new byte[4 + cmd.Length];
        BitConverter.GetBytes(9u).CopyTo(body, 0);
        cmd.CopyTo(body, 4);
        Send(Bridge.Command, body);
        var result = Receive(Bridge.CommandResult);
        Assert.Equal(9u, BitConverter.ToUInt32(result, 4));
        Assert.Equal(Bridge.ResultOk, result[8]);
        Assert.Equal("pong", Encoding.UTF8.GetString(result, 9, result.Length - 9));

        Send(Bridge.Subscribe, Encoding.UTF8.GetBytes("{\"keys\":[\"test.hp\"]}"));
        var state = Receive(Bridge.State);
        Assert.Equal(Bridge.StateFull, state[4]);
        Assert.Equal("{\"test.hp\":5}", Encoding.UTF8.GetString(state, 8, state.Length - 8));
    }
}
