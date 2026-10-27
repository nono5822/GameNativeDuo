using System;
using System.Diagnostics;
using System.Collections.Generic;
using System.IO;
using BepInEx;
using BepInEx.Logging;
using HarmonyLib;
using UnityEngine;
using UnityEngine.UI;

namespace GameNativeDuo.UnitySplit
{
    /// <summary>
    /// Game on the top screen, UI on the bottom screen, for Unity games with uGUI. GameNative Duo shows one tall game
    /// window across both screens; this plugin squeezes every camera that renders to the window into the top part and
    /// moves the game's screen-space UI to the screens the user chose (see <see cref="UiRouter"/>, rules from
    /// ui.json). Bottom-screen touches arrive over GameNative Duo's bridge and go straight to uGUI
    /// (<see cref="TouchInjector"/>). Nothing changes unless the window matches GameNative Duo's layout.json, and every
    /// step catches its own errors so the game keeps running.
    /// </summary>
    [BepInPlugin("app.gamenative.duo.unitysplit", "GameNative Duo Unity Split", "0.4.1")]
    public sealed class Plugin : BaseUnityPlugin
    {
        internal static ManualLogSource Log;

        /// <summary>
        /// The work happens on an object of our own: some games destroy BepInEx's manager object while loading
        /// (Subnautica does), which would stop the split and close the bridge.
        /// </summary>
        private void Awake()
        {
            Log = Logger;
            var go = new GameObject("GameNativeDuo");
            go.hideFlags = HideFlags.HideAndDontSave;
            DontDestroyOnLoad(go);
            go.AddComponent<Split>();
        }
    }

    /// <summary>The screen split itself; see <see cref="Plugin"/>.</summary>
    internal sealed class Split : MonoBehaviour
    {
        private const string Engine = "unity-ugui";
        /// <summary>Fallback scan interval; new canvases and scenes trigger a scan right away (see <see cref="RequestScan"/>).</summary>
        private const float RescanSeconds = 2f;
        private const float StatsSeconds = 30f;
        private const float WatchSeconds = 2f;
        private const float HelloSeconds = 2f;

        private static ManualLogSource Log => Plugin.Log;
        internal static Split Instance;

        internal Layout Layout;
        internal readonly UiRouter Router = new UiRouter();

        /// <summary>True while the window matches <see cref="Layout"/>.</summary>
        internal bool Active;

        private UiRules _rules = UiRules.Empty;
        private TouchInjector _touch;
        private Bridge _bridge;
        private readonly List<Bridge.Message> _messages = new List<Bridge.Message>();
        private float _nextScan, _nextWatch, _nextHello, _nextStats;
        private static volatile bool _scanRequested;
        private int _cameraCount = -1;
        private readonly FrameStats _stats = new FrameStats();

        /// <summary>Something new may need placing (a canvas was enabled, a scene loaded): scan before this frame renders.</summary>
        internal static void RequestScan() => _scanRequested = true;
        private DateTime _layoutTime, _rulesTime;
        private bool _dumpPending;
        private uint _dumpRequest;
        private bool _scaleErrorLogged;

        private void Awake()
        {
            Instance = this;
            _touch = new TouchInjector(Router);
            ReloadLayout();
            ReloadRules();
            var harmony = new Harmony("app.gamenative.duo.unitysplit");
            try
            {
                harmony.PatchAll(typeof(ScalerPatch));
            }
            catch (Exception ex)
            {
                Log.LogError("Could not patch CanvasScaler (UI keeps the full-window scale): " + ex);
            }
            try
            {
                harmony.PatchAll(typeof(ScalerEnablePatch));
            }
            catch (Exception ex)
            {
                Log.LogWarning("New canvases are found by the periodic scan only: " + ex.Message);
            }
            Quirks.Apply(harmony);
            Canvas.willRenderCanvases += OnWillRenderCanvases;
            UnityEngine.SceneManagement.SceneManager.sceneLoaded += (scene, mode) => RequestScan();
        }

        private void OnDestroy()
        {
            Log.LogWarning("GameNative Duo split stopped: its object was destroyed");
            Canvas.willRenderCanvases -= OnWillRenderCanvases;
            if (_bridge != null)
                _bridge.Dispose();
        }

        /// <summary>
        /// A per-game plugin's own bottom-screen touch handling (e.g. Hollow Knight's map): gets each touch first,
        /// returns true when it used it. Arguments as in <see cref="TouchInjector.Handle"/>.
        /// </summary>
        internal static Func<byte, int, float, float, bool> TouchFilter;

        private bool Filtered(Bridge.Message m)
        {
            if (TouchFilter == null)
                return false;
            try
            {
                return TouchFilter(m.Action, m.Pointer, m.X, m.Y);
            }
            catch (Exception ex)
            {
                Log.LogError("Touch filter failed: " + ex);
                return false;
            }
        }

