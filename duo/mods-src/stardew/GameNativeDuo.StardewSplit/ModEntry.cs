using System;
using System.Collections.Generic;
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
///
/// It is also GameNative Duo's engine adapter for Stardew Valley (duo/docs/engine-adapter-contract.md): bottom-screen
/// touches arrive over the bridge as the game's mouse (<see cref="TouchInput"/>), and the user's UI layout resizes,
/// moves, hides or sends HUD elements to the top screen (<see cref="HudLayout"/>).
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

        var harmony = new Harmony(ModManifest.UniqueID);
        Patches.Apply(harmony);
        Guard("touch input", () => TouchInput.Apply(harmony));
        Guard("UI layout", () => HudLayout.Apply(harmony));
        ReloadRules();
        StartBridge();
        helper.Events.Input.ButtonPressed += OnButtonPressed;
        Guard("test harness", () => TestHarness.Install(helper));
        TouchInput.DrainBridge = () => Guard("bridge", DrainBridge);
        // Normally already done this tick when SMAPI read the mouse (lower latency); this covers ticks where it did not.
        helper.Events.GameLoop.UpdateTicking += (_, _) => Guard("touch", TouchInput.Tick);
        helper.Events.GameLoop.UpdateTicked += (_, e) =>
        {
            if (e.IsMultipleOf(6))
                ReloadCalibration();
            if (e.IsMultipleOf(60))
                Guard("UI layout reload", WatchRules);
            if (e.IsMultipleOf(120))
                _bridge?.SendHello(Capabilities, "stardew-smapi");
        };
        helper.Events.Display.Rendered += (_, _) =>
        {
            if (_dumpPending)
                Guard("element list", WriteElements);
        };
        _layoutWritten = LayoutWriteTime();
    }

    private static Bridge? _bridge;
    private static readonly List<Bridge.Message> Messages = new();
    private static bool _dumpPending;
    private static uint _dumpRequest;
    private static DateTime _rulesWritten;

    private void Guard(string what, Action action)
    {
        try
        {
            action();
        }
        catch (Exception ex)
        {
            Monitor.LogOnce($"GameNative Duo {what} failed: {ex}", LogLevel.Error);
        }
    }

    private void StartBridge()
    {
        if (Layout == null || Layout.BridgePort <= 0)
            return;
        try
        {
            _bridge = new Bridge(Layout.BridgePort);
            _bridge.SendHello(Capabilities, "stardew-smapi");
            Monitor.Log($"GameNative Duo bridge: talking to the app on port {Layout.BridgePort}", LogLevel.Info);
        }
        catch (Exception ex)
        {
            Monitor.Log($"GameNative Duo bridge unavailable (touch falls back to the mouse): {ex.Message}", LogLevel.Warn);
        }
    }

    /// <summary>Once per tick, before the game update (see <see cref="TouchInput.Tick"/>): touches, layout changes.</summary>
    private void DrainBridge()
    {
        if (_bridge != null)
        {
            _bridge.Drain(Messages);
            foreach (Bridge.Message m in Messages)
            {
                switch (m.Type)
                {
                    case Bridge.Touch:
                        if (Active)
                            TouchInput.Handle(m.Action, m.Pointer, m.X, m.Y);
                        break;
                    case Bridge.RulesChanged:
                        ReloadRules();
                        break;
                    case Bridge.DumpElements:
                        _dumpPending = true;
                        _dumpRequest = m.Request;
                        break;
                }
            }
            Messages.Clear();
        }
    }

    /// <summary>Touch, UI rules, the element list, and every bottom-screen background (game, blur, color, image).</summary>
    private const uint Capabilities = Bridge.CapTouch | Bridge.CapUiRules | Bridge.CapElements | Bridge.CapBgBasic | Bridge.CapBgBlur | Bridge.CapBgGame;

    private void ReloadRules()
    {
        _rulesWritten = RulesWriteTime();
        UiRules rules = UiRules.Load(out string error);
        if (error.Length > 0)
            Monitor.Log(error, LogLevel.Warn);
        HudLayout.SetRules(rules);
        BottomBackground.Set(rules.Background);
        Monitor.Log($"UI layout: {rules.Count} element rule(s)", LogLevel.Trace);
    }

    /// <summary>Fallback for a missed bridge message: ui.json rewritten since it was read.</summary>
    private void WatchRules()
    {
        if (RulesWriteTime() != _rulesWritten)
            ReloadRules();
    }

    private static DateTime RulesWriteTime()
    {
        try
        {
            return System.IO.File.Exists(UiRules.FilePath) ? System.IO.File.GetLastWriteTimeUtc(UiRules.FilePath) : default;
        }
        catch
        {
            return default;
        }
    }

    private void WriteElements()
    {
        _dumpPending = false;
        if (Active)
            HudLayout.WriteElements(System.IO.Path.Combine(System.IO.Path.GetDirectoryName(Layout.FilePath)!, "elements.json"));
        _bridge?.SendElementsWritten(_dumpRequest);
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
