using System;
using System.Collections.Generic;
using GameNativeDuo.UnitySplit;
using UnityEngine;

namespace GameNativeDuo.HollowKnight
{
    /// <summary>
    /// The game's own map, live on the bottom screen while you play, under the masks, soul and geo.
    ///
    /// Hollow Knight draws its map (GameManager.gameMap, "Game_Map(Clone)" under the HUD camera) only while the
    /// quick map is held, which stops the Knight, or in the inventory. Here a camera of ours draws the same objects
    /// into the free part of the bottom screen all the time. The map shows exactly what the inventory map would
    /// (GameMap.SetupMap + WorldMap: mapped rooms, pins, markers), and the Knight's position only with the
    /// Wayward Compass equipped, as in the game.
    ///
    /// While ours is shown, the map's objects sit on a layer no game camera draws, so the HUD camera does not draw
    /// them over the HUD; whenever the game itself uses the map (inventory, vanilla quick map) they go back to the
    /// UI layer and ours steps aside.
    ///
    /// The map button switches the zoom (Knight / area / whole map) instead of opening the quick map, so the Knight
    /// keeps moving. Touch: drag to look around, pinch to zoom, double-tap to switch the zoom; the view drifts back
    /// to the Knight a few seconds after the last touch.
    /// </summary>
    internal sealed class LiveMap : MonoBehaviour
    {
        internal enum Zoom
        {
            Knight,
            Area,
            World,
        }

        private const int UiLayer = 5;
        private const float CameraDepth = 99.5f; // after the bottom screen's clear, before the HUD camera (100); see PlaceCamera
        private const float FollowRate = 8f;
        private const float RecenterSeconds = 4f;
        private const float DoubleTapSeconds = 0.35f;
        private const float TapSlopPx = 24f;
        private const float Padding = 1.08f;

        internal static LiveMap Instance;

        /// <summary>
        /// True while the map button is ours (switches the zoom) rather than the game's quick map (the Knight's and the
        /// benches' listeners). Not tied to the map being drawn this frame: it steps aside while the inventory is open
        /// or closing and while a room loads, and a press then must not open the game's quick map either. The
        /// inventory's own map pane keeps the button (<see cref="InInventory"/>).
        /// </summary>
        internal static bool OwnsMapButton
        {
            get
            {
                LiveMap map = Instance;
                if (map == null || !map.enabled || !Settings.Enabled || !Settings.MapButtonZooms)
                    return false;
                Split split = Split.Instance;
                if (split == null || !split.Active)
                    return false;
                GameManager gm = GameManager.instance;
                return gm != null && gm.gameMap != null;
            }
        }

        /// <summary>True for objects of the inventory (its map pane zooms out with the map button).</summary>
        internal static bool InInventory(GameObject go)
        {
            InventoryIsOpen(); // finds the inventory once per scene
            return go != null && _inventory != null && go.transform.IsChildOf(_inventory);
        }

        internal Zoom CurrentZoom { get; private set; } = Zoom.Knight;
        internal bool Shown => _shown;
        internal int CullingMask => _cam != null ? _cam.cullingMask : 0;

        private Camera _cam;
        private int _layer = -1;
        private bool _shown;
        private bool _onOurLayer;
        private GameMap _map;
        private static Transform _inventory;
        private static readonly List<GameObject> _inventoryPanes = new List<GameObject>();
        private bool _contentDirty = true;
        private bool _snap = true;
        private Bounds _areaBounds, _worldBounds;
        private bool _hasArea, _hasWorld;
        private Transform _boundsArea;
        private Vector2 _panOffset;
        private float _zoomFactor = 1f;
        private float _lastTouch = -100f;
        private float _nextLayerCheck;

        private readonly Dictionary<int, Vector2> _fingers = new Dictionary<int, Vector2>();
        private Vector2 _downPos;
        private float _downTime, _lastTapTime = -100f;
        private bool _moved;
        private float _pinchStart, _pinchZoomStart;

        private void Awake()
        {
            Instance = this;
            Split.TouchFilter = OnTouch;
            UiRouter.PluginElements.Add(DescribeElement);
            // The map's rooms and pins sit under the HUD camera like HUD sprites: they are the live map, not UI to move.
            UiRouter.NotInEditor.Add("_GameCameras/HudCamera/Game_Map(Clone)");
            UnityEngine.SceneManagement.SceneManager.activeSceneChanged += (a, b) => OnSceneChanged();
        }

        private void OnDestroy()
        {
            if (ReferenceEquals(Split.TouchFilter, (Func<byte, int, float, float, bool>)OnTouch))
                Split.TouchFilter = null;
            UiRouter.PluginElements.Remove(DescribeElement);
            Hide();
        }