        /// <summary>Time spent in this plugin (all its per-frame hooks), reported with the frame stats.</summary>
        internal static long OwnTicks;

        private void Update()
        {
            long t0 = Stopwatch.GetTimestamp();
            UpdateBody();
            OwnTicks += Stopwatch.GetTimestamp() - t0;
        }

        private void UpdateBody()
        {
            try
            {
                float now = Time.unscaledTime;
                if (now >= _nextWatch)
                {
                    _nextWatch = now + WatchSeconds;
                    WatchFiles();
                }
                if (_bridge == null)
                    return;
                if (now >= _nextHello)
                {
                    _nextHello = now + HelloSeconds;
                    _bridge.SendHello(Bridge.CapTouch | Bridge.CapUiRules | Bridge.CapElements | Bridge.CapBgBasic, Engine);
                }
                _bridge.Drain(_messages);
                if (_messages.Count == 0)
                    return;
                foreach (Bridge.Message m in _messages)
                {
                    switch (m.Type)
                    {
                        case Bridge.Touch:
                            if (Active && !Filtered(m))
                                _touch.Handle(m.Action, m.Pointer, m.X, m.Y);
                            break;
                        case Bridge.RulesChanged:
                            ReloadRules();
                            break;
                        case Bridge.DumpElements:
                            _dumpPending = true;
                            _dumpRequest = m.Request;
                            break;
                    }
                }
                _messages.Clear();
                // Without the split there is no frame hook to answer from; answer now.
                if (_dumpPending && !Active)
                    WriteElements();
            }
            catch (Exception ex)
            {
                _messages.Clear();
                Log.LogError("GameNative Duo bridge update failed: " + ex);
            }
        }

        private void LateUpdate()
        {
            long t0 = Stopwatch.GetTimestamp();
            LateUpdateBody();
            OwnTicks += Stopwatch.GetTimestamp() - t0;
        }

        private void LateUpdateBody()
        {
            if (Layout == null)
                return;
            try
            {
                bool active = Layout.Matches(Screen.width, Screen.height);
                if (active != Active)
                {
                    Active = active;
                    Log.LogInfo("Screen split " + (active ? "on" : "off") + " (window " + Screen.width + "x" + Screen.height + ")");
                    if (!active)
                    {
                        _touch.CancelAll();
                        Router.RestoreAll();
                    }
                    _nextScan = 0f;
                }
                // Frame stats with the split off too, to compare against the unchanged game.
                float now = Time.unscaledTime;
                _stats.Add(Time.unscaledDeltaTime);
                if (now >= _nextStats)
                {
                    _nextStats = now + StatsSeconds;
                    _stats.Log(Active);
                }
                if (!Active)
                    return;
                // A new camera (cutscene, menu) would draw over both screens until placed: a count check is enough.
                int cameras = Camera.allCamerasCount;
                if (cameras != _cameraCount)
                {
                    _cameraCount = cameras;
                    _scanRequested = true;
                }
                if (_scanRequested || now >= _nextScan)
                {
                    _scanRequested = false;
                    _nextScan = now + RescanSeconds;
                    Router.Scan();
                }
                Router.ApplyCameras();
            }
            catch (Exception ex)
            {
                Log.LogError("Screen split update failed: " + ex);
                _nextScan = Time.unscaledTime + 5f;
            }
        }

        /// <summary>Once per frame, after uGUI's layout and before rendering.</summary>
        private void OnWillRenderCanvases()
        {
            long t0 = Stopwatch.GetTimestamp();
            OnWillRenderCanvasesBody();
            OwnTicks += Stopwatch.GetTimestamp() - t0;
        }

        private void OnWillRenderCanvasesBody()
        {
            if (!Active)
                return;
            try
            {
                // Again here: game LateUpdates after ours may have moved a camera; tk2d reads its rect in OnPreCull.
                Router.ApplyCameras();
                Router.ApplyFrame();
                if (_dumpPending)
                    WriteElements();
            }
            catch (Exception ex)
            {
                _dumpPending = false;
                Log.LogError("Screen split frame update failed: " + ex);
            }
        }

        private void WriteElements()
        {
            _dumpPending = false;
            try
            {
                Router.WriteElements(Path.Combine(Layout.Dir, "elements.json"));
            }
            catch (Exception ex)
            {
                Log.LogError("Could not write the UI element list: " + ex);
            }
            if (_bridge != null)
                _bridge.SendElementsWritten(_dumpRequest);
        }

        /// <summary>layout.json and ui.json are rewritten by the app while the game runs (calibration, layout editor).</summary>
        private void WatchFiles()
        {
            if (Modified(Layout.FilePath, ref _layoutTime))
                ReloadLayout();
            if (Modified(UiRules.FilePath, ref _rulesTime))
                ReloadRules();
        }

        private static bool Modified(string path, ref DateTime last)
        {
            DateTime t = File.Exists(path) ? File.GetLastWriteTimeUtc(path) : DateTime.MinValue;
            if (t == last)
                return false;
            last = t;
            return true;
        }

