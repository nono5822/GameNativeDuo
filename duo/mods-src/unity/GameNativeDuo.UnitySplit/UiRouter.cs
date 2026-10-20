using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using UnityEngine;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

namespace GameNativeDuo.UnitySplit
{
    /// <summary>Left where an element moved to the other screen used to be, so its id, visibility and fading still follow the game.</summary>
    internal sealed class DuoPlaceholder : MonoBehaviour
    {
        public RectTransform Target;
    }

    /// <summary>Holds an element moved to the other screen; <see cref="Id"/> is the element's id at its original place.</summary>
    internal sealed class DuoProxy : MonoBehaviour
    {
        public string Id;
    }

    /// <summary>Marks canvases the plugin created.</summary>
    internal sealed class DuoOwned : MonoBehaviour
    {
    }

    /// <summary>
    /// Puts the game's uGUI on the screens the user chose. Game cameras are squeezed into the top screen; every
    /// screen-space root canvas renders through a UI camera over the bottom screen, over the top screen, or a disabled
    /// one (hidden). Single elements follow the user's <see cref="UiRules"/>: moved to the other screen (reparented
    /// under a proxy there; a placeholder keeps their place), hidden (scale 0), resized or shifted.
    ///
    /// Cost: canvases are scanned twice a second (the old plugin's cost); per frame only the elements the user
    /// changed are touched, without allocations. Transforms the game animates (Slime Rancher's crosshair pulses its
    /// scale every frame) keep working: whatever the game writes is taken as the new base and the rule applied on top.
    /// </summary>
    internal sealed class UiRouter
    {
        private const int MaxDumpElements = 800;
        private const int MaxDumpDepth = 10;

        private sealed class RootState
        {
            public Canvas Canvas;
            public CanvasScaler Scaler;
            public string Id;
            public DuoScreen Screen;
            public float Scale = 1f;
            public RenderMode OriginalMode;
            public Camera OriginalCamera;

            /// <summary>Our own canvas holding elements moved here from <see cref="Source"/>.</summary>
            public Canvas Source;

            public bool IsProxy => Source != null || Id == null;
        }

        private sealed class ElementState
        {
            public string Id;
            public RectTransform Rt;
            public UiRule Rule;
            public RootState SourceRoot;
            public RootState Root;
            public bool HasApplied;
            public Vector3 BaseScale, AppliedScale, BasePos, AppliedPos;
            public GameObject Placeholder;
            public RectTransform Proxy;
            public CanvasGroup ProxyGroup;
            public readonly List<CanvasGroup> Groups = new List<CanvasGroup>();

            /// <summary>Moved to the other screen (true even once Unity destroyed the proxy, so it gets cleaned up).</summary>
            public bool Moved => !ReferenceEquals(Proxy, null);
        }

        private Layout _layout;
        private UiRules _rules = UiRules.Empty;
        private Camera _bottomCam, _topCam, _hiddenCam;
        private readonly Dictionary<Canvas, RootState> _roots = new Dictionary<Canvas, RootState>();
        private readonly Dictionary<CanvasScaler, RootState> _byScaler = new Dictionary<CanvasScaler, RootState>();
        private readonly Dictionary<string, ElementState> _elements = new Dictionary<string, ElementState>();
        private readonly List<ElementState> _list = new List<ElementState>();
        private readonly Dictionary<long, RootState> _proxyCanvases = new Dictionary<long, RootState>();
        private readonly HashSet<string> _aimIds = new HashSet<string>();
        private readonly Dictionary<Camera, Rect> _originalRects = new Dictionary<Camera, Rect>();
        private readonly Dictionary<Camera, Rect> _appliedRects = new Dictionary<Camera, Rect>();
        private readonly List<Canvas> _dead = new List<Canvas>();
        private readonly Vector3[] _corners = new Vector3[4];
        private int _dumpCount;

        public void SetLayout(Layout layout)
        {
            _layout = layout;
            if (_bottomCam != null)
                _bottomCam.rect = layout.Viewport(layout.Bottom);
            if (_hiddenCam != null)
                _hiddenCam.rect = layout.Viewport(layout.Bottom);
            if (_topCam != null)
                _topCam.rect = layout.Viewport(layout.Top);
        }

