using System;
using System.Collections.Generic;
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
    /// moves every screen-space canvas onto a UI camera that covers the bottom part, laid out for the bottom screen's
    /// size. Taps on the bottom screen reach the UI through that camera. Nothing changes unless the window matches
    /// GameNative Duo's layout.json, and every step catches its own errors so the game keeps running.
    /// </summary>
    [BepInPlugin("app.gamenative.duo.unitysplit", "GameNative Duo Unity Split", "0.1.0")]
    public sealed class Plugin : BaseUnityPlugin
    {
        internal static ManualLogSource Log;
        internal static Layout Layout;

        /// <summary>True while the window matches <see cref="Layout"/>.</summary>
        internal static bool Active;

        /// <summary>Canvases moved to the bottom screen (their scaler is computed for the bottom screen's size).</summary>
        internal static readonly HashSet<Canvas> MovedCanvases = new HashSet<Canvas>();

        private const float RescanSeconds = 0.5f;

        private Camera _uiCamera;
        private float _nextScan;
        private readonly Dictionary<Camera, Rect> _originalRects = new Dictionary<Camera, Rect>();
        private readonly Dictionary<Camera, Rect> _appliedRects = new Dictionary<Camera, Rect>();

        private void Awake()
        {
            Log = Logger;
            string error;
            Layout = Layout.Load(out error);
            if (Layout == null)
            {
                Log.LogInfo("No GameNative Duo layout (" + error + "); the game runs unchanged.");
                return;
            }
            Log.LogInfo("GameNative Duo layout: " + Layout);
            try
            {
                new Harmony("app.gamenative.duo.unitysplit").PatchAll(typeof(CanvasScalerPatch));
            }
            catch (Exception ex)
            {
                Log.LogError("Could not patch CanvasScaler (UI keeps the full-window scale): " + ex);
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
                        RestoreAll();
                    _nextScan = 0f;
                }
                if (!Active || Time.unscaledTime < _nextScan)
                    return;
                _nextScan = Time.unscaledTime + RescanSeconds;
                EnsureUiCamera();
                PlaceCameras();
                MoveCanvases();
            }
            catch (Exception ex)
            {
                Log.LogError("Screen split update failed: " + ex);
                _nextScan = Time.unscaledTime + 5f;
            }
        }

        /// <summary>An orthographic camera over the bottom screen that renders only the moved canvases.</summary>
        private void EnsureUiCamera()
        {
            if (_uiCamera != null)
                return;
            var go = new GameObject("GameNativeDuo UI camera");
            DontDestroyOnLoad(go);
            _uiCamera = go.AddComponent<Camera>();
            _uiCamera.clearFlags = CameraClearFlags.SolidColor;
            _uiCamera.backgroundColor = Color.black;
            _uiCamera.cullingMask = 0; // set to the moved canvases' layers
            _uiCamera.orthographic = true;
            _uiCamera.depth = 100f;
            _uiCamera.nearClipPlane = 0.1f;
            _uiCamera.farClipPlane = 1000f;
            _uiCamera.useOcclusionCulling = false;
            _uiCamera.allowHDR = false;
            _uiCamera.allowMSAA = false;
            _uiCamera.rect = Layout.Viewport(Layout.Bottom);
        }

        /// <summary>
        /// Every camera that draws to the window is squeezed into the top screen, keeping its own placement inside it
        /// (a minimap camera keeps its corner). A rect the game changes later is taken as its new original.
        /// </summary>
        private void PlaceCameras()
        {
            Rect top = Layout.Viewport(Layout.Top);
            foreach (Camera cam in Camera.allCameras)
            {
                if (cam == null || cam == _uiCamera || cam.targetTexture != null)
                    continue;
                Rect applied;
                if (!_appliedRects.TryGetValue(cam, out applied) || cam.rect != applied)
                    _originalRects[cam] = cam.rect;
                Rect o = _originalRects[cam];
                var r = new Rect(top.x + o.x * top.width, top.y + o.y * top.height, o.width * top.width, o.height * top.height);
                cam.rect = r;
                _appliedRects[cam] = cam.rect;
            }
        }

        /// <summary>Screen-space canvases (overlay, or bound to a game camera) render through the bottom-screen UI camera.</summary>
        private void MoveCanvases()
        {
            int mask = _uiCamera.cullingMask;
            foreach (Canvas canvas in FindObjectsOfType<Canvas>())
            {
                if (canvas == null || !canvas.isRootCanvas || canvas.renderMode == RenderMode.WorldSpace)
                    continue;
                if (canvas.renderMode == RenderMode.ScreenSpaceCamera && canvas.worldCamera == _uiCamera)
                    continue;
                canvas.renderMode = RenderMode.ScreenSpaceCamera;
                canvas.worldCamera = _uiCamera;
                canvas.planeDistance = 10f;
                mask |= 1 << canvas.gameObject.layer;
                MovedCanvases.Add(canvas);
                Log.LogDebug("Moved canvas '" + canvas.name + "' (layer " + canvas.gameObject.layer + ", order " + canvas.sortingOrder + ") to the bottom screen");
            }
            _uiCamera.cullingMask = mask;
            MovedCanvases.RemoveWhere(c => c == null);
        }

        private void RestoreAll()
        {
            foreach (var pair in _originalRects)
            {
                if (pair.Key != null)
                    pair.Key.rect = pair.Value;
            }
            _originalRects.Clear();
            _appliedRects.Clear();
            foreach (Canvas canvas in MovedCanvases)
            {
                if (canvas != null && canvas.worldCamera == _uiCamera)
                    canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            }
            MovedCanvases.Clear();
        }
    }

    /// <summary>
    /// uGUI's CanvasScaler sizes UI from the whole window (Screen.width/height); a moved canvas is scaled for the
    /// bottom screen instead, with Unity's own formula, so the UI is laid out as if the game ran on that screen.
    /// </summary>
    [HarmonyPatch(typeof(CanvasScaler), "HandleScaleWithScreenSize")]
    internal static class CanvasScalerPatch
    {
        private static void Postfix(CanvasScaler __instance)
        {
            try
            {
                if (!Plugin.Active)
                    return;
                Canvas canvas = __instance.GetComponent<Canvas>();
                if (canvas == null || !Plugin.MovedCanvases.Contains(canvas))
                    return;
                Vector2 screen = new Vector2(Plugin.Layout.Bottom.width, Plugin.Layout.Bottom.height);
                Vector2 reference = __instance.referenceResolution;
                float scale;
                switch (__instance.screenMatchMode)
                {
                    case CanvasScaler.ScreenMatchMode.Expand:
                        scale = Mathf.Min(screen.x / reference.x, screen.y / reference.y);
                        break;
                    case CanvasScaler.ScreenMatchMode.Shrink:
                        scale = Mathf.Max(screen.x / reference.x, screen.y / reference.y);
                        break;
                    default:
                        float logW = Mathf.Log(screen.x / reference.x, 2f);
                        float logH = Mathf.Log(screen.y / reference.y, 2f);
                        scale = Mathf.Pow(2f, Mathf.Lerp(logW, logH, __instance.matchWidthOrHeight));
                        break;
                }
                SetScaleFactor(__instance, scale);
            }
            catch (Exception ex)
            {
                Plugin.Log.LogError("CanvasScaler patch failed: " + ex);
            }
        }

        private static readonly System.Reflection.MethodInfo SetScaleFactorMethod = AccessTools.Method(typeof(CanvasScaler), "SetScaleFactor");

        private static void SetScaleFactor(CanvasScaler scaler, float scale) => SetScaleFactorMethod.Invoke(scaler, new object[] { scale });
    }
}