        /// <summary>The live map as an element of the UI layout editors: where it is (or would be), and its options.</summary>
        private UiRouter.PluginElement DescribeElement()
        {
            Split split = Split.Instance;
            if (split == null || !split.Active || split.Layout == null)
                return null;
            RectInt r = MapRegion(split.Layout);
            return new UiRouter.PluginElement
            {
                Id = Settings.ElementId,
                Name = "Live map",
                Screen = Settings.Enabled ? DuoScreen.Bottom : DuoScreen.Hidden,
                Rect = new Rect(r.x, r.y, r.width, r.height),
                ExtraJson = Settings.OptionsJson,
            };
        }

        /// <summary>
        /// The map's rectangle in window pixels: by default the bottom screen below the HUD strip; the user's layout
        /// scales it about its centre and shifts it by fractions of the bottom screen, and it always stays on that screen.
        /// </summary>
        internal static RectInt MapRegion(Layout layout)
        {
            RectInt b = layout.Bottom;
            int reserve = Mathf.Clamp(Settings.HudReservePx, 0, b.height / 2);
            float w = Mathf.Min(b.width, b.width * Settings.Scale);
            float h = Mathf.Min(b.height, (b.height - reserve) * Settings.Scale);
            float cx = b.x + b.width * 0.5f + Settings.X * b.width;
            float cy = b.y + reserve + (b.height - reserve) * 0.5f + Settings.Y * b.height;
            float x = Mathf.Clamp(cx - w * 0.5f, b.x, b.xMax - w);
            float y = Mathf.Clamp(cy - h * 0.5f, b.y, b.yMax - h);
            return new RectInt(Mathf.RoundToInt(x), Mathf.RoundToInt(y), Mathf.Max(1, Mathf.RoundToInt(w)), Mathf.Max(1, Mathf.RoundToInt(h)));
        }

        private void OnSceneChanged()
        {
            _contentDirty = true;
            _inventory = null;
            _inventoryPanes.Clear();
            _dialogueRoot = null;
            _shopPrompts.Clear();
            _shopScanAt = Time.unscaledTime + 1f;
        }

        private void LateUpdate()
        {
            long t0 = System.Diagnostics.Stopwatch.GetTimestamp();
            try
            {
                if (Settings.Refresh())
                {
                    // The user changed the layout: start from the chosen zoom, at the Knight, with the map set up again
                    // (the Knight icon rule may have changed).
                    SetZoom(Settings.StartZoom);
                    _snap = true;
                    _contentDirty = true;
                }
                bool want = ShouldShow();
                if (want && !_shown)
                    Show();
                else if (!want && _shown)
                    Hide();
                if (!_shown)
                    return;
                // Not under the pause menu or a dialogue: the map is dimmed out of sight there, as the game ignores the button.
                if (Settings.MapButtonZooms && InputHandler.Instance != null && InputHandler.Instance.inputActions.quickMap.WasPressed && !ShouldDim())
                    CycleZoom();
                if (_contentDirty)
                    RefreshContent();
                if (Time.unscaledTime >= _nextLayerCheck)
                {
                    // Pins and markers the game turns on later come with their own layer: bring them over to ours.
                    _nextLayerCheck = Time.unscaledTime + 1f;
                    MoveMapToOurLayer();
                }
                ShowKnightAlways();
                PlaceCamera();
                _lastShownFrame = Time.frameCount;
            }
            catch (Exception ex)
            {
                Plugin.Log.LogError("Live map update failed: " + ex);
                Hide();
                enabled = false;
            }
            finally
            {
                // Counted in the split's per-frame cost ("this plugin ... ms per frame" in the Frames log line).
                Split.OwnTicks += System.Diagnostics.Stopwatch.GetTimestamp() - t0;
            }
        }

        // ------------------------------------------------------------------ when

        /// <summary>Why the map is not shown (logged when it hides).</summary>
        private string _why = "";

        private bool ShouldShow()
        {
            if (!Settings.Enabled)
                return No("turned off");
            Split split = Split.Instance;
            if (split == null || !split.Active || split.Layout == null)
                return No("split off");
            GameManager gm = GameManager.instance;
            if (gm == null || gm.gameMap == null || HeroController.instance == null || !gm.IsGameplayScene())
                return No("not in gameplay");
            // Paused: the map stays, dimmed under the pause menu (see ShouldDim).
            if (gm.gameState == GlobalEnums.GameState.CUTSCENE)
                return No("cutscene");
            if (_map == null || _map.gameObject != gm.gameMap)
            {
                _map = gm.gameMap.GetComponent<GameMap>();
                _contentDirty = true;
            }
            if (_map == null || !_map.gameObject.activeInHierarchy)
                return No("game map inactive");
            // The game's own map is open (vanilla quick map, when the button is left to the game).
            if (_map.displayNextArea)
                return No("game's quick map open");
            if (InventoryIsOpen())
                return No("inventory open");
            return true;
        }

