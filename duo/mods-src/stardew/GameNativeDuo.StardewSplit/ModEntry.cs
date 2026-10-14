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
        helper.Events.GameLoop.UpdateTicked += (_, e) =>
        {
            if (e.IsMultipleOf(6))
                ReloadCalibration();
        };
        _layoutWritten = LayoutWriteTime();
    }

    private static DateTime _layoutWritten;

    private static DateTime LayoutWriteTime()
    {
        try
        {
            return System.IO.File.GetLastWriteTimeUtc(Layout.FilePath);
        }
        catch
        {
            return default;
        }
    }

    /// <summary>Picks up new calibration values while the game runs (GameNative Duo's calibration sliders).</summary>
    private void ReloadCalibration()
    {
        try
        {
            DateTime written = LayoutWriteTime();
            if (written == _layoutWritten || Layout == null)
                return;
            _layoutWritten = written;
            Layout? fresh = Layout.Load(out _);
            if (fresh == null || (fresh.Gap == Layout.Gap && fresh.BottomScale == Layout.BottomScale && fresh.BottomShift == Layout.BottomShift))
                return;
            Layout.Gap = fresh.Gap;
            Layout.BottomScale = fresh.BottomScale;
            Layout.BottomShift = fresh.BottomShift;
            Monitor.Log($"Calibration: gap {Layout.Gap}, bottom scale {Layout.BottomScale}, bottom shift {Layout.BottomShift}", LogLevel.Trace);
            if (Active && Patches.WorldTargetTooSmall(Game1.game1))
                Patches.CreateRenderTargets(Game1.game1);
        }
        catch (Exception ex)
        {
            Monitor.Log($"Failed to reload the layout: {ex}", LogLevel.Error);
        }
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
