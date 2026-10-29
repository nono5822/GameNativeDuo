using System.Collections.Generic;
using System.Reflection;
using HarmonyLib;
using HutongGames.PlayMaker;
using HutongGames.PlayMaker.Actions;
using UnityEngine;

namespace GameNativeDuo.HollowKnight
{
    /// <summary>
    /// Notes when the game itself opens its map (the inventory map's WorldMap, the quick map's QuickMap&lt;area&gt;), so
    /// the live map, stepping aside in that frame, does not switch the game's freshly opened map off again.
    /// </summary>
    [HarmonyPatch]
    internal static class GameOpensMapPatch
    {
        private static IEnumerable<MethodBase> TargetMethods()
        {
            foreach (MethodInfo m in typeof(GameMap).GetMethods(BindingFlags.Instance | BindingFlags.Public | BindingFlags.DeclaredOnly))
            {
                if (m.GetParameters().Length == 0 && (m.Name == "WorldMap" || (m.Name.StartsWith("QuickMap") && m.Name != "QuickMap")))
                    yield return m;
            }
        }

        private static void Prefix()
        {
            if (!LiveMap.OurCall)
                LiveMap.GameOpenedMapFrame = Time.frameCount;
        }
    }

    /// <summary>
    /// The map button reaches the game only through this PlayMaker action (the Knight's "Map Control", a bench's
    /// "Bench Control" and the inventory map all listen with it). While the live map owns the button, the game sees it
    /// as not pressed (except the inventory's map pane, which zooms out with it): nothing opens, the Knight is not stopped (LiveMap switches the zoom instead), and a quick map
    /// the game opened in a moment the live map did not own the button closes itself (its "not pressed" event), instead
    /// of staying open over the live map because it never hears the button go up.
    /// </summary>
    [HarmonyPatch(typeof(ListenForQuickMap), nameof(ListenForQuickMap.OnUpdate))]
    internal static class QuickMapPatch
    {
        private static bool Prefix(ListenForQuickMap __instance)
        {
            if (!LiveMap.OwnsMapButton || LiveMap.InInventory(__instance.Fsm.GameObject))
            {
                if (TestHarness.On && InputHandler.Instance != null && InputHandler.Instance.inputActions.quickMap.IsPressed)
                    Plugin.Log.LogInfo("TRACE game hears the map button: fsm '" + __instance.Fsm.Name + "' state '" + __instance.Fsm.ActiveStateName + "'");
                return true;
            }
            FsmEvent notPressed = __instance.isNotPressed;
            if (notPressed != null && !string.IsNullOrEmpty(notPressed.Name))
            {
                if (TestHarness.On)
                    Plugin.Log.LogInfo("TRACE map button not pressed for fsm '" + __instance.Fsm.Name + "' state '" + __instance.Fsm.ActiveStateName + "' -> " + notPressed.Name);
                __instance.Fsm.Event(notPressed);
            }
            return false;
        }
    }
}