        private bool No(string why)
        {
            _why = why;
            return false;
        }

        /// <summary>True while one of the inventory's panes (inventory, charms, journal, map) is open.</summary>
        internal static bool InventoryIsOpen()
        {
            if (_inventory == null)
            {
                GameCameras cams = GameCameras.instance;
                _inventory = cams != null && cams.hudCamera != null ? cams.hudCamera.transform.Find("Inventory") : null;
                _inventoryPanes.Clear();
                if (_inventory != null)
                {
                    foreach (string pane in new[] { "Inv", "Charms", "Journal", "Map" })
                    {
                        Transform t = _inventory.Find(pane);
                        if (t != null)
                            _inventoryPanes.Add(t.gameObject);
                    }
                }
            }
            for (int i = 0; i < _inventoryPanes.Count; i++)
            {
                if (_inventoryPanes[i] != null && _inventoryPanes[i].activeSelf)
                    return true;
            }
            return false;
        }

        // ------------------------------------------------------------------ show / hide

        private void Show()
        {
            _layer = FreeLayer();
            if (_layer < 0)
            {
                Plugin.Log.LogWarning("Live map: every layer is drawn by a game camera; leaving the map to the game");
                return;
            }
            if (_cam == null)
            {
                var go = new GameObject("GameNativeDuo map camera");
                go.hideFlags = HideFlags.HideAndDontSave;
                DontDestroyOnLoad(go);
                _cam = go.AddComponent<Camera>();
                _cam.orthographic = true;
                _cam.clearFlags = CameraClearFlags.Depth; // over the bottom screen's background
                _cam.nearClipPlane = 0.1f;
                _cam.farClipPlane = 500f;
                _cam.depth = CameraDepth;
                _cam.allowHDR = false;
                _cam.allowMSAA = false;
                _cam.useOcclusionCulling = false;
                CameraPlacer.PluginCameras.Add(_cam);
                _fade = MakeGradient(go.transform, "GameNativeDuo map fade", short.MaxValue - 1);
                _dim = MakeGradient(go.transform, "GameNativeDuo map dim", short.MaxValue);

                var clearGo = new GameObject("GameNativeDuo map clear camera");
                clearGo.hideFlags = HideFlags.HideAndDontSave;
                DontDestroyOnLoad(clearGo);
                _clearCam = clearGo.AddComponent<Camera>();
                _clearCam.cullingMask = 0;
                _clearCam.clearFlags = CameraClearFlags.SolidColor;
                _clearCam.allowHDR = false;
                _clearCam.allowMSAA = false;
                _clearCam.enabled = false;
                CameraPlacer.PluginCameras.Add(_clearCam);
            }
            _fade.layer = _layer;
            _dim.layer = _layer;
            _dimAlpha = 0f;
            _cam.cullingMask = 1 << _layer;
            _cam.enabled = true;
            _shown = true;
            _snap = true;
            _contentDirty = true;
            Plugin.Log.LogInfo("Live map on (layer " + _layer + ")");
        }

        private void Hide()
        {
            if (_cam != null)
                _cam.enabled = false;
            ReleaseBottomClear();
            if (_onOurLayer)
                RestoreGameLayers();
            if (_shown)
            {
                Plugin.Log.LogInfo("Live map off (" + _why + ")");
                HandMapBack();
            }
            _shown = false;
            _fingers.Clear();
            _contentDirty = true;
        }

        /// <summary>True while the live map itself calls into GameMap (so <see cref="GameOpensMapPatch"/> ignores it).</summary>
        internal static bool OurCall;

        /// <summary>The last frame in which the game itself opened its map (inventory map, quick map).</summary>
        internal static int GameOpenedMapFrame = -1;

        private int _lastShownFrame;

        /// <summary>
        /// The game expects its map switched off when it is not using it (its CloseQuickMap does that when its own map
        /// closes): the inventory's zoomed-out map pane otherwise shows the whole detailed map behind its area overview.
        /// So the live map switches it off the same way when it steps aside, unless the game has just opened the map
        /// itself (zoomed-in inventory map, quick map), which it then would wipe.
        /// </summary>
        private void HandMapBack()
        {
            if (_map == null || GameOpenedMapFrame >= _lastShownFrame)
                return;
            OurCall = true;
            try
            {
                _map.CloseQuickMap();
            }
            finally
            {
                OurCall = false;
            }
        }