        /// <summary>New rules from ui.json: applied right away to everything already found.</summary>
        public void SetRules(UiRules rules)
        {
            _rules = rules ?? UiRules.Empty;
            foreach (string id in _aimIds)
                _rules.AddImplicit(id, DuoScreen.Top);
            if (_layout == null || _bottomCam == null)
                return;
            foreach (RootState root in _roots.Values)
            {
                if (root.IsProxy)
                    continue;
                UiRule rule = _rules.Get(root.Id);
                root.Screen = RootScreen(rule);
                root.Scale = rule != null ? rule.Scale : 1f;
                PlaceRoot(root);
            }
            ResolveElements();
        }

        // ---- twice a second -------------------------------------------------------------------------------------

        public void Scan()
        {
            EnsureCameras();
            PlaceCameras();

            foreach (Canvas canvas in UnityEngine.Object.FindObjectsOfType<Canvas>())
            {
                if (canvas == null || !canvas.isRootCanvas)
                    continue;
                RootState root;
                if (!_roots.TryGetValue(canvas, out root))
                {
                    if (canvas.renderMode == RenderMode.WorldSpace || canvas.GetComponent<DuoOwned>() != null)
                        continue;
                    root = Register(canvas);
                }
                PlaceRoot(root);
            }

            foreach (var pair in _roots)
            {
                if (pair.Key == null)
                    _dead.Add(pair.Key);
            }
            foreach (Canvas c in _dead)
                Forget(c);
            _dead.Clear();

            ResolveElements();
            foreach (ElementState st in _list)
            {
                if (st.Placeholder != null && st.Proxy != null)
                {
                    CollectGroups(st);
                    FitProxy(st);
                }
            }
            if (_topCam != null)
                _topCam.enabled = HasCanvasOn(DuoScreen.Top);
        }

        private RootState Register(Canvas canvas)
        {
            var root = new RootState
            {
                Canvas = canvas,
                Scaler = canvas.GetComponent<CanvasScaler>(),
                Id = PathOf(canvas.transform),
                OriginalMode = canvas.renderMode,
                OriginalCamera = canvas.worldCamera,
            };
            UiRule rule = _rules.Get(root.Id);
            root.Screen = RootScreen(rule);
            root.Scale = rule != null ? rule.Scale : 1f;
            _roots[canvas] = root;
            if (root.Scaler != null)
                _byScaler[root.Scaler] = root;
            FindAimElements(root);
            Plugin.Log.LogDebug("Canvas '" + root.Id + "' (layer " + canvas.gameObject.layer + ", order " + canvas.sortingOrder + ") -> " + root.Screen);
            return root;
        }

        /// <summary>Crosshairs and reticles are aimed through the game view: unless the user says otherwise they stay on top.</summary>
        private void FindAimElements(RootState root)
        {
            foreach (Transform t in root.Canvas.GetComponentsInChildren<Transform>(true))
            {
                if (t == root.Canvas.transform || !UiRules.IsAimElement(t.name))
                    continue;
                string id = PathOf(t);
                bool insideAnother = false;
                foreach (string other in _aimIds)
                {
                    if (id.StartsWith(other + "/", StringComparison.Ordinal))
                        insideAnother = true;
                }
                if (insideAnother || !_aimIds.Add(id))
                    continue;
                if (_rules.AddImplicit(id, DuoScreen.Top))
                    Plugin.Log.LogInfo("'" + id + "' looks like a crosshair: kept on the top screen");
            }
        }

        private void PlaceRoot(RootState root)
        {
            Canvas c = root.Canvas;
            if (c == null)
                return;
            Camera cam = CameraFor(root.Screen);
            if (c.renderMode != RenderMode.ScreenSpaceCamera || c.worldCamera != cam)
            {
                c.renderMode = RenderMode.ScreenSpaceCamera;
                c.worldCamera = cam;
                c.planeDistance = 10f;
            }
            cam.cullingMask |= 1 << c.gameObject.layer;
        }

        private void Forget(Canvas key)
        {
            RootState root = _roots[key];
            _roots.Remove(key);
            if (!ReferenceEquals(root.Scaler, null))
                _byScaler.Remove(root.Scaler);
            var stale = new List<long>();
            foreach (var pair in _proxyCanvases)
            {
                if (pair.Value == root || pair.Value.Source == null)
                    stale.Add(pair.Key);
            }
            foreach (long k in stale)
            {
                RootState proxy = _proxyCanvases[k];
                _proxyCanvases.Remove(k);
                if (proxy.Canvas != null)
                    UnityEngine.Object.Destroy(proxy.Canvas.gameObject);
            }
        }

