using System;
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
    [BepInPlugin("app.gamenative.duo.unitysplit", "GameNative Duo Unity Split", "0.2.0")]
    public sealed class Plugin : BaseUnityPlugin
    {
        private const string Engine = "unity-ugui";
        private const float RescanSeconds = 0.5f;
        private const float WatchSeconds = 2f;
        private const float HelloSeconds = 2f;

        internal static ManualLogSource Log;
        internal static Plugin Instance;

        internal Layout Layout;
        internal readonly UiRouter Router = new UiRouter();

        /// <summary>True while the window matches <see cref="Layout"/>.</summary>
        internal bool Active;

        private UiRules _rules = UiRules.Empty;
        private TouchInjector _touch;
        private Bridge _bridge;
        private readonly List<Bridge.Message> _messages = new List<Bridge.Message>();
        private float _nextScan, _nextWatch, _nextHello;
        private DateTime _layoutTime, _rulesTime;
        private bool _dumpPending;
        private uint _dumpRequest;
        private bool _scaleErrorLogged;

        private void Awake()
        {
            Log = Logger;
            Instance = this;
            _touch = new TouchInjector(Router);
            ReloadLayout();
            ReloadRules();
            try
            {
                new Harmony("app.gamenative.duo.unitysplit").PatchAll(typeof(ScalerPatch));
            }
            catch (Exception ex)
            {
                Log.LogError("Could not patch CanvasScaler (UI keeps the full-window scale): " + ex);
            }
            Canvas.willRenderCanvases += OnWillRenderCanvases;
        }

        private void OnDestroy()
        {
            Canvas.willRenderCanvases -= OnWillRenderCanvases;
            if (_bridge != null)
                _bridge.Dispose();
        }

        private void Update()
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
                    _bridge.SendHello(Bridge.CapTouch | Bridge.CapUiRules | Bridge.CapElements, Engine);
                }
                _bridge.Drain(_messages);
                if (_messages.Count == 0)
                    return;
                foreach (Bridge.Message m in _messages)
                {
                    switch (m.Type)
                    {
                        case Bridge.Touch:
                            if (Active)
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
                if (!Active || Time.unscaledTime < _nextScan)
                    return;
                _nextScan = Time.unscaledTime + RescanSeconds;
                Router.Scan();
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
            if (!Active)
                return;
            try
            {
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
            Plugin p = Plugin.Instance;
            if (p == null || !p.Active)
                return;
            try
            {
                p.Router.AdjustScale(__instance, ref scaleFactor);
            }
            catch (Exception ex)
            {
                p.OnScaleError(ex);
            }
        }
    }
}
