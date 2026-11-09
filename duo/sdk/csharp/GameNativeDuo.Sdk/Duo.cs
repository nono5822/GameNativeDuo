#nullable disable
#pragma warning disable CS0649 // a mod that embeds the SDK need not use every field
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;

namespace GameNativeDuo.Sdk
{
    /// <summary>A bottom-screen finger, in window pixels (origin top-left), already on the game thread.</summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    struct DuoTouch
    {
        /// <summary><see cref="Bridge.TouchDown"/>, Move, Up or Cancel.</summary>
        public byte Action;

        public int Pointer;
        public float X, Y;
    }

    /// <summary>What a command handler answers: done, not now (with a reason the user sees), or failed.</summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    struct CommandResult
    {
        public byte Status;
        public string Message;

        public static CommandResult Ok(string message = "") => new CommandResult { Status = Bridge.ResultOk, Message = message };

        public static CommandResult NotNow(string reason) => new CommandResult { Status = Bridge.ResultRefused, Message = reason };

        public static CommandResult Failed(string message) => new CommandResult { Status = Bridge.ResultFailed, Message = message };
    }

    /// <summary>
    /// The Duo SDK in one place (duo/docs/devkit-platform.md, "SDKs"). A mod calls <see cref="Start"/> once and
    /// <see cref="Tick"/> once per frame on the game thread; everything else is callbacks on that same thread:
    /// <code>
    /// Duo.Start("love-mygame");
    /// Duo.Elements.Register("Score", "Score", () => scoreRect);
    /// Duo.OnTouch(t => ...);
    /// // every frame: Duo.Tick(windowWidth, windowHeight);
    /// </code>
    /// Costs nothing measurable: one HELLO every 2 s, two file-time checks a second, no allocations per frame while
    /// nothing arrives.
    /// </summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    static class Duo
    {
        public const string SdkVersion = "1.0";

        private static Bridge _bridge;
        private static string _engine = "";
        private static readonly Stopwatch Clock = new Stopwatch();
        private static long _nextHelloMs, _nextFileCheckMs;
        private static DateTime _layoutTime, _uiTime;
        private static readonly List<Bridge.Message> Inbox = new List<Bridge.Message>();
        private static readonly List<Action<DuoTouch>> TouchHandlers = new List<Action<DuoTouch>>();
        private static readonly Dictionary<string, Func<Dictionary<string, object>, CommandResult>> Commands =
            new Dictionary<string, Func<Dictionary<string, object>, CommandResult>>();
        private static readonly StatePublisher State = new StatePublisher();
        private static bool _published, _featuresWritten;
        private static int _windowW, _windowH;
        private static string _lastError;

        /// <summary>Extra HELLO capabilities the mod implements itself (backgrounds: <see cref="Bridge.CapBgBasic"/>...).</summary>
        public static uint ExtraCapabilities;

        /// <summary>Background modes for features.json (the mod draws them), besides "game".</summary>
        public static string[] Backgrounds = { "game" };

        /// <summary>Where the screens are, or null before GameNative Duo wrote layout.json.</summary>
        public static DuoLayout Layout { get; private set; }

        /// <summary>The user's UI layout (rules and background); never null.</summary>
        public static UiFile Ui { get; private set; } = UiFile.Empty;

        /// <summary>Goes up every time ui.json is read again: re-read rules when it changes.</summary>
        public static int RulesVersion { get; private set; }

        /// <summary>The window is the tall window the layout was made for (the last size given to <see cref="Tick"/>).</summary>
        public static bool IsSplit => Layout != null && Layout.Matches(_windowW, _windowH);

        public static event Action<DuoLayout> LayoutChanged;
        public static event Action RulesChanged;

        /// <summary>Where errors and the SDK's own messages go besides the app (e.g. BepInEx's logger).</summary>
        public static Action<string> LocalLog;

        public static bool Started => _bridge != null;

        /// <summary>Opens the bridge. <paramref name="engine"/>: a short id like "love-mygame" (the SDK version is appended).</summary>
        public static void Start(string engine)
        {
            if (_bridge != null)
                return;
            _engine = engine;
            Clock.Start();
            try
            {
                _bridge = new Bridge(0);
                ReadFiles(true);
            }
            catch (Exception ex)
            {
                Report("start failed: " + ex.Message);
            }
        }

        public static void Stop()
        {
            _bridge?.Dispose();
            _bridge = null;
        }

        /// <summary>Call once per frame on the game thread with the window's size.</summary>
        public static void Tick(int windowWidth, int windowHeight)
        {
            if (_bridge == null)
                return;
            _windowW = windowWidth;
            _windowH = windowHeight;
            try
            {
                long now = Clock.ElapsedMilliseconds;
                if (now >= _nextFileCheckMs)
                {
                    _nextFileCheckMs = now + 500;
                    ReadFiles(false);
                }
                if (now >= _nextHelloMs && _bridge.AppPort > 0)
                {
                    _nextHelloMs = now + 2000;
                    _bridge.SendHello(Capabilities(), _engine + "/sdk" + SdkVersion);
                    if (!_featuresWritten)
                        WriteFeatures();
                }
                Inbox.Clear();
                _bridge.Drain(Inbox);
                foreach (var m in Inbox)
                    Handle(m);
                State.Flush(_bridge, DateTime.UtcNow);
            }
            catch (Exception ex)
            {
                Report("tick failed: " + ex.Message);
            }
        }

        public static void OnTouch(Action<DuoTouch> handler)
        {
            if (handler != null)
                TouchHandlers.Add(handler);
        }

        /// <summary>The user's rule for element <paramref name="id"/>, or null (the mod's default).</summary>
        public static UiRule Rule(string id)
        {
            UiRule r;
            return id != null && Ui.Rules.TryGetValue(id, out r) ? r : null;
        }

        /// <summary>Companion state: sent only while a visible card reads it.</summary>
        public static void Publish(string key, object value)
        {
            _published = true;
            State.Publish(key, value);
        }

        /// <summary>True when a visible companion card reads <paramref name="key"/>: compute costly values only then.</summary>
        public static bool Wants(string key) => State.Wants(key);

        /// <summary>A command companion buttons and hotkeys can send. Runs on the game thread; go through the game's own UI paths.</summary>
        public static void OnCommand(string name, Func<Dictionary<string, object>, CommandResult> handler)
        {
            if (!string.IsNullOrEmpty(name) && handler != null)
                Commands[name] = handler;
        }

        public static void Log(string text) => Log(Bridge.LogInfo, text);

        public static void Log(byte level, string text)
        {
            _bridge?.SendLog(level, text);
            LocalLog?.Invoke(text);
        }

        public static void Haptic(int ms = 20, int strength = 128) => _bridge?.SendHaptic(ms, strength);

        /// <summary>The mod's own elements, listed in the editors.</summary>
        public static class Elements
        {
            internal sealed class Entry
            {
                public string Id, Name, When;
                public Func<DuoRect?> Rect;
                public ElementFeatures Features;
                public DuoScreen DefaultScreen;
                public ElementOption[] Options;
            }

            internal static readonly List<Entry> All = new List<Entry>();

            /// <summary>
            /// Lists an element. <paramref name="rect"/> returns where it is now in window pixels (rules applied), or
            /// null while it is not on screen (then <paramref name="when"/> says when it shows).
            /// </summary>
            public static void Register(
                string id,
                string name,
                Func<DuoRect?> rect,
                ElementFeatures features = null,
                DuoScreen defaultScreen = DuoScreen.Bottom,
                string when = null,
                params ElementOption[] options)
            {
                All.RemoveAll(e => e.Id == id);
                All.Add(new Entry { Id = id, Name = name, Rect = rect, Features = features, DefaultScreen = defaultScreen, When = when, Options = options });
                _featuresWritten = false;
                Changed();
            }

            /// <summary>Tells an open editor the element set changed (a scene change).</summary>
            public static void Changed() => _bridge?.SendElementsChanged();

            /// <summary>The screen the user chose for <paramref name="id"/>, or its default.</summary>
            public static DuoScreen ScreenOf(string id)
            {
                var rule = Rule(id);
                if (rule != null && rule.Screen != DuoScreen.Default)
                    return rule.Screen;
                foreach (var e in All)
                {
                    if (e.Id == id)
                        return e.DefaultScreen;
                }
                return DuoScreen.Bottom;
            }

            internal static List<ElementInfo> Snapshot()
            {
                var list = new List<ElementInfo>(All.Count);
                foreach (var e in All)
                {
                    DuoRect? r = null;
                    try
                    {
                        r = e.Rect?.Invoke();
                    }
                    catch (Exception)
                    {
                    }
                    list.Add(new ElementInfo
                    {
                        Id = e.Id,
                        Name = e.Name,
                        Screen = ScreenOf(e.Id),
                        Rect = r ?? default(DuoRect),
                        Present = r.HasValue,
                        When = e.When,
                        Features = e.Features,
                        Options = e.Options,
                    });
                }
                return list;
            }
        }

        private static uint Capabilities()
        {
            uint caps = Bridge.CapUiRules | Bridge.CapElements | Bridge.CapElementsChanged | ExtraCapabilities;
            if (TouchHandlers.Count > 0)
                caps |= Bridge.CapTouch;
            if (_published)
                caps |= Bridge.CapState;
            if (Commands.Count > 0)
                caps |= Bridge.CapCommands;
            return caps;
        }

        private static void Handle(Bridge.Message m)
        {
            switch (m.Type)
            {
                case Bridge.Touch:
                    var t = new DuoTouch { Action = m.Action, Pointer = m.Pointer, X = m.X, Y = m.Y };
                    foreach (var h in TouchHandlers)
                        h(t);
                    break;
                case Bridge.RulesChanged:
                    ReadUi();
                    break;
                case Bridge.DumpElements:
                    WriteElements();
                    _bridge.SendElementsWritten(m.Request);
                    break;
                case Bridge.Subscribe:
                    State.OnSubscribe(m.Text);
                    break;
                case Bridge.Command:
                    RunCommand(m.Request, m.Text);
                    break;
            }
        }

        private static void RunCommand(uint request, string json)
        {
            string name = "";
            try
            {
                var o = MiniJson.Parse(json) as Dictionary<string, object>;
                name = MiniJson.Str(o, "cmd", "");
                Func<Dictionary<string, object>, CommandResult> handler;
                if (!Commands.TryGetValue(name, out handler))
                {
                    _bridge.SendCommandResult(request, Bridge.ResultUnknown, "unknown command " + name);
                    return;
                }
                var r = handler(MiniJson.Obj(o, "args") ?? new Dictionary<string, object>());
                _bridge.SendCommandResult(request, r.Status, r.Message);
            }
            catch (Exception ex)
            {
                Report("command " + name + " failed: " + ex.Message);
                _bridge.SendCommandResult(request, Bridge.ResultFailed, ex.Message);
            }
        }

        private static void ReadFiles(bool force)
        {
            DateTime lt = Time(DuoPaths.LayoutFile);
            if (force || lt != _layoutTime)
            {
                _layoutTime = lt;
                string error;
                var layout = lt == DateTime.MinValue ? null : DuoLayout.Load(out error);
                bool changed = layout == null ? Layout != null : !layout.SameGeometry(Layout);
                Layout = layout;
                if (changed)
                    LayoutChanged?.Invoke(layout);
            }
            int port = Layout?.BridgePort ?? 0;
            if (port == 0)
                port = DuoLayout.ReadBridgePort();
            if (port != _bridge.AppPort)
            {
                _bridge.AppPort = port;
                _nextHelloMs = 0;
            }
            DateTime ut = Time(DuoPaths.UiFile);
            if (force || ut != _uiTime)
            {
                _uiTime = ut;
                ReadUi();
            }
        }

        private static void ReadUi()
        {
            string error;
            Ui = UiFile.Load(out error);
            if (error.Length > 0)
                Report(error);
            RulesVersion++;
            RulesChanged?.Invoke();
        }

        private static void WriteElements()
        {
            try
            {
                DuoPaths.WriteAtomic(DuoPaths.ElementsFile, ElementsFile.Build(_engine, Layout?.ScreenW ?? _windowW, Layout?.ScreenH ?? _windowH, Elements.Snapshot()));
            }
            catch (Exception ex)
            {
                Report("elements.json not written: " + ex.Message);
            }
        }

        private static void WriteFeatures()
        {
            _featuresWritten = true;
            try
            {
                var fixedOnes = Elements.Snapshot();
                DuoPaths.WriteAtomic(DuoPaths.FeaturesFile, ElementsFile.BuildFeatures(_engine, TouchHandlers.Count > 0, Backgrounds, fixedOnes, false));
            }
            catch (Exception ex)
            {
                Report("features.json not written: " + ex.Message);
            }
        }

        private static DateTime Time(string path)
        {
            try
            {
                return File.Exists(path) ? File.GetLastWriteTimeUtc(path) : DateTime.MinValue;
            }
            catch (Exception)
            {
                return DateTime.MinValue;
            }
        }

        private static void Report(string message)
        {
            if (message == _lastError)
                return;
            _lastError = message;
            LocalLog?.Invoke("GameNative Duo: " + message);
            _bridge?.SendLog(Bridge.LogWarn, message);
        }
    }
}