        private void ResolveElements()
        {
            for (int i = _list.Count - 1; i >= 0; i--)
            {
                ElementState st = _list[i];
                UiRule rule = _rules.Get(st.Id);
                if (st.Rt == null || rule == null)
                {
                    Release(st);
                    _list.RemoveAt(i);
                    _elements.Remove(st.Id);
                }
                else if (!rule.SameAs(st.Rule))
                {
                    Apply(st, rule);
                }
            }

            foreach (var pair in _rules.All)
            {
                if (_elements.ContainsKey(pair.Key))
                    continue;
                RootState root;
                Transform found = Find(pair.Key, out root);
                var rt = found as RectTransform;
                if (rt == null)
                    continue;
                var st = new ElementState { Id = pair.Key, Rt = rt, SourceRoot = root, Root = root };
                _elements[pair.Key] = st;
                _list.Add(st);
                Apply(st, pair.Value);
            }
        }

        private void Apply(ElementState st, UiRule rule)
        {
            st.Rule = rule;
            DuoScreen want = rule.Screen;
            bool wantMove = (want == DuoScreen.Top || want == DuoScreen.Bottom) && want != st.SourceRoot.Screen;
            if (st.Moved && (!wantMove || st.Root.Screen != want))
                Return(st);
            if (wantMove && !st.Moved)
                Move(st, want);
        }

        // ---- moving single elements to the other screen -----------------------------------------------------------

        private void Move(ElementState st, DuoScreen screen)
        {
            RectTransform el = st.Rt;
            Transform parent = el.parent;
            if (parent == null || st.SourceRoot.Canvas == null)
                return;
            var ph = new GameObject(el.name, typeof(RectTransform));
            ph.layer = el.gameObject.layer;
            ph.transform.SetParent(parent, false);
            ph.transform.SetSiblingIndex(el.GetSiblingIndex());
            ph.AddComponent<LayoutElement>().ignoreLayout = true;
            ph.AddComponent<DuoPlaceholder>().Target = el;

            RootState target = ProxyCanvas(st.SourceRoot, screen);
            var proxyGo = new GameObject("GameNativeDuo proxy", typeof(RectTransform));
            proxyGo.layer = el.gameObject.layer;
            proxyGo.AddComponent<DuoProxy>().Id = st.Id;
            var proxy = (RectTransform)proxyGo.transform;
            proxy.SetParent(target.Canvas.transform, false);
            st.ProxyGroup = proxyGo.AddComponent<CanvasGroup>();
            el.SetParent(proxy, false);

            st.Placeholder = ph;
            st.Proxy = proxy;
            st.Root = target;
            CollectGroups(st);
            FitProxy(st);
            Plugin.Log.LogInfo("'" + st.Id + "' moved to the " + UiRule.ScreenId(screen) + " screen");
        }

        /// <summary>Puts a moved element back where it came from.</summary>
        private void Return(ElementState st)
        {
            GameObject ph = st.Placeholder;
            if (st.Rt != null && ph != null)
            {
                st.Rt.SetParent(ph.transform.parent, false);
                st.Rt.SetSiblingIndex(ph.transform.GetSiblingIndex());
            }
            if (ph != null)
                UnityEngine.Object.Destroy(ph);
            // Still under the proxy only if its old parent is gone, and then the game expects it gone too.
            if (st.Proxy != null)
                UnityEngine.Object.Destroy(st.Proxy.gameObject);
            st.Placeholder = null;
            st.Proxy = null;
            st.ProxyGroup = null;
            st.Groups.Clear();
            st.Root = st.SourceRoot;
        }

        /// <summary>Undoes everything done to one element.</summary>
        private void Release(ElementState st)
        {
            if (st.Rt != null && st.HasApplied)
            {
                if (st.Rt.localScale == st.AppliedScale)
                    st.Rt.localScale = st.BaseScale;
                if (st.Rt.localPosition == st.AppliedPos)
                    st.Rt.localPosition = st.BasePos;
            }
            st.HasApplied = false;
            if (st.Moved)
                Return(st);
        }