        /// <summary>A layer no game camera draws (built-in unnamed layers last), so only our camera sees the map.</summary>
        private int FreeLayer()
        {
            int used = 0;
            foreach (Camera c in Camera.allCameras)
            {
                if (c != _cam && c.targetTexture == null)
                    used |= c.cullingMask;
            }
            for (int l = 31; l >= 8; l--)
            {
                if ((used & (1 << l)) == 0 && LayerMask.LayerToName(l).Length == 0)
                    return l;
            }
            foreach (int l in new[] { 7, 6, 3 })
            {
                if ((used & (1 << l)) == 0)
                    return l;
            }
            return -1;
        }

        /// <summary>The game's own layer of each map object on ours, given back when the game uses the map again.</summary>
        private readonly Dictionary<GameObject, int> _gameLayers = new Dictionary<GameObject, int>();
        private bool _layersLogged;

        /// <summary>
        /// Moves the map's objects that the game's HUD camera draws onto our layer. The game hides some map objects by
        /// layer (one its cameras never draw): those stay where they are, or the live map would show what the game's
        /// own map does not. Objects the game turns on or moves later are picked up by the next call.
        /// </summary>
        private void MoveMapToOurLayer()
        {
            if (_map == null)
                return;
            GameCameras cams = GameCameras.instance;
            int drawn = cams != null && cams.hudCamera != null ? cams.hudCamera.cullingMask : 1 << UiLayer;
            if (TestHarness.On && !_layersLogged)
            {
                _layersLogged = true;
                var s = new System.Text.StringBuilder("Game map objects the HUD camera does not draw (mask 0x" + drawn.ToString("x") + "):");
                foreach (Transform t in _map.GetComponentsInChildren<Transform>(true))
                {
                    if ((drawn & (1 << t.gameObject.layer)) == 0)
                        s.Append("\n  layer ").Append(t.gameObject.layer).Append(' ').Append(Diagnostics.Path(t));
                }
                Plugin.Log.LogInfo(s.ToString());
            }
            MoveRecursive(_map.transform, drawn);
            _onOurLayer = true;
        }

        private void MoveRecursive(Transform t, int drawn)
        {
            GameObject go = t.gameObject;
            int layer = go.layer;
            if (layer != _layer && (drawn & (1 << layer)) != 0)
            {
                _gameLayers[go] = layer;
                go.layer = _layer;
            }
            for (int i = 0; i < t.childCount; i++)
                MoveRecursive(t.GetChild(i), drawn);
        }

        private void RestoreGameLayers()
        {
            foreach (KeyValuePair<GameObject, int> kv in _gameLayers)
            {
                if (kv.Key != null && kv.Key.layer == _layer)
                    kv.Key.layer = kv.Value;
            }
            _gameLayers.Clear();
            _onOurLayer = false;
        }

        // ------------------------------------------------------------------ content

        /// <summary>What the inventory map shows: the mapped rooms and pins (SetupMap), every mapped area and the compass (WorldMap).</summary>
        private void RefreshContent()
        {
            _contentDirty = false;
            GameManager gm = GameManager.instance;
            if (gm == null || !gm.IsGameplayScene())
                return;
            OurCall = true;
            try
            {
                _map.SetupMap();
                _map.WorldMap();
            }
            finally
            {
                OurCall = false;
            }
            MoveMapToOurLayer();
            _hasWorld = WorldBounds(ref _worldBounds);
            _boundsArea = null;
            _snap = true;
        }

        private float _nextCompass;

        /// <summary>
        /// Option "Knight on the map: Always" (off by default, the game shows the Knight only with the Wayward Compass):
        /// GameMap.PositionCompass always works out where the Knight is and only then hides the icon without the charm, so
        /// the live map asks it a few times a second and keeps the icon on. Not in dream areas, White Palace or Godhome
        /// (the game has no position there). Only while the live map is shown: handing the map back resets the icon.
        /// </summary>
        private void ShowKnightAlways()
        {
            if (!Settings.KnightAlways || _map == null || _map.compassIcon == null || Time.unscaledTime < _nextCompass)
                return;
            _nextCompass = Time.unscaledTime + 0.25f;
            if (PlayerData.instance == null || PlayerData.instance.equippedCharm_2)
                return; // the game shows it itself
            string zone = GameManager.instance != null ? GameManager.instance.GetCurrentMapZone() : "";
            if (zone == "DREAM_WORLD" || zone == "WHITE_PALACE" || zone == "GODS_GLORY")
                return;
            OurCall = true;
            try
            {
                _map.PositionCompass(false);
            }
            finally
            {
                OurCall = false;
            }
            if (!_map.compassIcon.activeSelf)
                _map.compassIcon.SetActive(true);
        }

