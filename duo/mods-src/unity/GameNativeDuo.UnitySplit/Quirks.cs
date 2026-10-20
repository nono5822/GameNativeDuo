using System;
using System.Reflection;
using HarmonyLib;
using UnityEngine;
using UnityEngine.EventSystems;

namespace GameNativeDuo.UnitySplit
{
    /// <summary>
    /// Patches for UI code particular to some games, found by type and method name. Each one is inert when its game
    /// is not running (the type does not exist) and is logged when applied. Add a game here only when the generic
    /// split cannot handle it, and say which game and why.
    /// </summary>
    internal static class Quirks
    {
        /// <summary>True while Subnautica's uGUI_CanvasScaler sizes a canvas (see <see cref="FrustumPrefix"/>).</summary>
        [ThreadStatic] private static bool _sizingCanvas;

        public static void Apply(Harmony harmony)
        {
            // Subnautica: uGUI_CanvasScaler fits its world-space UI canvases to the UI camera using the whole window's
            // size (GraphicsUtil.GetScreenSize). Its UI camera is on the bottom screen, so while it sizes a canvas the
            // "screen" is the bottom screen.
            Patch(harmony, "uGUI_CanvasScaler", "UpdateFrustum", nameof(FrustumPrefix), nameof(FrustumPostfix), nameof(FrustumFinalizer));
            Patch(harmony, "GraphicsUtil", "GetScreenSize", nameof(ScreenSizePrefix), null, null);

            // Subnautica: a dragged inventory item is drawn at the game's own cursor raycast. While a finger is down,
            // that raycast is the finger's.
            Patch(harmony, "CursorManager", "SetRaycastResult", nameof(CursorPrefix), null, null);

            // Hollow Knight and Silksong (Team Cherry): ForceCameraAspect letterboxes the world camera (a tk2dCamera) and
            // the HUD camera to the window's shape; on the tall split window that is a thin band in the middle. While
            // the split is on it sees a 16:9 screen, the world camera goes to the top screen (CameraPlacer, through
            // tk2d's settings) and GameCameras.hudCamera, which draws the HUD, inventory and menus, to the bottom one.
            Patch(harmony, "ForceCameraAspect", "AutoScaleViewportShared", nameof(TeamCherryAspectPrefix), null, null);
            _gameCamerasInstance = Field("GameCameras", "_instance");
            _hudCamera = Field("GameCameras", "hudCamera");
            _forceCameraAspect = AccessTools.TypeByName("ForceCameraAspect");
            _anchorTopLeft = _forceCameraAspect != null ? AccessTools.Field(_forceCameraAspect, "anchorTopLeft") : null;
            _initialHudCamSize = _forceCameraAspect != null ? AccessTools.Field(_forceCameraAspect, "initialHudCamSize") : null;
        }

        private static FieldInfo _gameCamerasInstance, _hudCamera, _anchorTopLeft, _initialHudCamSize;
        private static Type _forceCameraAspect;

        private static FieldInfo Field(string type, string field)
        {
            Type t = AccessTools.TypeByName(type);
            return t != null ? AccessTools.Field(t, field) : null;
        }

        /// <summary>Cameras a game uses for its HUD that the generic check (draws only the "UI" layer) misses.</summary>
        public static bool IsGameUiCamera(Camera cam)
        {
            try
            {
                if (_gameCamerasInstance == null || _hudCamera == null)
                    return false;
                object cams = _gameCamerasInstance.GetValue(null);
                return cams != null && (cams as UnityEngine.Object) != null && ReferenceEquals(_hudCamera.GetValue(cams), cam);
            }
            catch (Exception)
            {
                return false;
            }
        }

        /// <summary>
        /// A game UI camera's orthographic size changed for the bottom screen's shape. Team Cherry anchors the HUD to
        /// the camera's top-left from its size; keep the HUD glued to the top of the bottom screen.
        /// </summary>
        public static void OnUiCameraResized(Camera cam, float oldSize, float newSize)
        {
            if (_anchorTopLeft == null || _initialHudCamSize == null || !IsGameUiCamera(cam))
                return;
            try
            {
                var aspect = UnityEngine.Object.FindObjectOfType(_forceCameraAspect);
                if (aspect == null)
                    return;
                var anchor = _anchorTopLeft.GetValue(aspect) as Transform;
                if (anchor == null)
                    return;
                float initial = (float)_initialHudCamSize.GetValue(aspect);
                if (initial <= 0f)
                    return;
                anchor.localPosition = new Vector3(0f, newSize - initial, 0f);
            }
            catch (Exception ex)
            {
                Plugin.Log.LogWarning("Could not re-anchor the HUD: " + ex.Message);
            }
        }

        private static bool TeamCherryAspectPrefix(ref Rect newViewPortRect, ref float heightMult, ref float __result)
        {
            Split p = Split.Instance;
            if (p == null || p.Layout == null || !p.Layout.Matches(Screen.width, Screen.height))
                return true;
            // As on a 16:9 screen: full viewport, no zoom. Where each camera goes is CameraPlacer's job.
            newViewPortRect = new Rect(0f, 0f, 1f, 1f);
            heightMult = 1f;
            __result = 16f / 9f;
            return false;
        }

        private static void Patch(Harmony harmony, string typeName, string method, string prefix, string postfix, string finalizer)
        {
            try
            {
                Type type = AccessTools.TypeByName(typeName);
                MethodInfo target = type != null ? AccessTools.Method(type, method) : null;
                if (target == null)
                    return;
                harmony.Patch(
                    target,
                    prefix: prefix != null ? new HarmonyMethod(typeof(Quirks), prefix) : null,
                    postfix: postfix != null ? new HarmonyMethod(typeof(Quirks), postfix) : null,
                    finalizer: finalizer != null ? new HarmonyMethod(typeof(Quirks), finalizer) : null);
                Plugin.Log.LogInfo("Game quirk patched: " + typeName + "." + method);
            }
            catch (Exception ex)
            {
                Plugin.Log.LogError("Could not patch " + typeName + "." + method + ": " + ex);
            }
        }

        private static void FrustumPrefix() => _sizingCanvas = true;

        private static void FrustumPostfix() => _sizingCanvas = false;

        private static Exception FrustumFinalizer(Exception __exception)
        {
            _sizingCanvas = false;
            return __exception;
        }

        private static bool ScreenSizePrefix(ref Vector2Int __result)
        {
            Split p = Split.Instance;
            if (!_sizingCanvas || p == null || !p.Active)
                return true;
            __result = new Vector2Int(p.Layout.Bottom.width, p.Layout.Bottom.height);
            return false;
        }

        private static void CursorPrefix(ref RaycastResult raycastResult)
        {
            if (TouchInjector.FingersDown > 0 && TouchInjector.FingerRaycast.gameObject != null)
                raycastResult = TouchInjector.FingerRaycast;
        }
    }
}