        /// <summary>
        /// One canvas per (game canvas, screen) for elements moved there, with the game canvas's scaler settings, so
        /// a moved element keeps its size and anchoring relative to the screen it lands on.
        /// </summary>
        private RootState ProxyCanvas(RootState source, DuoScreen screen)
        {
            long key = ((long)source.Canvas.GetInstanceID() << 2) | (long)screen;
            RootState existing;
            if (_proxyCanvases.TryGetValue(key, out existing) && existing.Canvas != null)
                return existing;
            Canvas src = source.Canvas;
            var go = new GameObject("GameNativeDuo " + UiRule.ScreenId(screen) + " canvas (" + src.name + ")", typeof(RectTransform));
            go.layer = src.gameObject.layer;
            go.AddComponent<DuoOwned>();
            if (src.gameObject.scene.IsValid() && src.gameObject.scene.buildIndex >= 0)
                SceneManager.MoveGameObjectToScene(go, src.gameObject.scene);
            else
                UnityEngine.Object.DontDestroyOnLoad(go);
            Canvas canvas = go.AddComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceCamera;
            canvas.worldCamera = CameraFor(screen);
            canvas.planeDistance = 10f;
            canvas.sortingLayerID = src.sortingLayerID;
            canvas.sortingOrder = src.sortingOrder;
            canvas.pixelPerfect = src.pixelPerfect;
            CanvasScaler s = source.Scaler;
            CanvasScaler scaler = go.AddComponent<CanvasScaler>();
            if (s != null)
            {
                scaler.uiScaleMode = s.uiScaleMode;
                scaler.referencePixelsPerUnit = s.referencePixelsPerUnit;
                scaler.scaleFactor = s.scaleFactor;
                scaler.referenceResolution = s.referenceResolution;
                scaler.screenMatchMode = s.screenMatchMode;
                scaler.matchWidthOrHeight = s.matchWidthOrHeight;
                scaler.physicalUnit = s.physicalUnit;
                scaler.fallbackScreenDPI = s.fallbackScreenDPI;
                scaler.defaultSpriteDPI = s.defaultSpriteDPI;
                scaler.dynamicPixelsPerUnit = s.dynamicPixelsPerUnit;
            }
            if (src.GetComponent<GraphicRaycaster>() != null)
                go.AddComponent<GraphicRaycaster>();
            CameraFor(screen).cullingMask |= 1 << go.layer;
            var root = new RootState { Canvas = canvas, Scaler = scaler, Screen = screen, Source = src };
            _proxyCanvases[key] = root;
            _byScaler[scaler] = root;
            if (_topCam != null && screen == DuoScreen.Top)
                _topCam.enabled = true;
            return root;
        }

        /// <summary>The proxy covers the same part of its screen as the element's old parent did of the game canvas.</summary>
        private void FitProxy(ElementState st)
        {
            var parent = st.Placeholder.transform.parent as RectTransform;
            var root = st.SourceRoot.Canvas != null ? st.SourceRoot.Canvas.transform as RectTransform : null;
            if (parent == null || root == null)
                return;
            Rect r = root.rect;
            if (r.width <= 0f || r.height <= 0f)
                return;
            parent.GetWorldCorners(_corners);
            Vector2 a = root.InverseTransformPoint(_corners[0]);
            Vector2 b = root.InverseTransformPoint(_corners[2]);
            var min = new Vector2((a.x - r.xMin) / r.width, (a.y - r.yMin) / r.height);
            var max = new Vector2((b.x - r.xMin) / r.width, (b.y - r.yMin) / r.height);
            RectTransform p = st.Proxy;
            if (p.anchorMin != min || p.anchorMax != max)
            {
                p.anchorMin = min;
                p.anchorMax = max;
                p.offsetMin = Vector2.zero;
                p.offsetMax = Vector2.zero;
            }
        }

        /// <summary>Canvas groups between the old parent and its canvas; their fading is copied onto the proxy.</summary>
        private static void CollectGroups(ElementState st)
        {
            st.Groups.Clear();
            for (Transform t = st.Placeholder.transform.parent; t != null; t = t.parent)
            {
                var g = t.GetComponent<CanvasGroup>();
                if (g != null)
                {
                    st.Groups.Add(g);
                    if (g.ignoreParentGroups)
                        break;
                }
                if (t.GetComponent<Canvas>() is Canvas c && c.isRootCanvas)
                    break;
            }
        }