        /// <summary>The map's areas, as GameMap knows them (its other children are pins and markers).</summary>
        private GameObject[] Areas() => new[]
        {
            _map.areaAncientBasin, _map.areaCity, _map.areaCliffs, _map.areaCrossroads, _map.areaCrystalPeak, _map.areaDeepnest,
            _map.areaFogCanyon, _map.areaFungalWastes, _map.areaGreenpath, _map.areaKingdomsEdge, _map.areaQueensGardens,
            _map.areaRestingGrounds, _map.areaDirtmouth, _map.areaWaterways,
        };

        /// <summary>Every area the map shows now.</summary>
        private bool WorldBounds(ref Bounds bounds)
        {
            bool any = false;
            foreach (GameObject area in Areas())
            {
                Bounds b = default(Bounds);
                if (area == null || !area.activeInHierarchy || !AreaBounds(area.transform, ref b))
                    continue;
                if (!any)
                    bounds = b;
                else
                    bounds.Encapsulate(b);
                any = true;
            }
            return any;
        }

        /// <summary>The area the Knight is in (the parent of the room GameMap.PositionCompass found), when it is on the map.</summary>
        private Transform CurrentArea()
        {
            GameObject room = _map.currentScene;
            if (room == null || !room.activeInHierarchy || room.transform.parent == null)
                return null;
            Transform area = room.transform.parent;
            return Array.IndexOf(Areas(), area.gameObject) >= 0 ? area : null;
        }

        // ------------------------------------------------------------------ camera

        private void PlaceCamera()
        {
            Layout layout = Split.Instance.Layout;
            RectInt b = layout.Bottom;
            RectInt region = MapRegion(layout);
            Rect viewport = layout.Viewport(region);
            if (_cam.rect != viewport)
                _cam.rect = viewport;
            OrderCameras(layout.Viewport(b));
            float aspect = (float)region.width / Mathf.Max(1, region.height);

            Vector3 focus;
            float size;
            if (!Frame(aspect, out focus, out size))
                return;
            float worldPerPx = 2f * size / Mathf.Max(1, region.height);
            if (Settings.DriftBack && Time.unscaledTime - _lastTouch > RecenterSeconds && _fingers.Count == 0)
            {
                float k = 1f - Mathf.Exp(-Time.unscaledDeltaTime * 3f);
                _panOffset = Vector2.Lerp(_panOffset, Vector2.zero, k);
                if (_panOffset.sqrMagnitude < 1e-6f)
                    _panOffset = Vector2.zero;
            }
            size *= _zoomFactor;
            var target = new Vector3(focus.x + _panOffset.x, focus.y + _panOffset.y, focus.z - 100f);
            if (_snap)
            {
                _snap = false;
                _cam.transform.position = target;
                _cam.orthographicSize = size;
            }
            else
            {
                float k = 1f - Mathf.Exp(-Time.unscaledDeltaTime * FollowRate);
                _cam.transform.position = Vector3.Lerp(_cam.transform.position, target, k);
                _cam.orthographicSize = Mathf.Lerp(_cam.orthographicSize, size, k);
            }
            _worldPerPx = worldPerPx * _zoomFactor;
            PlaceOverlays();
        }

        // ------------------------------------------------------------------ draw order

        private Camera _clearCam;
        private Camera _takenBottom;
        private Color _clearColor = Color.black;

        /// <summary>
        /// The map goes under everything else on the bottom screen: the pause menu and options (canvases on our bottom
        /// UI camera) and the HUD and dialogue (the game's HUD camera) draw over it. So the bottom UI camera stops
        /// clearing; a camera of ours clears the bottom screen in its colour first, then the map draws. With an image
        /// background the bottom UI camera already clears nothing and the background camera clears.
        /// </summary>
        private void OrderCameras(Rect bottomViewport)
        {
            Camera bottom = Split.Instance.Router.BottomCamera;
            if (bottom == null)
            {
                GameCameras cams = GameCameras.instance;
                if (cams != null && cams.hudCamera != null && _cam.depth != cams.hudCamera.depth - 0.5f)
                    _cam.depth = cams.hudCamera.depth - 0.5f;
                return;
            }
            if (bottom.clearFlags == CameraClearFlags.SolidColor)
            {
                // Ours to clear (again, if a new background reset it).
                _takenBottom = bottom;
                _clearColor = bottom.backgroundColor;
                bottom.clearFlags = CameraClearFlags.Depth;
            }
            bool clearing = _takenBottom == bottom;
            if (_clearCam.enabled != clearing)
                _clearCam.enabled = clearing;
            if (clearing)
            {
                if (_clearCam.backgroundColor != _clearColor)
                    _clearCam.backgroundColor = _clearColor;
                if (_clearCam.rect != bottomViewport)
                    _clearCam.rect = bottomViewport;
                if (_clearCam.depth != bottom.depth - 0.2f)
                    _clearCam.depth = bottom.depth - 0.2f;
            }
            if (_cam.depth != bottom.depth - 0.1f)
                _cam.depth = bottom.depth - 0.1f;
        }

