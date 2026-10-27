using HarmonyLib;
using HutongGames.PlayMaker.Actions;

namespace GameNativeDuo.HollowKnight
{
    /// <summary>
    /// The Knight's quick map (a PlayMaker FSM) listens for the map button through this action: while the live map
    /// owns the button, the FSM never hears it, so the Knight is not stopped (LiveMap switches the zoom instead).
    /// </summary>
    [HarmonyPatch(typeof(ListenForQuickMap), nameof(ListenForQuickMap.OnUpdate))]
    internal static class QuickMapPatch
    {
        private static bool Prefix() => !LiveMap.OwnsMapButton;
    }
}
