using System;
using BepInEx;
using BepInEx.Logging;
using HarmonyLib;
using UnityEngine;

namespace GameNativeDuo.HollowKnight
{
    /// <summary>
    /// GameNative Duo for Hollow Knight, on top of the generic Unity split (GameNativeDuo.UnitySplit, which already
    /// puts the world on the top screen and the HUD, inventory and menus on the bottom one): the map, live on the
    /// bottom screen while you play (<see cref="LiveMap"/>). Inert unless the split is on.
    /// </summary>
    [BepInPlugin("app.gamenative.duo.hollowknight", "GameNative Duo Hollow Knight", "0.1.0")]
    [BepInDependency("app.gamenative.duo.unitysplit")]
    public sealed class Plugin : BaseUnityPlugin
    {
        internal static ManualLogSource Log;

        private void Awake()
        {
            Log = Logger;
            try
            {
                var harmony = new Harmony("app.gamenative.duo.hollowknight");
                harmony.PatchAll(typeof(QuickMapPatch));
                harmony.PatchAll(typeof(GameOpensMapPatch));
                // Our own object: the game may destroy BepInEx's manager object while loading.
                var go = new GameObject("GameNativeDuo.HollowKnight");
                go.hideFlags = HideFlags.HideAndDontSave;
                DontDestroyOnLoad(go);
                go.AddComponent<LiveMap>();
                go.AddComponent<WorldOnTop>();
                TestHarness.Install(go, harmony);
            }
            catch (Exception ex)
            {
                Log.LogError("GameNative Duo Hollow Knight failed to start (the game runs with the generic split): " + ex);
            }
        }
    }
}
