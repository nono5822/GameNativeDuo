using System;
using System.IO;
using HarmonyLib;
using StardewModdingAPI;
using StardewValley;
using StardewValley.Menus;
using StardewValley.Objects;

namespace GameNativeDuo.StardewSplit;

/// <summary>
/// For automated tests on a real save: only when %AppData%\GameNativeDuo\sdv-test.txt exists at start. Then the
/// clock is frozen (no day can end, so the game never saves). Each time the file changes, its lines run as commands:
///   menu inventory|crafting|chest   open that menu (chest: a temporary chest with test items, not saved anywhere)
///   close                           close the active menu
///   trace on|off                    log touch, mouse and held-item state every tick while it changes
///   state                           log that state once
/// </summary>
internal static class TestHarness
{
    internal static string FilePath => Path.Combine(Path.GetDirectoryName(Layout.FilePath)!, "sdv-test.txt");

    internal static bool On { get; private set; }
    private static bool _trace;
    private static DateTime _stamp;
    private static string _last = "";

    public static void Install(IModHelper helper)
    {
        if (!File.Exists(FilePath))
            return;
        On = true;
        var harmony = new Harmony("GameNativeDuo.StardewSplit.TestHarness");
        foreach (var m in new[] { AccessTools.Method(typeof(GameMenu), nameof(GameMenu.receiveLeftClick)), AccessTools.Method(typeof(ItemGrabMenu), nameof(ItemGrabMenu.receiveLeftClick)) })
            harmony.Patch(m, prefix: new HarmonyMethod(typeof(TestHarness), nameof(Click_Prefix)));
        ModEntry.Log.Log($"TEST MODE ({FilePath}): the clock is frozen, no day ends, nothing is saved", LogLevel.Warn);
        helper.Events.GameLoop.UpdateTicked += (_, e) =>
        {
            if (Context.IsWorldReady)
                Game1.gameTimeInterval = 0;
            if (_trace)
                Trace();
            if (e.IsMultipleOf(30))
                Poll();
        };
    }

    private static void Poll()
    {
        DateTime t = File.GetLastWriteTimeUtc(FilePath);
        if (t == _stamp)
            return;
        bool first = _stamp == default;
        _stamp = t;
        if (first)
            return; // commands present at start are not run again on every launch
        foreach (string raw in File.ReadAllLines(FilePath))
        {
            string line = raw.Trim();
            if (line.Length == 0 || line.StartsWith('#'))
                continue;
            try
            {
                Run(line.Split(' ', StringSplitOptions.RemoveEmptyEntries));
            }
            catch (Exception ex)
            {
                ModEntry.Log.Log($"test: '{line}' failed: {ex.Message}", LogLevel.Warn);
            }
        }
    }

    private static void Run(string[] a)
    {
        switch (a[0])
        {
            case "menu" when a.Length > 1:
                Game1.activeClickableMenu = a[1] switch
                {
                    "inventory" => new GameMenu(GameMenu.inventoryTab),
                    "crafting" => new GameMenu(GameMenu.craftingTab),
                    "chest" => TestChest(),
                    _ => Game1.activeClickableMenu,
                };
                break;
            case "close":
                Game1.exitActiveMenu();
                break;
            case "trace" when a.Length > 1:
                _trace = a[1] == "on";
                break;
            case "state":
                ModEntry.Log.Log("test state: " + Describe(), LogLevel.Info);
                return;
        }
        ModEntry.Log.Log("test: " + string.Join(' ', a), LogLevel.Info);
    }

    private static void Click_Prefix(IClickableMenu __instance, int x, int y)
    {
        if (_trace)
            ModEntry.Log.Log($"trace {Game1.ticks}: CLICK {__instance.GetType().Name} at {x},{y} cursorSlot {Game1.player?.CursorSlotItem?.Name ?? "-"}", LogLevel.Info);
    }

    private static IClickableMenu TestChest()
    {
        var chest = new Chest(true);
        chest.Items.Add(ItemRegistry.Create("(O)388", 25)); // wood
        chest.Items.Add(ItemRegistry.Create("(O)390", 10)); // stone
        chest.Items.Add(ItemRegistry.Create("(O)24", 3)); // parsnip
        return new ItemGrabMenu(chest.Items, false, true, InventoryMenu.highlightAllItems, chest.grabItemFromInventory, null,
            chest.grabItemFromChest, false, true, true, true, true, 1, chest, -1, chest);
    }

    private static void Trace()
    {
        string s = Describe();
        if (s == _last)
            return;
        _last = s;
        ModEntry.Log.Log($"trace {Game1.ticks}: {s}", LogLevel.Info);
    }

    private static string Describe()
    {
        var m = Game1.input.GetMouseState();
        var o = Game1.oldMouseState;
        object? held = Game1.activeClickableMenu is ItemGrabMenu g ? g.heldItem : null;
        return $"{TouchInput.Describe()} | mouse {m.X},{m.Y} {(m.LeftButton == Microsoft.Xna.Framework.Input.ButtonState.Pressed ? "P" : "r")}"
            + $" old {o.X},{o.Y} {(o.LeftButton == Microsoft.Xna.Framework.Input.ButtonState.Pressed ? "P" : "r")}"
            + $" ui {Game1.getMouseX(true)},{Game1.getMouseY(true)} cursorSlot {Game1.player?.CursorSlotItem?.Name ?? "-"} held {(held as Item)?.Name ?? "-"}"
            + $" pad {Game1.options.gamepadControls} snappy {Game1.options.snappyMenus} mouseMotion {Game1.lastCursorMotionWasMouse} menu {Game1.activeClickableMenu?.GetType().Name ?? "-"}";
    }
}