        private void ReleaseBottomClear()
        {
            if (_takenBottom != null && _takenBottom.clearFlags == CameraClearFlags.Depth)
                _takenBottom.clearFlags = CameraClearFlags.SolidColor;
            _takenBottom = null;
            if (_clearCam != null)
                _clearCam.enabled = false;
        }

        // ------------------------------------------------------------------ dimming

        private GameObject _dim;
        private float _dimAlpha;
        private static DialogueBox[] _dialogueBoxes;
        private static Transform _dialogueRoot;

        /// <summary>While a menu or a dialogue sits over the map: pause, options, NPC conversations.</summary>
        private static bool ShouldDim()
        {
            GameManager gm = GameManager.instance;
            if (gm != null && (gm.isPaused || gm.gameState == GlobalEnums.GameState.PAUSED))
                return true;
            UIManager ui = UIManager.instance;
            if (ui != null && (ui.uiState == GlobalEnums.UIState.PAUSED || ui.uiState == GlobalEnums.UIState.OPTIONS))
                return true;
            return DialogueShown() || ShopShown();
        }

        private static readonly List<GameObject> _shopPrompts = new List<GameObject>();
        private static float _shopScanAt;

        /// <summary>
        /// A shop's menu is up (Sly, Iselda, Salubra, ...): its "Action Cancel" prompt shows exactly while the item list
        /// or the purchase confirmation is open. The shop menus are looked up once per room, a moment after it loads.
        /// </summary>
        private static bool ShopShown()
        {
            if (_shopScanAt > 0f && Time.unscaledTime >= _shopScanAt)
            {
                _shopScanAt = 0f;
                _shopPrompts.Clear();
                foreach (ShopMenuStock stock in FindObjectsOfType<ShopMenuStock>())
                {
                    Transform prompt = stock.transform.Find("Action Cancel");
                    if (prompt != null)
                        _shopPrompts.Add(prompt.gameObject);
                }
            }
            for (int i = 0; i < _shopPrompts.Count; i++)
            {
                if (_shopPrompts[i] != null && _shopPrompts[i].activeInHierarchy)
                    return true;
            }
            return false;
        }

        private static readonly HarmonyLib.AccessTools.FieldRef<DialogueBox, bool> DialogueHidden =
            HarmonyLib.AccessTools.FieldRefAccess<DialogueBox, bool>("hidden");

        /// <summary>A conversation's text is up (DialogueBox shows a page until HideText).</summary>
        private static bool DialogueShown()
        {
            GameCameras cams = GameCameras.instance;
            if (cams == null || cams.hudCamera == null)
                return false;
            if (_dialogueRoot == null)
            {
                _dialogueRoot = cams.hudCamera.transform.Find("DialogueManager");
                _dialogueBoxes = _dialogueRoot != null ? _dialogueRoot.GetComponentsInChildren<DialogueBox>(true) : new DialogueBox[0];
            }
            foreach (DialogueBox box in _dialogueBoxes)
            {
                if (box != null && box.gameObject.activeInHierarchy && !DialogueHidden(box))
                    return true;
            }
            return false;
        }

        private float _worldPerPx = 0.01f;
        private GameObject _fade;
        private const float FadeFraction = 0.12f;

        /// <summary>
        /// A sprite over the map, drawn last by our camera: the soft edge where the map meets the HUD strip (a gradient,
        /// clear at the bottom) and the dim layer (solid). One 1x32 texture, white: the renderer's colour tints it.
        /// </summary>
        private static GameObject MakeGradient(Transform cameraTransform, string name, int order)
        {
            if (_gradient == null)
            {
                var tex = new Texture2D(1, 32, TextureFormat.RGBA32, false) { wrapMode = TextureWrapMode.Clamp, filterMode = FilterMode.Bilinear };
                for (int y = 0; y < 32; y++)
                    tex.SetPixel(0, y, new Color(1f, 1f, 1f, Mathf.SmoothStep(0f, 1f, y / 31f)));
                tex.Apply(false, true);
                _gradient = Sprite.Create(tex, new Rect(0, 0, 1, 32), new Vector2(0.5f, 1f), 32f);
                var solid = new Texture2D(1, 1, TextureFormat.RGBA32, false);
                solid.SetPixel(0, 0, Color.white);
                solid.Apply(false, true);
                _solid = Sprite.Create(solid, new Rect(0, 0, 1, 1), new Vector2(0.5f, 0.5f), 1f);
            }
            var go = new GameObject(name);
            go.hideFlags = HideFlags.HideAndDontSave;
            go.transform.SetParent(cameraTransform, false);
            var r = go.AddComponent<SpriteRenderer>();
            r.sprite = order == short.MaxValue ? _solid : _gradient;
            SortingLayer[] layers = SortingLayer.layers;
            if (layers.Length > 0)
                r.sortingLayerID = layers[layers.Length - 1].id;
            r.sortingOrder = order;
            return go;
        }

