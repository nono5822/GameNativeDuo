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
    /// Puts the game's UI on the screens the user chose.
    ///
    /// Roots are what UI hangs from:
    /// - screen-space canvases (uGUI): bound to a UI camera of ours over the bottom screen, the top screen, or a disabled
    ///   one (hidden), laid out for that screen's size;
    /// - "passive" canvases drawn by the game's own UI camera (world-space in front of it, like Subnautica's, or
    ///   screen-space bound to it, like Hollow Knight's menus): left in place, since that camera moves to the bottom
    ///   screen (<see cref="CameraPlacer"/>);
    /// - camera roots: the children of a game's UI camera, for HUDs made of sprites rather than uGUI (Hollow Knight's
    ///   masks, soul orb and geo counter hang from its HudCamera).
    ///
    /// Elements under a root follow the user's <see cref="UiRules"/>: resized, shifted, hidden (scale 0), and for uGUI,
    /// moved to the other screen (reparented under a proxy there; a placeholder keeps their place).
    ///
    /// Cost: scans run when uGUI enables a canvas scaler or a scene loads, with a slow fallback (see Split); per frame
    /// only cameras the game moved and elements the user changed are touched, without allocations. Whatever the game
    /// writes to a changed element (Slime Rancher's crosshair pulses its scale every frame) becomes the new base and
    /// the rule is applied on top.
    /// </summary>
    internal sealed class UiRouter
    {
        private const int MaxDumpElements = 800;
        private const int MaxDumpDepth = 10;

        private sealed class RootState
        {
            public Canvas Canvas;
            public Transform Root;
            public CanvasScaler Scaler;
            public string Id;
            public DuoScreen Screen;
            public float Scale = 1f;
            public RenderMode OriginalMode;
            public Camera OriginalCamera;

            /// <summary>Our own canvas holding elements moved here from <see cref="Source"/>.</summary>
            public Canvas Source;

            /// <summary>Drawn by the game's own UI camera (<see cref="Camera"/>); never re-bound.</summary>
            public bool Passive;

            /// <summary>Passive but screen-space: still laid out for its screen's size.</summary>
            public bool ScaleForScreen;

            /// <summary>The children of a game UI camera (no canvas).</summary>
            public bool CameraRoot;

            public Camera Camera;

            public bool IsProxy => Source != null;

            public bool Alive => CameraRoot ? Camera != null : Canvas != null;
        }

        private sealed class ElementState
        {
            public string Id;
            public Transform T;
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

        private struct DumpEntry
        {
            public string Id, Name;
            public int Parent;
            public DuoScreen Screen;
            public Rect Rect;
            public bool Graphic;
        }

        private Layout _layout;
        private UiRules _rules = UiRules.Empty;
        private Camera _bottomCam, _topCam, _hiddenCam;
        private readonly CameraPlacer _cameras = new CameraPlacer();
        private readonly BottomBackground _background = new BottomBackground();
        private readonly Dictionary<Canvas, RootState> _roots = new Dictionary<Canvas, RootState>();
        private readonly Dictionary<Camera, RootState> _cameraRoots = new Dictionary<Camera, RootState>();
        private readonly Dictionary<CanvasScaler, RootState> _byScaler = new Dictionary<CanvasScaler, RootState>();
        private readonly Dictionary<string, ElementState> _elements = new Dictionary<string, ElementState>();
        private readonly List<ElementState> _list = new List<ElementState>();
        private readonly Dictionary<long, RootState> _proxyCanvases = new Dictionary<long, RootState>();
        private readonly HashSet<string> _aimIds = new HashSet<string>();
        private readonly List<Canvas> _deadCanvases = new List<Canvas>();
        private readonly List<Camera> _deadCameras = new List<Camera>();
        private readonly Vector3[] _corners = new Vector3[4];
        private readonly List<DumpEntry> _dump = new List<DumpEntry>();

        public void SetLayout(Layout layout)
        {
            _layout = layout;
            _cameras.SetLayout(layout);
            if (_bottomCam != null)
                _bottomCam.rect = layout.Viewport(layout.Bottom);
            if (_hiddenCam != null)
                _hiddenCam.rect = layout.Viewport(layout.Bottom);
            if (_topCam != null)
                _topCam.rect = layout.Viewport(layout.Top);
            _background.Apply(_layout, _bottomCam);
        }

        /// <summary>New rules from ui.json: applied right away to everything already found.</summary>
        public void SetRules(UiRules rules)
        {
            _rules = rules ?? UiRules.Empty;
            _background.Set(_rules.Background);
            _background.Apply(_layout, _bottomCam);
            _cameras.SetOurs(_bottomCam, _topCam, _hiddenCam, _background.Camera);
            foreach (string id in _aimIds)
                _rules.AddImplicit(id, DuoScreen.Top);
            if (_layout == null || _bottomCam == null)
                return;
            foreach (RootState root in _roots.Values)
            {
                if (root.IsProxy || root.Passive)
                    continue;
                UiRule rule = _rules.Get(root.Id);
                root.Screen = RootScreen(rule);
                root.Scale = rule != null ? rule.Scale : 1f;
                PlaceRoot(root);
            }
            ResolveElements();
        }

        // ---- scans (on new canvases / scenes, plus a slow fallback) -------------------------------------------------

        public void Scan()
        {
            EnsureCameras();
            _cameras.Discover();
            SortBottomCamera();

            foreach (Camera cam in _cameras.GameUiCameras)
            {
                if (cam != null && !_cameraRoots.ContainsKey(cam))
                    RegisterCameraRoot(cam);
            }

            foreach (Canvas canvas in UnityEngine.Object.FindObjectsOfType<Canvas>())
            {
                if (canvas == null || !canvas.isRootCanvas)
                    continue;
                RootState root;
                if (!_roots.TryGetValue(canvas, out root))
                {
                    if (canvas.GetComponent<DuoOwned>() != null)
                        continue;
                    Camera gameUiCamera = GameUiCameraFor(canvas);
                    if (canvas.renderMode == RenderMode.WorldSpace && gameUiCamera == null)
                        continue; // in-world UI (signs, name tags): part of the scene
                    root = Register(canvas, gameUiCamera);
                }
                PlaceRoot(root);
            }

            foreach (var pair in _roots)
            {
                if (pair.Key == null)
                    _deadCanvases.Add(pair.Key);
            }
            foreach (Canvas c in _deadCanvases)
                ForgetCanvas(c);
            _deadCanvases.Clear();
            foreach (var pair in _cameraRoots)
            {
                if (pair.Key == null)
                    _deadCameras.Add(pair.Key);
            }
            foreach (Camera c in _deadCameras)
                _cameraRoots.Remove(c);
            _deadCameras.Clear();

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

        /// <summary>The game's UI cameras draw over the bottom screen: ours clears it first and draws below them.</summary>
        private void SortBottomCamera()
        {
            float min = float.MaxValue;
            foreach (Camera cam in _cameras.GameUiCameras)
            {
                if (cam != null)
                    min = Mathf.Min(min, cam.depth);
            }
            float depth = min == float.MaxValue ? 100f : min - 1f;
            if (_bottomCam.depth != depth)
                _bottomCam.depth = depth;
        }

        private RootState Register(Canvas canvas, Camera gameUiCamera)
        {
            // A screen-space canvas bound to the game's UI camera goes wherever that camera goes.
            bool boundToGameUi = canvas.renderMode == RenderMode.ScreenSpaceCamera && gameUiCamera != null && canvas.worldCamera == gameUiCamera;
            bool passive = canvas.renderMode == RenderMode.WorldSpace || boundToGameUi;
            var root = new RootState
            {
                Canvas = canvas,
                Root = canvas.transform,
                Scaler = canvas.GetComponent<CanvasScaler>(),
                Id = PathOf(canvas.transform),
                OriginalMode = canvas.renderMode,
                OriginalCamera = canvas.worldCamera,
                Passive = passive,
                ScaleForScreen = boundToGameUi,
                Camera = passive ? gameUiCamera : null,
            };
            UiRule rule = _rules.Get(root.Id);
            root.Screen = root.Passive ? DuoScreen.Bottom : RootScreen(rule);
            root.Scale = rule != null && (!root.Passive || root.ScaleForScreen) ? rule.Scale : 1f;
            _roots[canvas] = root;
            if (root.Scaler != null)
                _byScaler[root.Scaler] = root;
            FindAimElements(root);
            Plugin.Log.LogDebug("Canvas '" + root.Id + "' (layer " + canvas.gameObject.layer + ", order " + canvas.sortingOrder + ") -> " +
                (root.Passive ? "stays with the game's UI camera" : root.Screen.ToString()));
            return root;
        }

        private void RegisterCameraRoot(Camera cam)
        {
            var root = new RootState
            {
                Root = cam.transform,
                Id = PathOf(cam.transform),
                Passive = true,
                CameraRoot = true,
                Camera = cam,
                Screen = DuoScreen.Bottom,
            };
            _cameraRoots[cam] = root;
            Plugin.Log.LogInfo("UI under camera '" + root.Id + "' is editable (sprite HUD)");
        }

        private Camera GameUiCameraFor(Canvas canvas)
        {
            if (canvas.renderMode == RenderMode.ScreenSpaceCamera && _cameras.IsGameUiCamera(canvas.worldCamera))
                return canvas.worldCamera;
            if (canvas.renderMode != RenderMode.WorldSpace)
                return null;
            int layer = 1 << canvas.gameObject.layer;
            foreach (Camera cam in _cameras.GameUiCameras)
            {
                if (cam != null && (cam.cullingMask & layer) != 0)
                    return cam;
            }
            return null;
        }

        /// <summary>Crosshairs and reticles are aimed through the game view: unless the user says otherwise they stay on top.</summary>
        private void FindAimElements(RootState root)
        {
            foreach (Transform t in root.Root.GetComponentsInChildren<Transform>(true))
            {
                if (t == root.Root || !UiRules.IsAimElement(t.name) || !(t is RectTransform))
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
            if (c == null || root.Passive || root.IsProxy)
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

        private void ForgetCanvas(Canvas key)
        {
            RootState root = _roots[key];
            _roots.Remove(key);
            if (!ReferenceEquals(root.Scaler, null))
                _byScaler.Remove(root.Scaler);
            List<long> stale = null;
            foreach (var pair in _proxyCanvases)
            {
                if (pair.Value.Source == null)
                    (stale ?? (stale = new List<long>())).Add(pair.Key);
            }
            if (stale == null)
                return;
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
                if (st.T == null || rule == null)
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
                if (found == null)
                    continue;
                var st = new ElementState { Id = pair.Key, T = found, SourceRoot = root, Root = root };
                _elements[pair.Key] = st;
                _list.Add(st);
                Apply(st, pair.Value);
            }
        }

        private void Apply(ElementState st, UiRule rule)
        {
            st.Rule = rule;
            DuoScreen want = rule.Screen;
            // Only uGUI elements can change screens (sprites belong to their camera's world).
            bool wantMove = (want == DuoScreen.Top || want == DuoScreen.Bottom) && want != st.SourceRoot.Screen &&
                st.T is RectTransform && !st.SourceRoot.CameraRoot;
            if (st.Moved && (!wantMove || st.Root.Screen != want))
                Return(st);
            if (wantMove && !st.Moved)
                Move(st, want);
        }

        // ---- moving single uGUI elements to the other screen -----------------------------------------------------

        private void Move(ElementState st, DuoScreen screen)
        {
            var el = (RectTransform)st.T;
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
            if (st.T != null && ph != null)
            {
                st.T.SetParent(ph.transform.parent, false);
                st.T.SetSiblingIndex(ph.transform.GetSiblingIndex());
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
            if (st.T != null && st.HasApplied)
            {
                if (st.T.localScale == st.AppliedScale)
                    st.T.localScale = st.BaseScale;
                if (st.T.localPosition == st.AppliedPos)
                    st.T.localPosition = st.BasePos;
            }
            st.HasApplied = false;
            if (st.Moved)
                Return(st);
        }

        /// <summary>
        /// One canvas per (game canvas, screen) for elements moved there. A game canvas with a CanvasScaler lends its
        /// settings; one sized by the game's own code keeps its measured pixels per unit. Either way a moved element
        /// keeps its size.
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
            if (s == null)
            {
                scaler.uiScaleMode = CanvasScaler.ScaleMode.ConstantPixelSize;
                scaler.scaleFactor = PixelsPerUnit(source);
            }
            else
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
            var root = new RootState { Canvas = canvas, Root = canvas.transform, Scaler = scaler, Screen = screen, Source = src };
            _proxyCanvases[key] = root;
            _byScaler[scaler] = root;
            if (_topCam != null && screen == DuoScreen.Top)
                _topCam.enabled = true;
            return root;
        }

        /// <summary>Screen pixels per canvas unit of a game canvas, as its camera draws it now.</summary>
        private float PixelsPerUnit(RootState root)
        {
            var rt = root.Root as RectTransform;
            Camera cam = CameraOf(root);
            if (rt == null || cam == null || rt.rect.width <= 0f)
                return 1f;
            rt.GetWorldCorners(_corners);
            float pixels = Mathf.Abs(cam.WorldToScreenPoint(_corners[3]).x - cam.WorldToScreenPoint(_corners[0]).x);
            return pixels > 0f ? pixels / rt.rect.width : 1f;
        }

        /// <summary>The proxy covers the same part of its screen as the element's old parent did of the game canvas.</summary>
        private void FitProxy(ElementState st)
        {
            var parent = st.Placeholder.transform.parent as RectTransform;
            var root = st.SourceRoot.Root as RectTransform;
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

        // ---- every frame -----------------------------------------------------------------------------------------

        /// <summary>Cameras back in place if the game moved them. Late in the frame, before rendering.</summary>
        public void ApplyCameras() => _cameras.Apply();

        /// <summary>Changed elements (Canvas.willRenderCanvases, after uGUI's layout).</summary>
        public void ApplyFrame()
        {
            for (int i = _list.Count - 1; i >= 0; i--)
            {
                ElementState st = _list[i];
                if (st.T == null || (st.Moved && st.Placeholder == null))
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
            Transform t = st.T;
            Vector3 scale = t.localScale;
            Vector3 pos = t.localPosition;
            if (!st.HasApplied || scale != st.AppliedScale)
                st.BaseScale = scale;
            if (!st.HasApplied || pos != st.AppliedPos)
                st.BasePos = pos;
            if (!want)
            {
                t.localScale = st.BaseScale;
                t.localPosition = st.BasePos;
                st.HasApplied = false;
                return;
            }
            Vector3 ts = st.BaseScale * (rule.Screen == DuoScreen.Hidden ? 0f : rule.Scale);
            Vector3 tp = st.BasePos + Offset(st);
            if (scale != ts)
                t.localScale = ts;
            if (pos != tp)
                t.localPosition = tp;
            st.AppliedScale = ts;
            st.AppliedPos = tp;
            st.HasApplied = true;
        }

        /// <summary>
        /// The rule's offset (fractions of the screen the element is on) in the element's parent space, through the
        /// element's own camera at the element's depth (orthographic and perspective cameras alike).
        /// </summary>
        private Vector3 Offset(ElementState st)
        {
            UiRule rule = st.Rule;
            if (rule.X == 0f && rule.Y == 0f)
                return Vector3.zero;
            Camera cam = CameraOf(st.Root);
            if (cam == null)
                return Vector3.zero;
            RectInt region = _layout.Region(st.Root.Screen == DuoScreen.Top ? DuoScreen.Top : DuoScreen.Bottom);
            // From the base position, so the offset does not depend on itself.
            Transform parent = st.T.parent;
            Vector3 baseWorld = parent != null ? parent.TransformPoint(st.BasePos) : st.BasePos;
            Vector3 p = cam.WorldToScreenPoint(baseWorld);
            Vector3 world = cam.ScreenToWorldPoint(new Vector3(p.x + rule.X * region.width, p.y - rule.Y * region.height, p.z)) -
                cam.ScreenToWorldPoint(p);
            return parent != null ? parent.InverseTransformVector(world) : world;
        }

        /// <summary>Called from CanvasScaler.SetScaleFactor: lays a canvas out for the screen it is on, times the user's size.</summary>
        public void AdjustScale(CanvasScaler scaler, ref float scaleFactor)
        {
            RootState root;
            if (_layout == null || !_byScaler.TryGetValue(scaler, out root) || (root.Passive && !root.ScaleForScreen))
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

        // ---- our cameras -----------------------------------------------------------------------------------------

        private void EnsureCameras()
        {
            if (_bottomCam == null)
                _bottomCam = NewUiCamera("GameNativeDuo UI camera (bottom)", _layout.Bottom, CameraClearFlags.SolidColor);
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
            _background.Apply(_layout, _bottomCam);
            _cameras.SetOurs(_bottomCam, _topCam, _hiddenCam, _background.Camera);
        }

        private Camera NewUiCamera(string name, RectInt region, CameraClearFlags clear)
        {
            var go = new GameObject(name);
            UnityEngine.Object.DontDestroyOnLoad(go);
            // Far from the game world: our canvases sit in front of these cameras, and nothing of the game's (e.g.
            // world-space UI on the same "UI" layer) may end up in their view, or ours in its cameras' view.
            go.transform.position = new Vector3(-30000f, -30000f, -30000f);
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

        /// <summary>The camera that draws a root: the game's own UI camera for passive roots, else ours.</summary>
        private Camera CameraOf(RootState root) => root.Passive ? root.Camera : CameraFor(root.Screen);

        private static DuoScreen RootScreen(UiRule rule) =>
            rule == null || rule.Screen == DuoScreen.Default ? DuoScreen.Bottom : rule.Screen;

        private bool HasCanvasOn(DuoScreen screen)
        {
            foreach (RootState r in _roots.Values)
            {
                if (!r.Passive && r.Screen == screen && r.Canvas != null)
                    return true;
            }
            foreach (RootState r in _proxyCanvases.Values)
            {
                if (r.Screen == screen && r.Canvas != null)
                    return true;
            }
            return false;
        }

        /// <summary>Puts everything back as the game made it (the window no longer matches the layout).</summary>
        public void RestoreAll()
        {
            foreach (ElementState st in _list)
                Release(st);
            _list.Clear();
            _elements.Clear();
            _cameras.RestoreAll();
            foreach (RootState root in _roots.Values)
            {
                if (root.Canvas == null || root.Passive)
                    continue;
                root.Canvas.renderMode = root.OriginalMode;
                root.Canvas.worldCamera = root.OriginalCamera;
            }
            _roots.Clear();
            _cameraRoots.Clear();
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

        // ---- element ids -------------------------------------------------------------------------------------------

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
                Transform t = FindUnder(r, id);
                if (t != null)
                {
                    root = r;
                    return t;
                }
            }
            foreach (RootState r in _cameraRoots.Values)
            {
                Transform t = FindUnder(r, id);
                if (t != null)
                {
                    root = r;
                    return t;
                }
            }
            root = null;
            return null;
        }

        private static Transform FindUnder(RootState r, string id)
        {
            if (r.IsProxy || !r.Alive)
                return null;
            if (id.Length <= r.Id.Length || id[r.Id.Length] != '/' || !id.StartsWith(r.Id, StringComparison.Ordinal))
                return null;
            return FindPath(r.Root, id.Substring(r.Id.Length + 1));
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
            _dump.Clear();
            foreach (RootState root in _roots.Values)
                WalkRoot(root);
            foreach (RootState root in _cameraRoots.Values)
                WalkRoot(root);
            var sb = new StringBuilder(32 * 1024);
            sb.Append("{\"engine\":\"unity-ugui\",\"screen\":{\"w\":").Append(Screen.width).Append(",\"h\":").Append(Screen.height).Append("},\"elements\":[");
            for (int i = 0; i < _dump.Count; i++)
            {
                DumpEntry e = _dump[i];
                if (i > 0)
                    sb.Append(',');
                sb.Append("{\"id\":");
                MiniJson.Quote(sb, e.Id);
                sb.Append(",\"name\":");
                MiniJson.Quote(sb, e.Name);
                sb.Append(",\"parent\":").Append(e.Parent);
                sb.Append(",\"screen\":\"").Append(UiRule.ScreenId(e.Screen)).Append('"');
                sb.Append(",\"x\":").Append(Mathf.RoundToInt(e.Rect.x));
                sb.Append(",\"y\":").Append(Mathf.RoundToInt(e.Rect.y));
                sb.Append(",\"w\":").Append(Mathf.RoundToInt(e.Rect.width));
                sb.Append(",\"h\":").Append(Mathf.RoundToInt(e.Rect.height));
                sb.Append(",\"graphic\":").Append(e.Graphic ? "true" : "false");
                sb.Append('}');
            }
            sb.Append("]}");
            string tmp = path + ".tmp";
            File.WriteAllText(tmp, sb.ToString());
            if (File.Exists(path))
                File.Delete(path);
            File.Move(tmp, path);
            _dump.Clear();
        }

        private void WalkRoot(RootState root)
        {
            if (root.IsProxy || !root.Alive || !root.Root.gameObject.activeInHierarchy)
                return;
            Walk(root.Root, root.Id, -1, 0, root.Screen, CameraOf(root), root.CameraRoot);
        }

        /// <summary>
        /// Adds <paramref name="t"/> and its children (parents before children); returns their window rectangle, which
        /// for sprite HUDs is the union of the children's (a container sprite has no size of its own).
        /// </summary>
        private Rect Walk(Transform t, string id, int parent, int depth, DuoScreen screen, Camera cam, bool sprites)
        {
            if (_dump.Count >= MaxDumpElements)
                return new Rect();
            DuoPlaceholder ph = t.GetComponent<DuoPlaceholder>();
            if (ph != null)
            {
                ElementState moved;
                if (ph.Target == null || !_elements.TryGetValue(id, out moved))
                    return new Rect();
                t = ph.Target;
                screen = moved.Root.Screen;
                cam = CameraOf(moved.Root);
            }
            UiRule rule = _rules.Get(id);
            if (!t.gameObject.activeInHierarchy && (rule == null || rule.Implicit))
                return new Rect();
            if (rule != null && rule.Screen == DuoScreen.Hidden)
                screen = DuoScreen.Hidden;

            var rt = t as RectTransform;
            bool graphic;
            Rect own;
            if (rt != null)
            {
                own = WindowRect(rt, cam);
                graphic = t.GetComponent<Graphic>() != null;
            }
            else if (sprites)
            {
                Renderer r = t.GetComponent<Renderer>();
                graphic = r != null && r.enabled;
                own = graphic ? WindowRect(r.bounds, cam) : new Rect();
            }
            else
            {
                return new Rect(); // a plain transform inside a canvas: nothing to show
            }

            int index = _dump.Count;
            _dump.Add(new DumpEntry { Id = id, Name = t.name, Parent = parent, Screen = screen, Rect = own, Graphic = graphic });
            Rect union = own;
            if (depth < MaxDumpDepth)
            {
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
                    string childId = id + "/" + (n > 1 ? name + "#" + n.ToString(CultureInfo.InvariantCulture) : name);
                    union = Union(union, Walk(c, childId, index, depth + 1, screen, cam, sprites));
                }
            }
            DumpEntry entry = _dump[index];
            if (sprites)
                entry.Rect = union;
            // Zero-sized holders are common in uGUI (anchor points): keep them only when something refers to them.
            bool keep = depth == 0 || (entry.Rect.width >= 2f && entry.Rect.height >= 2f) || (rule != null && !rule.Implicit) ||
                screen == DuoScreen.Hidden;
            if (keep)
            {
                _dump[index] = entry;
                return union;
            }
            // Drop it; its children point at this entry's parent instead, and later indices shift down by one.
            for (int i = index + 1; i < _dump.Count; i++)
            {
                DumpEntry c = _dump[i];
                if (c.Parent == index)
                    c.Parent = parent;
                else if (c.Parent > index)
                    c.Parent--;
                _dump[i] = c;
            }
            _dump.RemoveAt(index);
            return union;
        }

        private static Rect Union(Rect a, Rect b)
        {
            if (a.width <= 0f || a.height <= 0f)
                return b;
            if (b.width <= 0f || b.height <= 0f)
                return a;
            return Rect.MinMaxRect(Mathf.Min(a.xMin, b.xMin), Mathf.Min(a.yMin, b.yMin), Mathf.Max(a.xMax, b.xMax), Mathf.Max(a.yMax, b.yMax));
        }

        /// <summary>An element's bounding box in window pixels (origin top-left).</summary>
        private Rect WindowRect(RectTransform rt, Camera cam)
        {
            if (cam == null)
                return new Rect();
            rt.GetWorldCorners(_corners);
            return Project(cam);
        }

        private Rect WindowRect(Bounds b, Camera cam)
        {
            if (cam == null)
                return new Rect();
            Vector3 min = b.min, max = b.max;
            _corners[0] = new Vector3(min.x, min.y, min.z);
            _corners[1] = new Vector3(max.x, max.y, min.z);
            _corners[2] = new Vector3(min.x, max.y, max.z);
            _corners[3] = new Vector3(max.x, min.y, max.z);
            return Project(cam);
        }

        private Rect Project(Camera cam)
        {
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
