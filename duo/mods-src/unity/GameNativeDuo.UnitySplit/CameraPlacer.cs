using System;
using System.Collections.Generic;
using System.Reflection;
using UnityEngine;

namespace GameNativeDuo.UnitySplit
{
    /// <summary>
    /// Keeps every camera that draws to the window on its screen: game cameras squeezed into the top screen (keeping
    /// their own sub-rectangle, so a minimap camera keeps its corner), the game's own UI cameras moved to the bottom
    /// screen. Cameras are found by <see cref="Discover"/> (on scans) and held in place by <see cref="Apply"/> every
    /// frame, which only writes when the game changed something: games that reset their viewport every frame
    /// (2D Toolkit's tk2dCamera does, from its own settings) stay split without a visible flicker.
    ///
    /// A UI camera's view is widened or narrowed for the bottom screen's shape (orthographic size), so a HUD laid out
    /// for 16:9 keeps all of its width on the squarer bottom screen.
    /// </summary>
    internal sealed class CameraPlacer
    {
        private sealed class Tracked
        {
            public Camera Cam;
            public bool Ui;
            public Tk2d Tk;
            public Rect Original, Applied;
            public bool HasApplied;
            public float BaseSize, AppliedSize;
            public bool SizeApplied;
        }

        /// <summary>2D Toolkit cameras own their viewport (tk2dCamera.CameraSettings.rect), set every frame in OnPreCull.</summary>
        private sealed class Tk2d
        {
            private static readonly Dictionary<Type, Tk2dAccess> Access = new Dictionary<Type, Tk2dAccess>();
            private readonly object _settings;
            private readonly FieldInfo _rect;

            private sealed class Tk2dAccess
            {
                public PropertyInfo Settings;
                public FieldInfo Rect;
            }

            private Tk2d(object settings, FieldInfo rect)
            {
                _settings = settings;
                _rect = rect;
            }

            public Rect Rect
            {
                get { return (Rect)_rect.GetValue(_settings); }
                set { _rect.SetValue(_settings, value); }
            }

            public static Tk2d For(Camera cam)
            {
                foreach (MonoBehaviour b in cam.GetComponents<MonoBehaviour>())
                {
                    if (b == null || b.GetType().Name != "tk2dCamera")
                        continue;
                    Type type = b.GetType();
                    Tk2dAccess access;
                    if (!Access.TryGetValue(type, out access))
                    {
                        PropertyInfo settings = type.GetProperty("CameraSettings", BindingFlags.Public | BindingFlags.Instance);
                        FieldInfo rect = settings != null ? settings.PropertyType.GetField("rect", BindingFlags.Public | BindingFlags.Instance) : null;
                        access = rect != null ? new Tk2dAccess { Settings = settings, Rect = rect } : null;
                        Access[type] = access;
                    }
                    if (access == null)
                        return null;
                    object value = access.Settings.GetValue(b, null);
                    return value != null ? new Tk2d(value, access.Rect) : null;
                }
                return null;
            }
        }

        private Layout _layout;
        private readonly HashSet<Camera> _ours = new HashSet<Camera>();
        private readonly Dictionary<Camera, Tracked> _tracked = new Dictionary<Camera, Tracked>();
        private readonly List<Tracked> _list = new List<Tracked>();
        private readonly List<Camera> _gameUiCameras = new List<Camera>();
        private Camera[] _buffer = new Camera[32];
        private readonly int _uiLayerMask;

        public CameraPlacer()
        {
            int ui = LayerMask.NameToLayer("UI");
            _uiLayerMask = ui >= 0 ? 1 << ui : 0;
        }

        /// <summary>The game's own UI cameras, now on the bottom screen.</summary>
        public List<Camera> GameUiCameras => _gameUiCameras;

        public void SetLayout(Layout layout) => _layout = layout;

        public void SetOurs(params Camera[] cams)
        {
            _ours.Clear();
            foreach (Camera c in cams)
            {
                if (c != null)
                    _ours.Add(c);
            }
        }

        public bool IsGameUiCamera(Camera cam)
        {
            Tracked t;
            return cam != null && _tracked.TryGetValue(cam, out t) && t.Ui;
        }