        private static Sprite _gradient, _solid;

        /// <summary>Keeps the edge fade across the top of the view and the dim layer over all of it; fades the dim in and out.</summary>
        private void PlaceOverlays()
        {
            float h = _cam.orthographicSize * 2f;
            float w = h * _cam.aspect;
            var edge = _takenBottom != null ? _clearColor : Color.black;
            edge.a = 1f;
            _fade.transform.localPosition = new Vector3(0f, _cam.orthographicSize, 1f);
            _fade.transform.localScale = new Vector3(w * 32f, h * FadeFraction, 1f); // the sprite is 1/32 x 1 units
            var fr = _fade.GetComponent<SpriteRenderer>();
            if (fr.color != edge)
                fr.color = edge;

            float target = ShouldDim() ? Settings.Dim : 0f;
            _dimAlpha = Mathf.MoveTowards(_dimAlpha, target, Time.unscaledDeltaTime * 4f);
            bool on = _dimAlpha > 0.001f;
            var dr = _dim.GetComponent<SpriteRenderer>();
            if (dr.enabled != on)
                dr.enabled = on;
            if (on)
            {
                _dim.transform.localPosition = new Vector3(0f, 0f, 1f);
                _dim.transform.localScale = new Vector3(w * 1.02f, h * 1.02f, 1f);
                dr.color = new Color(0f, 0f, 0f, _dimAlpha);
            }
        }

        /// <summary>Where to look and how much to show for the current zoom.</summary>
        private bool Frame(float aspect, out Vector3 focus, out float size)
        {
            focus = Vector3.zero;
            size = 1f;
            Zoom zoom = EffectiveZoom();
            if (zoom == Zoom.Knight)
            {
                focus = _map.compassIcon.transform.position;
                size = Settings.KnightZoomSize * _map.transform.lossyScale.y;
                return true;
            }
            if (zoom == Zoom.Area)
            {
                Transform area = CurrentArea();
                if (area != _boundsArea)
                {
                    _boundsArea = area;
                    _hasArea = area != null && AreaBounds(area, ref _areaBounds);
                }
                if (_hasArea)
                {
                    FitBounds(_areaBounds, aspect, out focus, out size);
                    return true;
                }
            }
            if (!_hasWorld)
                return false;
            FitBounds(_worldBounds, aspect, out focus, out size);
            return true;
        }

        private static bool AreaBounds(Transform area, ref Bounds bounds)
        {
            bool any = false;
            for (int i = 0; i < area.childCount; i++)
            {
                Transform room = area.GetChild(i);
                if (!room.gameObject.activeInHierarchy)
                    continue;
                var r = room.GetComponent<SpriteRenderer>();
                if (r == null || r.sprite == null || !r.enabled)
                    continue;
                if (!any)
                {
                    bounds = r.bounds;
                    any = true;
                }
                else
                {
                    bounds.Encapsulate(r.bounds);
                }
            }
            return any;
        }

        /// <summary>Debug: each area's and the far rooms' bounds.</summary>
        internal void LogBounds()
        {
            if (_map == null)
                return;
            Plugin.Log.LogInfo("World bounds " + _worldBounds + " (map at " + _map.transform.position + ", scale " + _map.transform.lossyScale + ")");
            for (int i = 0; i < _map.transform.childCount; i++)
            {
                Transform area = _map.transform.GetChild(i);
                Bounds b = default(Bounds);
                if (area.gameObject.activeInHierarchy && AreaBounds(area, ref b))
                    Plugin.Log.LogInfo("  area " + area.name + ": " + b);
                foreach (SpriteRenderer r in area.GetComponentsInChildren<SpriteRenderer>(false))
                {
                    if (r.transform.parent == area && (r.bounds.center - _map.transform.position).magnitude > 40f)
                        Plugin.Log.LogInfo("    far room " + area.name + "/" + r.name + ": " + r.bounds);
                }
            }
        }

        private static void FitBounds(Bounds b, float aspect, out Vector3 focus, out float size)
        {
            focus = b.center;
            size = Mathf.Max(b.extents.y, b.extents.x / Mathf.Max(0.1f, aspect)) * Padding;
        }

