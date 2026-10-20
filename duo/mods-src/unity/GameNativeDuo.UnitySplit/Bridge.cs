using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace GameNativeDuo.UnitySplit
{
    /// <summary>
    /// GameNative Duo's bridge: UDP datagrams on 127.0.0.1 between this mod and the Android app (Wine sockets are the
    /// app's own sockets, so this is a kernel loopback hop, well under a millisecond). The app's port comes from
    /// layout.json; this side binds any free port and announces it with a HELLO every 2 s, which also tells the app
    /// which features the mod has. Packets start with "GD", a version byte and a type byte; numbers are little-endian.
    ///
    /// App -> mod: 0x01 TOUCH (action, pointer, x, y in window pixels, origin top-left), 0x02 RULES_CHANGED,
    /// 0x03 DUMP_ELEMENTS (request id).
    /// Mod -> app: 0x81 HELLO (capabilities, engine name), 0x83 ELEMENTS_WRITTEN (request id).
    ///
    /// The receive thread only parses and queues; the game's main thread drains <see cref="Inbox"/> once per frame,
    /// so nothing here touches Unity objects.
    /// </summary>
    internal sealed class Bridge : IDisposable
    {
        public const byte Version = 1;
        public const byte Touch = 0x01, RulesChanged = 0x02, DumpElements = 0x03;
        public const byte Hello = 0x81, ElementsWritten = 0x83;
        public const uint CapTouch = 1, CapUiRules = 2, CapElements = 4;
        public const byte TouchDown = 0, TouchMove = 1, TouchUp = 2, TouchCancel = 3;

        public struct Message
        {
            public byte Type;
            public byte Action;
            public byte Pointer;
            public float X, Y;
            public uint Request;
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

        public void SendHello(uint capabilities, string engine)
        {
            byte[] name = Encoding.ASCII.GetBytes(engine);
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
            var buffer = new byte[256];
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
                if (n < 4 || buffer[0] != (byte)'G' || buffer[1] != (byte)'D' || buffer[2] != Version)
                    continue;
                var m = new Message { Type = buffer[3] };
                switch (m.Type)
                {
                    case Touch:
                        if (n < 16)
                            continue;
                        m.Action = buffer[4];
                        m.Pointer = buffer[5];
                        m.X = BitConverter.ToSingle(buffer, 8);
                        m.Y = BitConverter.ToSingle(buffer, 12);
                        break;
                    case DumpElements:
                        if (n < 8)
                            continue;
                        m.Request = BitConverter.ToUInt32(buffer, 4);
                        break;
                    case RulesChanged:
                        break;
                    default:
                        continue;
                }
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
