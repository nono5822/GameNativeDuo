using System;
using System.IO;
using HarmonyLib;
using UnityEngine;

namespace GameNativeDuo.HollowKnight
{
    /// <summary>
    /// For automated tests on a real save: only when %AppData%\GameNativeDuo\hk-test.txt exists at start. Then the game
    /// never saves (GameManager.SaveGame is skipped) and the Knight is invincible, so a test run cannot change the save.
    /// Each time the file changes, its lines run as commands, results in the BepInEx log:
    ///   zoom                       press the map button (switch the live map's zoom)
    ///   dump                       cameras, HUD and map trees
    ///   gates                      the current scene's transition points
    ///   scene &lt;name&gt; &lt;gate&gt;        travel through a door (e.g. "scene Town left1")
    ///   compass on|off             equip or unequip the Wayward Compass's effect (charm 2), no save
    ///   fsm &lt;object path&gt;          the PlayMaker FSMs on an object: active state, events
    ///   event &lt;object path&gt; &lt;fsm&gt; &lt;event...&gt;   send an FSM event (names may contain spaces: use _ for them)
    ///   bounds                     the live map's area bounds
    /// </summary>
    internal sealed class TestHarness : MonoBehaviour
    {
        internal static string FilePath => Path.Combine(GameNativeDuo.UnitySplit.Layout.Dir, "hk-test.txt");

        internal static bool On;
        private DateTime _stamp;
        private float _next;

        public static void Install(GameObject host, Harmony harmony)
        {
            if (!File.Exists(FilePath))
                return;
            On = true;
            harmony.Patch(AccessTools.Method(typeof(GameManager), "SaveGame", new[] { typeof(int), typeof(Action<bool>) }),
                prefix: new HarmonyMethod(typeof(TestHarness), nameof(SkipSave)));
            host.AddComponent<TestHarness>();
            Plugin.Log.LogWarning("TEST MODE (" + FilePath + "): saving is off and the Knight is invincible");
        }

        private static bool SkipSave(Action<bool> callback)
        {
            Plugin.Log.LogInfo("Test mode: save skipped");
            if (callback != null)
                callback(true);
            return false;
        }

        private void Update()
        {
            if (PlayerData.instance != null && !PlayerData.instance.isInvincible)
                PlayerData.instance.isInvincible = true;
            if (Time.unscaledTime < _next)
                return;
            _next = Time.unscaledTime + 0.5f;
            try
            {
                DateTime t = File.GetLastWriteTimeUtc(FilePath);
                if (t == _stamp)
                    return;
                bool first = _stamp == default(DateTime);
                _stamp = t;
                if (!first)
                    Run(File.ReadAllLines(FilePath));
            }
            catch (Exception ex)
            {
                Plugin.Log.LogError("Test command failed: " + ex);
            }
        }

        /// <summary>An object by path from a root, e.g. "_GameCameras/HudCamera/Inventory" or "Knight".</summary>
        private static GameObject Find(string path)
        {
            GameObject go = GameObject.Find(path);
            if (go != null)
                return go;
            if (path == "Knight" && HeroController.instance != null)
                return HeroController.instance.gameObject;
            return null;
        }

        private static void Run(string[] lines)
        {
            foreach (string raw in lines)
            {
                string[] a = raw.Trim().Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
                if (a.Length == 0 || a[0].StartsWith("#"))
                    continue;
                Plugin.Log.LogInfo("Test command: " + raw.Trim());
                GameManager gm = GameManager.instance;
                switch (a[0])
                {
                    case "zoom":
                        if (LiveMap.Instance != null && LiveMap.Instance.Shown)
                            LiveMap.Instance.CycleZoom();
                        else
                            Plugin.Log.LogInfo("Live map not shown");
                        break;
                    case "pause":
                        if (gm != null)
                            gm.StartCoroutine(gm.PauseGameToggle());
                        break;
                    case "state":
                        Plugin.Log.LogInfo("  paused " + (gm != null && gm.isPaused) + ", game " + (gm != null ? gm.gameState.ToString() : "?") +
                            ", ui " + (UIManager.instance != null ? UIManager.instance.uiState.ToString() : "?") +
                            ", inventory " + LiveMap.InventoryIsOpen() + ", live map " + (LiveMap.Instance != null && LiveMap.Instance.Shown));
                        break;
                    case "bounds":
                        if (LiveMap.Instance != null)
                            LiveMap.Instance.LogBounds();
                        break;
                    case "dump":
                        Dump.Cameras();
                        if (GameCameras.instance != null)
                            Dump.Tree(GameCameras.instance.hudCamera.transform, 2);
                        if (gm != null && gm.gameMap != null)
                            Dump.Tree(gm.gameMap.transform, 2);
                        break;
                    case "gates":
                        foreach (TransitionPoint tp in FindObjectsOfType<TransitionPoint>())
                            Plugin.Log.LogInfo("  gate " + tp.name + " -> " + tp.targetScene + " / " + tp.entryPoint);
                        Plugin.Log.LogInfo("  scene " + (gm != null ? gm.sceneName : "?") + ", zone " + (gm != null ? gm.GetCurrentMapZone() : "?"));
                        break;
                    case "scene":
                        if (a.Length >= 3 && gm != null)
                            gm.BeginSceneTransition(new GameManager.SceneLoadInfo
                            {
                                SceneName = a[1],
                                EntryGateName = a[2],
                                EntryDelay = 0f,
                                PreventCameraFadeOut = false,
                                Visualization = GameManager.SceneLoadVisualizations.Default,
                            });
                        break;
                    case "fsm":
                    {
                        GameObject go = a.Length >= 2 ? Find(a[1]) : null;
                        if (go == null)
                        {
                            Plugin.Log.LogInfo("  no object " + (a.Length >= 2 ? a[1] : ""));
                            break;
                        }
                        foreach (PlayMakerFSM fsm in go.GetComponents<PlayMakerFSM>())
                        {
                            var events = new System.Text.StringBuilder();
                            foreach (HutongGames.PlayMaker.FsmEvent e in fsm.FsmEvents)
                                events.Append(e.Name).Append(", ");
                            Plugin.Log.LogInfo("  fsm '" + fsm.FsmName + "' state '" + fsm.ActiveStateName + "' events: " + events);
                        }
                        break;
                    }
                    case "event":
                    {
                        GameObject go = a.Length >= 4 ? Find(a[1]) : null;
                        PlayMakerFSM fsm = go != null ? PlayMakerFSM.FindFsmOnGameObject(go, a[2].Replace('_', ' ')) : null;
                        if (fsm == null)
                        {
                            Plugin.Log.LogInfo("  no fsm " + (a.Length >= 3 ? a[1] + " / " + a[2] : ""));
                            break;
                        }
                        string ev = string.Join(" ", a, 3, a.Length - 3).Replace('_', ' ');
                        fsm.SendEvent(ev);
                        Plugin.Log.LogInfo("  sent '" + ev + "', state now '" + fsm.ActiveStateName + "'");
                        break;
                    }
                    case "compass":
                        if (a.Length >= 2 && PlayerData.instance != null)
                        {
                            PlayerData.instance.equippedCharm_2 = a[1] == "on";
                            if (gm != null && gm.gameMap != null)
                                gm.gameMap.GetComponent<GameMap>().PositionCompass(false);
                        }
                        break;
                    default:
                        Plugin.Log.LogWarning("Unknown test command: " + a[0]);
                        break;
                }
            }
        }
    }
}