        /// <summary>Following the Knight needs the Wayward Compass, as on the game's own map.</summary>
        private Zoom EffectiveZoom()
        {
            bool compass = _map.compassIcon != null && _map.compassIcon.activeSelf;
            if (CurrentZoom == Zoom.Knight && !compass)
                return Zoom.Area;
            return CurrentZoom;
        }

        /// <summary>Re-reads what the map shows (after the player's map data changed).</summary>
        internal void MarkDirty() => _contentDirty = true;

        internal void SetZoom(Zoom zoom)
        {
            CurrentZoom = zoom;
            _panOffset = Vector2.zero;
            _zoomFactor = 1f;
            Plugin.Log.LogInfo("Live map zoom: " + CurrentZoom);
        }

        internal void CycleZoom()
        {
            bool compass = _map != null && _map.compassIcon != null && _map.compassIcon.activeSelf;
            switch (EffectiveZoom())
            {
                case Zoom.Knight:
                    CurrentZoom = Zoom.Area;
                    break;
                case Zoom.Area:
                    CurrentZoom = Zoom.World;
                    break;
                default:
                    CurrentZoom = compass ? Zoom.Knight : Zoom.Area;
                    break;
            }
            _panOffset = Vector2.zero;
            _zoomFactor = 1f;
            Plugin.Log.LogInfo("Live map zoom: " + CurrentZoom);
        }

        // ------------------------------------------------------------------ touch

        /// <summary>Bottom-screen touches on the map: drag pans, pinch zooms, double-tap switches the zoom.</summary>
        private bool OnTouch(byte action, int pointer, float x, float y)
        {
            if (!_shown || _cam == null)
                return false;
            if (ShouldDim())
            {
                // A menu or a dialogue is over the map: touches are for it (pause menu, options, dialogue choices).
                _fingers.Clear();
                return false;
            }
            RectInt r = MapRegion(Split.Instance.Layout);
            var pos = new Vector2(x, y);
            bool inside = x >= r.x && x < r.xMax && y >= r.y && y < r.yMax;
            switch (action)
            {
                case Bridge.TouchDown:
                    if (!inside)
                        return false;
                    _fingers[pointer] = pos;
                    _lastTouch = Time.unscaledTime;
                    if (_fingers.Count == 1)
                    {
                        _downPos = pos;
                        _downTime = Time.unscaledTime;
                        _moved = false;
                    }
                    else if (_fingers.Count == 2)
                    {
                        _pinchStart = PinchDistance();
                        _pinchZoomStart = _zoomFactor;
                        _moved = true;
                    }
                    return true;
                case Bridge.TouchMove:
                {
                    Vector2 last;
                    if (!_fingers.TryGetValue(pointer, out last))
                        return false;
                    _fingers[pointer] = pos;
                    _lastTouch = Time.unscaledTime;
                    if (_fingers.Count >= 2)
                    {
                        float d = PinchDistance();
                        if (_pinchStart > 1f && d > 1f)
                            _zoomFactor = Mathf.Clamp(_pinchZoomStart * _pinchStart / d, 0.25f, 4f);
                        // Pan by the fingers' midpoint movement too.
                        Vector2 delta = (pos - last) * 0.5f;
                        _panOffset += new Vector2(-delta.x, delta.y) * _worldPerPx;
                    }
                    else
                    {
                        if ((pos - _downPos).magnitude > TapSlopPx)
                            _moved = true;
                        if (_moved)
                        {
                            Vector2 delta = pos - last;
                            _panOffset += new Vector2(-delta.x, delta.y) * _worldPerPx;
                        }
                    }
                    return true;
                }
                case Bridge.TouchUp:
                case Bridge.TouchCancel:
                {
                    if (!_fingers.Remove(pointer))
                        return false;
                    _lastTouch = Time.unscaledTime;
                    if (action == Bridge.TouchUp && _fingers.Count == 0 && !_moved && Time.unscaledTime - _downTime < 0.3f)
                    {
                        if (Time.unscaledTime - _lastTapTime < DoubleTapSeconds)
                        {
                            _lastTapTime = -100f;
                            CycleZoom();
                        }
                        else
                        {
                            _lastTapTime = Time.unscaledTime;
                        }
                    }
                    if (_fingers.Count < 2)
                        _pinchStart = 0f;
                    return true;
                }
            }
            return false;
        }

        private float PinchDistance()
        {
            Vector2 a = Vector2.zero, c = Vector2.zero;
            int n = 0;
            foreach (Vector2 p in _fingers.Values)
            {
                if (n == 0)
                    a = p;
                else if (n == 1)
                    c = p;
                n++;
            }
            return n >= 2 ? (a - c).magnitude : 0f;
        }
    }
}
