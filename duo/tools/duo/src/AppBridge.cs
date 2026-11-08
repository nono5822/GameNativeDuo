using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>
/// The app's side of the bridge, for the simulator (the Android app's is `DuoBridge.kt`; wire format:
/// duo/docs/engine-adapter-contract.md). Binds 127.0.0.1 on a free port, learns the mod's port from its HELLO, accepts
/// everything else only from that port. Every packet in and out is recorded (decoded) for the Bridge tab.
/// </summary>
public sealed class AppBridge : IDisposable
{
    public const byte Touch = 0x01, RulesChanged = 0x02, DumpElements = 0x03, Subscribe = 0x04, Command = 0x05;
    public const byte Hello = 0x81, ElementsWritten = 0x83, State = 0x84, CommandResult = 0x85;
    public const byte ElementsChanged = 0x86, Log = 0x87, Haptic = 0x88;
    public const int CapState = 64;

    private readonly Socket _socket = new(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
    private readonly Thread _thread;
    private volatile bool _running = true;
    private readonly EventLog _events;
    private readonly LinkedList<JsonObject> _recent = new();
    private int _in, _out;
    private DateTime _rateStart = DateTime.UtcNow;
    private double _rateIn, _rateOut;

    public int Port => ((IPEndPoint)_socket.LocalEndPoint!).Port;
    public int ModPort { get; private set; }
    public uint Capabilities { get; private set; }
    public string Engine { get; private set; } = "";
    public string Sdk { get; private set; } = "";
    public DateTime LastHello { get; private set; } = DateTime.MinValue;
    public bool Connected => ModPort != 0 && DateTime.UtcNow - LastHello < TimeSpan.FromSeconds(15);

    /// <summary>Companion state the mod sent (keys to JSON values).</summary>
    public readonly Dictionary<string, JsonNode?> StateValues = new();

    public event Action<uint>? ElementsWrittenReceived;
    public event Action<uint, byte, string>? CommandResultReceived;
    public event Action? Connected_;

    private List<string> _subscription = new();

    public AppBridge(EventLog events)
    {
        _events = events;
        _socket.Bind(new IPEndPoint(IPAddress.Loopback, 0));
        _thread = new Thread(Receive) { IsBackground = true, Name = "duo-bridge" };
        _thread.Start();
    }

    // ---- app -> mod -------------------------------------------------------------------------------------------------

    public void SendTouch(byte action, byte pointer, float x, float y)
    {
        var p = Packet(Touch, 12);
        p[4] = action;
        p[5] = pointer;
        BitConverter.GetBytes(x).CopyTo(p, 8);
        BitConverter.GetBytes(y).CopyTo(p, 12);
        Send(p, $"{TouchName(action)} #{pointer} {x:0},{y:0}");
    }

    public void SendRulesChanged() => Send(Packet(RulesChanged, 0), "");

    public void SendDump(uint request)
    {
        var p = Packet(DumpElements, 4);
        BitConverter.GetBytes(request).CopyTo(p, 4);
        Send(p, $"request {request}");
    }

    public void SendSubscribe(IEnumerable<string> keys)
    {
        _subscription = keys.Distinct().OrderBy(k => k, StringComparer.Ordinal).ToList();
        var json = new JsonObject { ["keys"] = new JsonArray(_subscription.Select(k => (JsonNode)k).ToArray()) }.ToJsonString();
        var body = Encoding.UTF8.GetBytes(json);
        var p = Packet(Subscribe, body.Length);
        body.CopyTo(p, 4);
        Send(p, json);
    }

    public void SendCommand(uint request, string json)
    {
        var body = Encoding.UTF8.GetBytes(json);
        var p = Packet(Command, 4 + body.Length);
        BitConverter.GetBytes(request).CopyTo(p, 4);
        body.CopyTo(p, 8);
        Send(p, $"#{request} {json}");
    }

    private static byte[] Packet(byte type, int body)
    {
        var p = new byte[4 + body];
        p[0] = (byte)'G';
        p[1] = (byte)'D';
        p[2] = 1;
        p[3] = type;
        return p;
    }

    private void Send(byte[] p, string text)
    {
        if (ModPort == 0)
            return;
        try
        {
            _socket.SendTo(p, new IPEndPoint(IPAddress.Loopback, ModPort));
            Interlocked.Increment(ref _out);
            if (!(p[3] == Touch && p[4] == 1))
                Record("out", p[3], p.Length, text);
        }
        catch (SocketException)
        {
        }
    }

    // ---- mod -> app -------------------------------------------------------------------------------------------------

    private void Receive()
    {
        var buf = new byte[9000];
        EndPoint from = new IPEndPoint(IPAddress.Any, 0);
        while (_running)
        {
            int n;
            try
            {
                n = _socket.ReceiveFrom(buf, ref from);
            }
            catch (SocketException)
            {
                if (!_running)
                    return;
                continue;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            var ep = (IPEndPoint)from;
            if (!IPAddress.IsLoopback(ep.Address) || n < 4 || buf[0] != 'G' || buf[1] != 'D' || buf[2] != 1)
                continue;
            byte type = buf[3];
            if (type != Hello && ep.Port != ModPort)
                continue;
            Interlocked.Increment(ref _in);
            try
            {
                Handle(type, buf, n, ep.Port);
            }
            catch (Exception ex)
            {
                _events.Log("duo", "W", "bad packet 0x" + type.ToString("x2") + ": " + ex.Message);
            }
        }
    }

    private void Handle(byte type, byte[] b, int n, int port)
    {
        switch (type)
        {
            case Hello when n >= 8:
            {
                uint caps = BitConverter.ToUInt32(b, 4);
                string name = Encoding.ASCII.GetString(b, 8, n - 8);
                bool isNew = port != ModPort || caps != Capabilities || !Connected;
                ModPort = port;
                Capabilities = caps;
                Engine = name.Split("/sdk")[0];
                Sdk = name.Contains("/sdk") ? name[(name.IndexOf("/sdk", StringComparison.Ordinal) + 4)..] : "";
                LastHello = DateTime.UtcNow;
                if (isNew)
                {
                    Record("in", type, n, $"caps 0x{caps:x} {name}");
                    _events.Add("bridge", new JsonObject { ["event"] = "connected", ["engine"] = Engine, ["sdk"] = Sdk, ["caps"] = caps, ["port"] = port });
                    if ((caps & CapState) != 0 && _subscription.Count > 0)
                        SendSubscribe(_subscription);
                    Connected_?.Invoke();
                }
                break;
            }
            case ElementsWritten when n >= 8:
            {
                uint request = BitConverter.ToUInt32(b, 4);
                Record("in", type, n, $"request {request}");
                ElementsWrittenReceived?.Invoke(request);
                break;
            }
            case State when n >= 9:
            {
                string json = Encoding.UTF8.GetString(b, 8, n - 8);
                bool full = (b[4] & 1) != 0;
                var o = JsonNode.Parse(json) as JsonObject ?? new JsonObject();
                var keys = new JsonArray();
                lock (StateValues)
                {
                    if (full)
                        StateValues.Clear();
                    foreach (var pair in o)
                    {
                        if (pair.Value == null)
                            StateValues.Remove(pair.Key);
                        else
                            StateValues[pair.Key] = pair.Value.DeepClone();
                        keys.Add((JsonNode)pair.Key);
                    }
                }
                Record("in", type, n, (full ? "full " : "") + Clip(json, 200));
                _events.Add("state", new JsonObject { ["keys"] = keys, ["full"] = full });
                break;
            }
            case CommandResult when n >= 9:
            {
                uint request = BitConverter.ToUInt32(b, 4);
                string message = Encoding.UTF8.GetString(b, 9, n - 9);
                Record("in", type, n, $"#{request} status {b[8]} {message}");
                CommandResultReceived?.Invoke(request, b[8], message);
                break;
            }
            case ElementsChanged:
                Record("in", type, n, "");
                _events.Add("elements", new JsonObject { ["event"] = "changed" });
                break;
            case Log when n >= 5:
            {
                string level = b[4] switch { 0 => "D", 2 => "W", 3 => "E", _ => "I" };
                _events.Log("mod", level, Encoding.UTF8.GetString(b, 5, n - 5));
                break;
            }
            case Haptic when n >= 7:
                Record("in", type, n, $"{BitConverter.ToUInt16(b, 4)} ms, strength {b[6]}");
                break;
        }
    }

    // ---- Bridge tab -------------------------------------------------------------------------------------------------

    private void Record(string dir, byte type, int size, string text)
    {
        var e = new JsonObject
        {
            ["t"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), ["dir"] = dir, ["type"] = TypeName(type), ["size"] = size, ["text"] = text,
        };
        lock (_recent)
        {
            _recent.AddLast(e);
            while (_recent.Count > 100)
                _recent.RemoveFirst();
        }
    }

    public JsonObject Snapshot()
    {
        var now = DateTime.UtcNow;
        double secs = (now - _rateStart).TotalSeconds;
        if (secs >= 2)
        {
            _rateIn = Interlocked.Exchange(ref _in, 0) / secs;
            _rateOut = Interlocked.Exchange(ref _out, 0) / secs;
            _rateStart = now;
        }
        var recent = new JsonArray();
        lock (_recent)
        {
            foreach (var e in _recent)
                recent.Add(e.DeepClone());
        }
        return new JsonObject
        {
            ["port"] = Port,
            ["hello"] = new JsonObject
            {
                ["engine"] = Engine, ["sdk"] = Sdk, ["caps"] = Capabilities, ["port"] = ModPort, ["connected"] = Connected,
                ["agoMs"] = ModPort == 0 ? null : (long)(now - LastHello).TotalMilliseconds,
            },
            ["rates"] = new JsonObject { ["in"] = Math.Round(_rateIn, 1), ["out"] = Math.Round(_rateOut, 1) },
            ["recent"] = recent,
        };
    }

    public static string TypeName(byte t) => t switch
    {
        Touch => "TOUCH", RulesChanged => "RULES_CHANGED", DumpElements => "DUMP_ELEMENTS", Subscribe => "SUBSCRIBE",
        Command => "COMMAND", Hello => "HELLO", ElementsWritten => "ELEMENTS_WRITTEN", State => "STATE",
        CommandResult => "COMMAND_RESULT", ElementsChanged => "ELEMENTS_CHANGED", Log => "LOG", Haptic => "HAPTIC",
        _ => "0x" + t.ToString("x2"),
    };

    private static string TouchName(byte a) => a switch { 0 => "down", 1 => "move", 2 => "up", _ => "cancel" };

    private static string Clip(string s, int max) => s.Length <= max ? s : s[..max] + "...";

    public void Dispose()
    {
        _running = false;
        _socket.Close();
    }
}