        // ---- every frame (Canvas.willRenderCanvases, after uGUI's layout) ------------------------------------------

        public void ApplyFrame()
        {
            for (int i = _list.Count - 1; i >= 0; i--)
            {
                ElementState st = _list[i];
                if (st.Rt == null || (st.Moved && st.Placeholder == null))
                {
                    // Destroyed by the game, or its old parent was: it goes too.
                    Release(st);
                    _list.RemoveAt(i);
                    _elements.Remove(st.Id);
                    continue;
                }
                if (st.Moved)
                    Follow(st);
                ApplyTransform(st);
            }
        }

        /// <summary>A moved element shows and fades with its old parent.</summary>
        private static void Follow(ElementState st)
        {
            bool active = st.Placeholder.activeInHierarchy;
            GameObject proxy = st.Proxy.gameObject;
            if (proxy.activeSelf != active)
                proxy.SetActive(active);
            if (!active)
                return;
            float alpha = 1f;
            for (int i = 0; i < st.Groups.Count; i++)
            {
                CanvasGroup g = st.Groups[i];
                if (g != null && g.enabled)
                    alpha *= g.alpha;
            }
            if (st.ProxyGroup.alpha != alpha)
                st.ProxyGroup.alpha = alpha;
        }

        private void ApplyTransform(ElementState st)
        {
            UiRule rule = st.Rule;
            bool want = rule.ChangesTransform;
            if (!want && !st.HasApplied)
                return;
            RectTransform rt = st.Rt;
            Vector3 scale = rt.localScale;
            Vector3 pos = rt.localPosition;
            if (!st.HasApplied || scale != st.AppliedScale)
                st.BaseScale = scale;
            if (!st.HasApplied || pos != st.AppliedPos)
                st.BasePos = pos;
            if (!want)
            {
                rt.localScale = st.BaseScale;
                rt.localPosition = st.BasePos;
                st.HasApplied = false;
                return;
            }
            Vector3 ts = st.BaseScale * (rule.Screen == DuoScreen.Hidden ? 0f : rule.Scale);
            Vector3 tp = st.BasePos + Offset(st);
            if (scale != ts)
                rt.localScale = ts;
            if (pos != tp)
                rt.localPosition = tp;
            st.AppliedScale = ts;
            st.AppliedPos = tp;
            st.HasApplied = true;
        }

        /// <summary>The rule's offset (fractions of the screen the element is on) in the element's parent space.</summary>
        private Vector3 Offset(ElementState st)
        {
            UiRule rule = st.Rule;
            if (rule.X == 0f && rule.Y == 0f)
                return Vector3.zero;
            DuoScreen screen = st.Root.Screen == DuoScreen.Top ? DuoScreen.Top : DuoScreen.Bottom;
            Camera cam = CameraFor(screen);
            if (cam == null || !cam.orthographic)
                return Vector3.zero;
            RectInt region = _layout.Region(screen);
            float unitsPerPixel = 2f * cam.orthographicSize / Mathf.Max(1, cam.pixelHeight);
            var world = new Vector3(rule.X * region.width * unitsPerPixel, -rule.Y * region.height * unitsPerPixel, 0f);
            Transform parent = st.Rt.parent;
            return parent != null ? parent.InverseTransformVector(world) : world;
        }

        /// <summary>Called from CanvasScaler.SetScaleFactor: lays a canvas out for the screen it is on, times the user's size.</summary>
        public void AdjustScale(CanvasScaler scaler, ref float scaleFactor)
        {
            RootState root;
            if (_layout == null || !_byScaler.TryGetValue(scaler, out root))
                return;
            if (scaler.uiScaleMode == CanvasScaler.ScaleMode.ScaleWithScreenSize)
            {
                RectInt region = _layout.Region(root.Screen == DuoScreen.Top ? DuoScreen.Top : DuoScreen.Bottom);
                scaleFactor = ScreenScale(scaler, region.width, region.height);
            }
            scaleFactor *= root.Scale;
        }