        /// <summary>Finds new cameras and forgets destroyed ones. On scans only (allocation-free, but walks all cameras).</summary>
        public void Discover()
        {
            int count = Camera.allCamerasCount;
            if (_buffer.Length < count)
                _buffer = new Camera[count * 2];
            count = Camera.GetAllCameras(_buffer);
            for (int i = 0; i < count; i++)
            {
                Camera cam = _buffer[i];
                _buffer[i] = null;
                if (cam == null || _ours.Contains(cam) || cam.targetTexture != null || _tracked.ContainsKey(cam))
                    continue;
                var t = new Tracked { Cam = cam, Ui = IsUiCamera(cam), Tk = Tk2d.For(cam) };
                _tracked[cam] = t;
                _list.Add(t);
                Plugin.Log.LogInfo("Camera '" + cam.name + "' (mask 0x" + cam.cullingMask.ToString("x") + ", depth " + cam.depth +
                    (t.Tk != null ? ", tk2d" : "") + ") -> " + (t.Ui ? "bottom screen (game UI camera)" : "top screen"));
            }
            for (int i = _list.Count - 1; i >= 0; i--)
            {
                if (_list[i].Cam == null)
                {
                    _tracked.Remove(_list[i].Cam);
                    _list.RemoveAt(i);
                }
            }
            _gameUiCameras.Clear();
            foreach (Tracked t in _list)
            {
                if (t.Ui)
                    _gameUiCameras.Add(t.Cam);
            }
        }

        /// <summary>
        /// The game's own UI camera: one that draws only the "UI" layer, one named like a UI camera ("UICamera",
        /// "HudCamera", "UI Camera": Risk of Rain 2, Hollow Knight, Silksong), or one a game quirk names.
        /// </summary>
        private bool IsUiCamera(Camera cam) =>
            Quirks.IsGameUiCamera(cam) ||
            UiCameraName.IsMatch(cam.name) ||
            (_uiLayerMask != 0 && cam.cullingMask != 0 && (cam.cullingMask & ~_uiLayerMask) == 0);

        private static readonly System.Text.RegularExpressions.Regex UiCameraName =
            new System.Text.RegularExpressions.Regex(@"^(ui|hud|gui)[ _-]?cam(era)?(\s*\(clone\))?$",
                System.Text.RegularExpressions.RegexOptions.IgnoreCase | System.Text.RegularExpressions.RegexOptions.CultureInvariant);

        /// <summary>Every frame: puts back whatever the game moved. Writes nothing when nothing changed.</summary>
        public void Apply()
        {
            if (_layout == null)
                return;
            Rect top = _layout.Viewport(_layout.Top);
            Rect bottom = _layout.Viewport(_layout.Bottom);
            for (int i = 0; i < _list.Count; i++)
            {
                Tracked t = _list[i];
                Camera cam = t.Cam;
                // A camera that renders into a texture now (portraits, minimaps) is not drawing to the window.
                if (cam == null || !cam.isActiveAndEnabled || cam.targetTexture != null)
                    continue;
                Rect current = t.Tk != null ? t.Tk.Rect : cam.rect;
                if (!t.HasApplied || current != t.Applied)
                    t.Original = current;
                Rect region = t.Ui ? bottom : top;
                Rect o = t.Original;
                var target = new Rect(region.x + o.x * region.width, region.y + o.y * region.height, o.width * region.width, o.height * region.height);
                if (current != target)
                {
                    if (t.Tk != null)
                        t.Tk.Rect = target;
                    cam.rect = target;
                }
                t.Applied = target;
                t.HasApplied = true;
                if (t.Ui && cam.orthographic)
                    FitUiCamera(t);
            }
        }

        /// <summary>Keeps a UI camera's horizontal extent: the HUD was laid out for the top screen's shape (16:9).</summary>
        private void FitUiCamera(Tracked t)
        {
            float top = (float)_layout.Top.width / Mathf.Max(1, _layout.Top.height);
            float bottom = (float)_layout.Bottom.width / Mathf.Max(1, _layout.Bottom.height);
            float k = top / bottom;
            float current = t.Cam.orthographicSize;
            if (!t.SizeApplied || !Mathf.Approximately(current, t.AppliedSize))
                t.BaseSize = current;
            float target = t.BaseSize * k;
            if (!Mathf.Approximately(current, target))
            {
                t.Cam.orthographicSize = target;
                Quirks.OnUiCameraResized(t.Cam, t.BaseSize, target);
            }
            t.AppliedSize = target;
            t.SizeApplied = true;
        }

        /// <summary>Puts every camera back as the game had it.</summary>
        public void RestoreAll()
        {
            foreach (Tracked t in _list)
            {
                if (t.Cam == null || !t.HasApplied)
                    continue;
                if (t.Tk != null)
                    t.Tk.Rect = t.Original;
                t.Cam.rect = t.Original;
                if (t.SizeApplied && Mathf.Approximately(t.Cam.orthographicSize, t.AppliedSize))
                {
                    t.Cam.orthographicSize = t.BaseSize;
                    Quirks.OnUiCameraResized(t.Cam, t.AppliedSize, t.BaseSize);
                }
            }
            _tracked.Clear();
            _list.Clear();
            _gameUiCameras.Clear();
        }
    }
}