        private void ReloadLayout()
        {
            _layoutTime = File.Exists(Layout.FilePath) ? File.GetLastWriteTimeUtc(Layout.FilePath) : DateTime.MinValue;
            string error;
            Layout layout = Layout.Load(out error);
            if (layout == null)
            {
                if (Layout == null)
                    Log.LogInfo("No GameNative Duo layout (" + error + "); the game runs unchanged.");
                return;
            }
            if (!layout.SameGeometry(Layout))
            {
                Log.LogInfo("GameNative Duo layout: " + layout);
                if (Active)
                {
                    _touch.CancelAll();
                    Router.RestoreAll();
                    Active = false; // LateUpdate re-checks the window against the new layout
                }
            }
            Layout = layout;
            Router.SetLayout(layout);
            if (layout.BridgePort > 0)
            {
                if (_bridge == null)
                {
                    try
                    {
                        _bridge = new Bridge(layout.BridgePort);
                        _nextHello = 0f;
                        Log.LogInfo("GameNative Duo bridge: talking to the app on port " + layout.BridgePort);
                    }
                    catch (Exception ex)
                    {
                        Log.LogError("GameNative Duo bridge unavailable (touch falls back to the mouse): " + ex);
                    }
                }
                else if (_bridge.AppPort != layout.BridgePort)
                {
                    _bridge.AppPort = layout.BridgePort;
                    _nextHello = 0f;
                }
            }
        }

        private void ReloadRules()
        {
            _rulesTime = File.Exists(UiRules.FilePath) ? File.GetLastWriteTimeUtc(UiRules.FilePath) : DateTime.MinValue;
            string error;
            _rules = UiRules.Load(out error);
            if (error.Length > 0)
                Log.LogWarning(error);
            Log.LogInfo("UI layout: " + _rules.Count + " element rule(s)");
            Router.SetRules(_rules);
        }

        internal void OnScaleError(Exception ex)
        {
            if (_scaleErrorLogged)
                return;
            _scaleErrorLogged = true;
            Log.LogError("CanvasScaler patch failed: " + ex);
        }
    }

    /// <summary>
    /// uGUI's CanvasScaler sizes UI from the whole window; a canvas on one screen is laid out for that screen's size
    /// instead (Unity's own formula), times the size the user chose for it.
    /// </summary>
    [HarmonyPatch(typeof(CanvasScaler), "SetScaleFactor")]
    internal static class ScalerPatch
    {
        private static void Prefix(CanvasScaler __instance, ref float scaleFactor)
        {
            Split p = Split.Instance;
            if (p == null || !p.Active)
                return;
            long t0 = Stopwatch.GetTimestamp();
            try
            {
                p.Router.AdjustScale(__instance, ref scaleFactor);
            }
            catch (Exception ex)
            {
                p.OnScaleError(ex);
            }
            Split.OwnTicks += Stopwatch.GetTimestamp() - t0;
        }
    }

    /// <summary>uGUI enables a CanvasScaler with almost every new screen of UI: place it before it is drawn.</summary>
    [HarmonyPatch(typeof(CanvasScaler), "OnEnable")]
    internal static class ScalerEnablePatch
    {
        private static void Postfix() => Split.RequestScan();
    }

    /// <summary>
    /// Frame times while the split is on, logged every 30 s (average, 95th percentile, worst): the numbers to compare
    /// against the game with the split off. Allocation-free ring buffer.
    /// </summary>
    internal sealed class FrameStats
    {
        private readonly float[] _times = new float[2048];
        private readonly float[] _sorted = new float[2048];
        private int _count;

        public void Add(float seconds)
        {
            if (_count < _times.Length)
                _times[_count++] = seconds;
        }

        public void Log(bool split)
        {
            if (_count < 10)
            {
                _count = 0;
                return;
            }
            float sum = 0f, worst = 0f;
            for (int i = 0; i < _count; i++)
            {
                sum += _times[i];
                worst = Mathf.Max(worst, _times[i]);
                _sorted[i] = _times[i];
            }
            System.Array.Sort(_sorted, 0, _count);
            float avg = sum / _count;
            float p95 = _sorted[(int)(_count * 0.95f)];
            double own = Split.OwnTicks * 1000.0 / Stopwatch.Frequency / _count;
            Split.OwnTicks = 0;
            Plugin.Log.LogInfo(string.Format(System.Globalization.CultureInfo.InvariantCulture,
                "Frames ({6}): {0:0.0} fps average, {1:0.0} ms average, {2:0.0} ms 95th percentile, {3:0.0} ms worst ({4} frames); this plugin {5:0.000} ms per frame",
                1f / avg, avg * 1000f, p95 * 1000f, worst * 1000f, _count, own, split ? "split" : "no split"));
            _count = 0;
        }
    }
}
