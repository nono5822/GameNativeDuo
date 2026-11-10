#nullable disable
#pragma warning disable CS0649 // a mod that embeds the SDK need not use every field
using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace GameNativeDuo.Sdk
{
    /// <summary>
    /// GameNative Duo's bridge: UDP datagrams on 127.0.0.1 between this mod and the Android app (Wine sockets are the
    /// app's own sockets, so this is a kernel loopback hop, well under a millisecond). The app's port comes from
    /// layout.json or bridge.json; this side binds any free port and announces it with a HELLO every 2 s, which also
    /// tells the app which features the mod has. Packets start with "GD", a version byte and a type byte; numbers are
    /// little-endian. Full table: duo/docs/engine-adapter-contract.md.
    ///
    /// App -> mod: 0x01 TOUCH, 0x02 RULES_CHANGED, 0x03 DUMP_ELEMENTS, 0x04 SUBSCRIBE, 0x05 COMMAND.
    /// Mod -> app: 0x81 HELLO, 0x83 ELEMENTS_WRITTEN, 0x84 STATE, 0x85 COMMAND_RESULT, 0x86 ELEMENTS_CHANGED, 0x87 LOG,
    /// 0x88 HAPTIC.
    ///
    /// The receive thread only parses and queues; the game's main thread drains it once per frame (<see cref="Drain"/>),
    /// so nothing here touches game objects.
    /// </summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    sealed class Bridge : IDisposable
    {
        public const byte Version = 1;
        public const byte Touch = 0x01, RulesChanged = 0x02, DumpElements = 0x03, Subscribe = 0x04, Command = 0x05;
        public const byte Hello = 0x81, ElementsWritten = 0x83, State = 0x84, CommandResult = 0x85, ElementsChanged = 0x86;
        public const byte Log = 0x87, Haptic = 0x88;
        public const uint CapTouch = 1, CapUiRules = 2, CapElements = 4, CapBgBasic = 8, CapBgBlur = 16, CapBgGame = 32;
        public const uint CapState = 64, CapCommands = 128, CapElementsChanged = 256;
        public const byte TouchDown = 0, TouchMove = 1, TouchUp = 2, TouchCancel = 3;
        public const byte StateFull = 1;
        public const byte ResultOk = 0, ResultRefused = 1, ResultUnknown = 2, ResultFailed = 3;
        public const byte LogDebug = 0, LogInfo = 1, LogWarn = 2, LogError = 3;

        /// <summary>The largest STATE JSON the app takes in one datagram; bigger states go in several deltas.</summary>
        public const int MaxStateBytes = 8192;

        public struct Message
        {
            public byte Type;
            public byte Action;
            public byte Pointer;
            public float X, Y;
            public uint Request;

            /// <summary>SUBSCRIBE and COMMAND: the JSON payload.</summary>
            public string Text;
        }

        private readonly Socket _socket;
        private readonly Thread _thread;
        private readonly Queue<Message> _inbox = new Queue<Message>();
        private volatile bool _running = true;
        private volatile int _appPort;

        /// <summary>Set by the receive thread; lets the main thread skip the lock when nothing arrived.</summary>
        private volatile bool _pending;

        public Bridge(int appPort)
        {
            _appPort = appPort;
            _socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            _socket.Bind(new IPEndPoint(IPAddress.Loopback, 0));
            _thread = new Thread(ReceiveLoop) { IsBackground = true, Name = "GNDuo-bridge", Priority = ThreadPriority.BelowNormal };
            _thread.Start();
        }

        public int AppPort
        {
            get { return _appPort; }
            set { _appPort = value; }
        }

        /// <summary>This side's port (tests, the simulator).</summary>
        public int LocalPort => ((IPEndPoint)_socket.LocalEndPoint).Port;

        /// <summary>Moves everything received since the last call into <paramref name="into"/>. Cheap when nothing arrived.</summary>
        public void Drain(List<Message> into)
        {
            if (!_pending)
                return;
            lock (_inbox)
            {
                while (_inbox.Count > 0)
                    into.Add(_inbox.Dequeue());
                _pending = false;
            }
        }

        /// <summary>
        /// The session token from layout.json / bridge.json, sent back in every HELLO so the app knows this is the game
        /// and not another app on the device ("" with an app that has none: the HELLO is then the old one).
        /// </summary>
        public string Token = "";

        public void SendHello(uint capabilities, string engine)
        {
            byte[] name = Encoding.ASCII.GetBytes(string.IsNullOrEmpty(Token) ? engine : engine + "\0" + Token);
            var p = new byte[8 + name.Length];
            Header(p, Hello);
            WriteU32(p, 4, capabilities);
            Buffer.BlockCopy(name, 0, p, 8, name.Length);
            Send(p);
        }

        public void SendElementsWritten(uint request)
        {
            var p = new byte[8];
            Header(p, ElementsWritten);
            WriteU32(p, 4, request);
            Send(p);
        }

        /// <summary>The element set changed (a scene change): an open editor asks for the list again.</summary>
        public void SendElementsChanged()
        {
            var p = new byte[4];
            Header(p, ElementsChanged);
            Send(p);
        }

        /// <summary>Companion state: <paramref name="json"/> is an object of changed keys (null deletes). False when too big.</summary>
        public bool SendState(string json, bool full)
        {
            byte[] body = Encoding.UTF8.GetBytes(json);
            if (body.Length > MaxStateBytes)
                return false;
            var p = new byte[8 + body.Length];
            Header(p, State);
            p[4] = full ? StateFull : (byte)0;
            Buffer.BlockCopy(body, 0, p, 8, body.Length);
            Send(p);
            return true;
        }

        public void SendCommandResult(uint request, byte status, string message)
        {
            byte[] body = Encoding.UTF8.GetBytes(Clip(message ?? "", 500));
            var p = new byte[9 + body.Length];
            Header(p, CommandResult);
            WriteU32(p, 4, request);
            p[8] = status;
            Buffer.BlockCopy(body, 0, p, 9, body.Length);
            Send(p);
        }

        public void SendLog(byte level, string text)
        {
            byte[] body = Encoding.UTF8.GetBytes(Clip(text ?? "", 500));
            var p = new byte[5 + body.Length];
            Header(p, Log);
            p[4] = level;
            Buffer.BlockCopy(body, 0, p, 5, body.Length);
            Send(p);
        }

        public void SendHaptic(int ms, int strength)
        {
            var p = new byte[7];
            Header(p, Haptic);
            int m = Math.Max(0, Math.Min(65535, ms));
            p[4] = (byte)m;
            p[5] = (byte)(m >> 8);
            p[6] = (byte)Math.Max(1, Math.Min(255, strength));
            Send(p);
        }

        private void Send(byte[] packet)
        {
            int port = _appPort;
            if (port <= 0)
                return;
            try
            {
                _socket.SendTo(packet, new IPEndPoint(IPAddress.Loopback, port));
            }
            catch (Exception)
            {
                // The app is gone or not listening yet; the next HELLO tries again.
            }
        }

        private void ReceiveLoop()
        {
            var buffer = new byte[2048];
            EndPoint from = new IPEndPoint(IPAddress.Any, 0);
            while (_running)
            {
                int n;
                try
                {
                    n = _socket.ReceiveFrom(buffer, ref from);
                }
                catch (SocketException)
                {
                    // Windows reports an earlier send to a closed port here (ICMP port unreachable); keep listening.
                    if (!_running)
                        return;
                    Thread.Sleep(50);
                    continue;
                }
                catch (ObjectDisposedException)
                {
                    return;
                }
                var ep = from as IPEndPoint;
                // Only the app's own socket may drive the game.
                if (ep == null || !IPAddress.IsLoopback(ep.Address) || ep.Port != _appPort)
                    continue;
                Message m;
                if (!Parse(buffer, n, out m))
                    continue;
                lock (_inbox)
                {
                    // A stalled game (loading screen) must not grow this without bound: drop the oldest.
                    if (_inbox.Count > 512)
                        _inbox.Dequeue();
                    _inbox.Enqueue(m);
                    _pending = true;
                }
            }
        }

        /// <summary>Parses an app -> mod packet (public for tests and the simulator).</summary>
        public static bool Parse(byte[] buffer, int n, out Message m)
        {
            m = new Message();
            if (n < 4 || buffer[0] != (byte)'G' || buffer[1] != (byte)'D' || buffer[2] != Version)
                return false;
            m.Type = buffer[3];
            switch (m.Type)
            {
                case Touch:
                    if (n < 16)
                        return false;
                    m.Action = buffer[4];
                    m.Pointer = buffer[5];
                    m.X = BitConverter.ToSingle(buffer, 8);
                    m.Y = BitConverter.ToSingle(buffer, 12);
                    return true;
                case DumpElements:
                    if (n < 8)
                        return false;
                    m.Request = BitConverter.ToUInt32(buffer, 4);
                    return true;
                case RulesChanged:
                    return true;
                case Subscribe:
                    m.Text = Encoding.UTF8.GetString(buffer, 4, n - 4);
                    return true;
                case Command:
                    if (n < 8)
                        return false;
                    m.Request = BitConverter.ToUInt32(buffer, 4);
                    m.Text = Encoding.UTF8.GetString(buffer, 8, n - 8);
                    return true;
                default:
                    return false;
            }
        }

        private static string Clip(string s, int max) => s.Length <= max ? s : s.Substring(0, max);

        private static void Header(byte[] p, byte type)
        {
            p[0] = (byte)'G';
            p[1] = (byte)'D';
            p[2] = Version;
            p[3] = type;
        }

        private static void WriteU32(byte[] p, int at, uint v)
        {
            p[at] = (byte)v;
            p[at + 1] = (byte)(v >> 8);
            p[at + 2] = (byte)(v >> 16);
            p[at + 3] = (byte)(v >> 24);
        }

        public void Dispose()
        {
            _running = false;
            try
            {
                _socket.Close();
            }
            catch (Exception)
            {
            }
        }
    }
}