        /// <summary>Unity's own ScaleWithScreenSize formula, for a screen of the given size.</summary>
        private static float ScreenScale(CanvasScaler s, float width, float height)
        {
            Vector2 reference = s.referenceResolution;
            if (reference.x <= 0f || reference.y <= 0f)
                return 1f;
            switch (s.screenMatchMode)
            {
                case CanvasScaler.ScreenMatchMode.Expand:
                    return Mathf.Min(width / reference.x, height / reference.y);
                case CanvasScaler.ScreenMatchMode.Shrink:
                    return Mathf.Max(width / reference.x, height / reference.y);
                default:
                    float logW = Mathf.Log(width / reference.x, 2f);
                    float logH = Mathf.Log(height / reference.y, 2f);
                    return Mathf.Pow(2f, Mathf.Lerp(logW, logH, s.matchWidthOrHeight));
            }
        }

        /// <summary>True for objects on a hidden canvas (touches must not reach them).</summary>
        public bool IsHidden(GameObject go)
        {
            Canvas c = go.GetComponentInParent<Canvas>();
            if (c == null)
                return false;
            RootState root;
            return _roots.TryGetValue(c.rootCanvas, out root) && root.Screen == DuoScreen.Hidden;
        }

        // ---- cameras ----------------------------------------------------------------------------------------------

        private void EnsureCameras()
        {
            if (_bottomCam == null)
            {
                _bottomCam = NewUiCamera("GameNativeDuo UI camera (bottom)", _layout.Bottom, CameraClearFlags.SolidColor);
            }
            if (_topCam == null)
            {
                // Draws over the game on the top screen, so it keeps the game's picture.
                _topCam = NewUiCamera("GameNativeDuo UI camera (top)", _layout.Top, CameraClearFlags.Depth);
                _topCam.enabled = false;
            }
            if (_hiddenCam == null)
            {
                // Never renders: canvases bound to it are invisible but keep running.
                _hiddenCam = NewUiCamera("GameNativeDuo UI camera (hidden)", _layout.Bottom, CameraClearFlags.Nothing);
                _hiddenCam.enabled = false;
            }
        }

        private Camera NewUiCamera(string name, RectInt region, CameraClearFlags clear)
        {
            var go = new GameObject(name);
            UnityEngine.Object.DontDestroyOnLoad(go);
            Camera cam = go.AddComponent<Camera>();
            cam.clearFlags = clear;
            cam.backgroundColor = Color.black;
            cam.cullingMask = 0; // the layers of the canvases bound to it
            cam.orthographic = true;
            cam.depth = 100f;
            cam.nearClipPlane = 0.1f;
            cam.farClipPlane = 1000f;
            cam.useOcclusionCulling = false;
            cam.allowHDR = false;
            cam.allowMSAA = false;
            cam.rect = _layout.Viewport(region);
            return cam;
        }

        private Camera CameraFor(DuoScreen screen)
        {
            switch (screen)
            {
                case DuoScreen.Top: return _topCam;
                case DuoScreen.Hidden: return _hiddenCam;
                default: return _bottomCam;
            }
        }

        private static DuoScreen RootScreen(UiRule rule) =>
            rule == null || rule.Screen == DuoScreen.Default ? DuoScreen.Bottom : rule.Screen;

        private bool HasCanvasOn(DuoScreen screen)
        {
            foreach (RootState r in _roots.Values)
            {
                if (r.Screen == screen && r.Canvas != null)
                    return true;
            }
            foreach (RootState r in _proxyCanvases.Values)
            {
                if (r.Screen == screen && r.Canvas != null)
                    return true;
            }
            return false;
        }

        /// <summary>
        /// Every camera that draws to the window is squeezed into the top screen, keeping its own placement inside it
        /// (a minimap camera keeps its corner). A rect the game changes later is taken as its new original.
        /// </summary>
        private void PlaceCameras()
        {
            Rect top = _layout.Viewport(_layout.Top);
            foreach (Camera cam in Camera.allCameras)
            {
                if (cam == null || cam == _bottomCam || cam == _topCam || cam == _hiddenCam || cam.targetTexture != null)
                    continue;
                Rect applied;
                if (!_appliedRects.TryGetValue(cam, out applied) || cam.rect != applied)
                    _originalRects[cam] = cam.rect;
                Rect o = _originalRects[cam];
                cam.rect = new Rect(top.x + o.x * top.width, top.y + o.y * top.height, o.width * top.width, o.height * top.height);
                _appliedRects[cam] = cam.rect;
            }
        }

