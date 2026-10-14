using System;
using System.Linq;
using HarmonyLib;
using StardewModdingAPI;
using StardewModdingAPI.Events;
using StardewValley;

namespace GameNativeDuo.StardewSplit;

/// <summary>
/// World on the top screen, HUD and menus on the bottom screen. GameNative Duo shows one tall game window
/// across both screens; this mod renders the world into the top part (at the top screen's own resolution, so
/// more of the map is visible) and the UI into the bottom part (at the bottom screen's resolution), and makes
/// bottom-screen taps reach only the UI.
/// </summary>
public sealed class ModEntry : Mod
{
    internal static IMonitor Log = null!;

    /// <summary>The layout from GameNative Duo, or null when there is none.</summary>
    internal static Layout? Layout;

    /// <summary>True while the game window matches <see cref="Layout"/> (set on every window resize).</summary>
    internal static bool Active;

    public override void Entry(IModHelper helper)
    {
        Log = Monitor;
        Layout = Layout.Load(out string error);
        if (Layout == null)
        {
            Monitor.Log($"No GameNative Duo layout ({error}); the game runs unchanged.", LogLevel.Info);
            return;
        }
        Monitor.Log($"GameNative Duo layout: {Layout}", LogLevel.Info);

        Patches.Apply(new Harmony(ModManifest.UniqueID));
        helper.Events.Input.ButtonPressed += OnButtonPressed;
    }

    /// <summary>
    /// Taps on the bottom screen are UI-only: let them through when they land on a HUD element (toolbar, clock...)
    /// or while a menu is open, and drop them otherwise so the farmer does not swing a tool at an off-screen tile.
    /// </summary>
    private void OnButtonPressed(object? sender, ButtonPressedEventArgs e)
    {
        if (!Active || Layout == null || (e.Button != SButton.MouseLeft && e.Button != SButton.MouseRight))
            return;
        var mouse = Game1.input.GetMouseState();
        if (!Layout.Bottom.Contains(mouse.X, mouse.Y) || Game1.activeClickableMenu != null)
            return;

        int x = Game1.getMouseX(ui_scale: true);
        int y = Game1.getMouseY(ui_scale: true);
        bool onHud = Game1.onScreenMenus.Any(menu => menu.isWithinBounds(x, y));
        if (!onHud)
            Helper.Input.Suppress(e.Button);
    }
}