        /// <summary>Puts everything back as the game made it (the window no longer matches the layout).</summary>
        public void RestoreAll()
        {
            foreach (ElementState st in _list)
                Release(st);
            _list.Clear();
            _elements.Clear();
            foreach (var pair in _originalRects)
            {
                if (pair.Key != null)
                    pair.Key.rect = pair.Value;
            }
            _originalRects.Clear();
            _appliedRects.Clear();
            foreach (RootState root in _roots.Values)
            {
                if (root.Canvas == null)
                    continue;
                root.Canvas.renderMode = root.OriginalMode;
                root.Canvas.worldCamera = root.OriginalCamera;
            }
            _roots.Clear();
            _byScaler.Clear();
            foreach (RootState proxy in _proxyCanvases.Values)
            {
                if (proxy.Canvas != null)
                    UnityEngine.Object.Destroy(proxy.Canvas.gameObject);
            }
            _proxyCanvases.Clear();
            if (_topCam != null)
                _topCam.enabled = false;
        }

        // ---- element ids --------------------------------------------------------------------------------------------

        /// <summary>
        /// "Scene root/child/grandchild" by object names, "#n" for the n-th of same-named siblings. '/' and '#' in
        /// names become '_'. A moved element keeps the id of its original place.
        /// </summary>
        public static string PathOf(Transform t)
        {
            var parts = new List<string>();
            for (Transform cur = t; cur != null; cur = cur.parent)
            {
                Transform parent = cur.parent;
                DuoProxy proxy = parent != null ? parent.GetComponent<DuoProxy>() : null;
                if (proxy != null)
                {
                    parts.Add(proxy.Id);
                    break;
                }
                parts.Add(SegOf(cur));
            }
            parts.Reverse();
            return string.Join("/", parts.ToArray());
        }

        private static string Clean(string name) => name.Replace('/', '_').Replace('#', '_');

        private static string SegOf(Transform t)
        {
            string name = Clean(t.name);
            int index = 1;
            Transform parent = t.parent;
            if (parent != null)
            {
                int self = t.GetSiblingIndex();
                for (int i = 0; i < self; i++)
                {
                    if (Clean(parent.GetChild(i).name) == name)
                        index++;
                }
            }
            else if (t.gameObject.scene.IsValid())
            {
                foreach (GameObject r in t.gameObject.scene.GetRootGameObjects())
                {
                    if (r.transform == t)
                        break;
                    if (Clean(r.name) == name)
                        index++;
                }
            }
            return index > 1 ? name + "#" + index.ToString(CultureInfo.InvariantCulture) : name;
        }

        private Transform Find(string id, out RootState root)
        {
            foreach (RootState r in _roots.Values)
            {
                if (r.IsProxy || r.Canvas == null)
                    continue;
                if (id.Length > r.Id.Length && id[r.Id.Length] == '/' && id.StartsWith(r.Id, StringComparison.Ordinal))
                {
                    Transform t = FindPath(r.Canvas.transform, id.Substring(r.Id.Length + 1));
                    if (t != null)
                    {
                        root = r;
                        return t;
                    }
                }
            }
            root = null;
            return null;
        }

        private static Transform FindPath(Transform start, string path)
        {
            Transform cur = start;
            foreach (string seg in path.Split('/'))
            {
                string name = seg;
                int index = 1;
                int hash = seg.LastIndexOf('#');
                if (hash > 0 && int.TryParse(seg.Substring(hash + 1), NumberStyles.None, CultureInfo.InvariantCulture, out index))
                    name = seg.Substring(0, hash);
                else
                    index = 1;
                Transform next = null;
                for (int i = 0; i < cur.childCount && next == null; i++)
                {
                    Transform c = cur.GetChild(i);
                    if (Clean(c.name) == name && --index == 0)
                        next = c;
                }
                if (next == null)
                    return null;
                DuoPlaceholder ph = next.GetComponent<DuoPlaceholder>();
                cur = ph != null && ph.Target != null ? ph.Target : next;
            }
            return cur;
        }

        // ---- element list for the editor -----------------------------------------------------------------------------

        /// <summary>
        /// Writes every visible UI element (id, parent, screen, rectangle in window pixels from the top-left) to
        /// <paramref name="path"/>, for GameNative Duo's layout editor. Only on request: it walks the whole UI.
        /// </summary>
        public void WriteElements(string path)
        {
            var sb = new StringBuilder(32 * 1024);
            sb.Append("{\"engine\":\"unity-ugui\",\"screen\":{\"w\":").Append(Screen.width).Append(",\"h\":").Append(Screen.height).Append("},\"elements\":[");
            _dumpCount = 0;
            foreach (RootState root in _roots.Values)
            {
                if (!root.IsProxy && root.Canvas != null && root.Canvas.gameObject.activeInHierarchy)
                    Walk(sb, root.Canvas.transform, root.Id, -1, 0, root.Screen);
            }
            sb.Append("]}");
            string tmp = path + ".tmp";
            File.WriteAllText(tmp, sb.ToString());
            if (File.Exists(path))
                File.Delete(path);
            File.Move(tmp, path);
        }

        private void Walk(StringBuilder sb, Transform t, string id, int parent, int depth, DuoScreen screen)
        {
            if (_dumpCount >= MaxDumpElements)
                return;
            DuoPlaceholder ph = t.GetComponent<DuoPlaceholder>();
            ElementState moved = null;
            if (ph != null)
            {
                if (ph.Target == null || !_elements.TryGetValue(id, out moved))
                    return;
                t = ph.Target;
                screen = moved.Root.Screen;
            }
            UiRule rule = _rules.Get(id);
            if (!t.gameObject.activeInHierarchy && (rule == null || rule.Implicit))
                return;
            var rt = t as RectTransform;
            if (rt == null)
                return;
            if (rule != null && rule.Screen == DuoScreen.Hidden)
                screen = DuoScreen.Hidden;

            Rect r = WindowRect(rt, screen);
            int index = parent;
            // Zero-sized holders are common (anchor points): skip them but keep their children.
            if (depth == 0 || (r.width >= 2f && r.height >= 2f) || (rule != null && !rule.Implicit) || screen == DuoScreen.Hidden)
            {
                index = _dumpCount++;
                if (index > 0)
                    sb.Append(',');
                sb.Append("{\"id\":");
                MiniJson.Quote(sb, id);
                sb.Append(",\"name\":");
                MiniJson.Quote(sb, t.name);
                sb.Append(",\"parent\":").Append(parent);
                sb.Append(",\"screen\":\"").Append(UiRule.ScreenId(screen)).Append('"');
                sb.Append(",\"x\":").Append(Mathf.RoundToInt(r.x));
                sb.Append(",\"y\":").Append(Mathf.RoundToInt(r.y));
                sb.Append(",\"w\":").Append(Mathf.RoundToInt(r.width));
                sb.Append(",\"h\":").Append(Mathf.RoundToInt(r.height));
                sb.Append(",\"graphic\":").Append(t.GetComponent<Graphic>() != null ? "true" : "false");
                sb.Append('}');
            }
            if (depth >= MaxDumpDepth)
                return;
            Dictionary<string, int> seen = null;
            for (int i = 0; i < t.childCount; i++)
            {
                Transform c = t.GetChild(i);
                string name = Clean(c.name);
                if (seen == null)
                    seen = new Dictionary<string, int>();
                int n;
                seen.TryGetValue(name, out n);
                seen[name] = ++n;
                Walk(sb, c, id + "/" + (n > 1 ? name + "#" + n.ToString(CultureInfo.InvariantCulture) : name), index, depth + 1, screen);
            }
        }

        /// <summary>An element's bounding box in window pixels (origin top-left).</summary>
        private Rect WindowRect(RectTransform rt, DuoScreen screen)
        {
            Camera cam = CameraFor(screen);
            if (cam == null)
                return new Rect();
            rt.GetWorldCorners(_corners);
            float minX = float.MaxValue, minY = float.MaxValue, maxX = float.MinValue, maxY = float.MinValue;
            for (int i = 0; i < 4; i++)
            {
                Vector3 p = cam.WorldToScreenPoint(_corners[i]);
                minX = Mathf.Min(minX, p.x);
                maxX = Mathf.Max(maxX, p.x);
                minY = Mathf.Min(minY, p.y);
                maxY = Mathf.Max(maxY, p.y);
            }
            return new Rect(minX, Screen.height - maxY, maxX - minX, maxY - minY);
        }
    }
}
