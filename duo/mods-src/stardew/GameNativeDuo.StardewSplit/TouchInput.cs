using System;
using System.Collections.Generic;
using System.Reflection;
using Microsoft.Xna.Framework;
using HarmonyLib;
using Microsoft.Xna.Framework.Input;
using StardewModdingAPI;
using StardewValley;
using StardewValley.Menus;

namespace GameNativeDuo.StardewSplit;

/// <summary>
/// Bottom-screen touches, from GameNative Duo's bridge, as the game's mouse: while a finger is down, MonoGame's
/// Mouse.GetState() reports the finger's position and a pressed left button. The X server's pointer never moves, so
/// nothing outside the game reacts, and SMAPI and the game see the same input, so menus, the toolbar and inventory
/// clicks, holds and releases work as with a mouse.
///
/// Touchscreen behaviour on top of that:
/// - touches are applied when SMAPI reads the mouse for a tick (not after), so the game reacts in the same tick;
/// - every press and release lasts at least one tick (a quick tap is never lost), and a press is seen exactly where
///   the finger landed, even if it moved within the same tick;
/// - drag and drop: Stardew picks an item up on a click and puts it down on the next click. Lifting a finger that
///   carries an item somewhere else clicks there (inside the menu only: never throws the item out of it); if the item
///   is still carried afterwards (an invalid target) and the drag started on an inventory slot, it goes back there.
///   Chests move a stack on the press itself (to the first free slot); a drag that ends on a slot of the other grid
///   then moves that stack to the slot under the finger (in the player's inventory: chests keep their items packed);
/// - while a finger drives the cursor, the game's own cursor warps (meant for gamepad navigation, and computed
///   without the bottom screen's offset) are ignored, and only real mouse use (a button, the wheel, or the X pointer
///   moving) hands the cursor back to the mouse. Before, a warp moved the X pointer, which looked like a mouse, so
///   the cursor and the item it carried jumped off screen (the "item disappears" bug).
/// Only the first finger counts (Stardew Valley has one cursor).
/// </summary>
internal static class TouchInput
{
    /// <summary>Finger travel (window px) that turns a press-and-lift into a drag.</summary>
    private const int DragPixels = 24;

    private enum Kind : byte
    {
        Move,
        Down,
        Up,
    }

    private readonly struct Step
    {
        public readonly Kind Kind;
        public readonly int X, Y;

        public Step(Kind kind, int x, int y)
        {
            Kind = kind;
            X = x;
            Y = y;
        }
    }

    private static readonly Queue<Step> Steps = new();
    private static bool _active, _down;
    private static int _x, _y, _startX, _startY;
    private static bool _carriedAtStart, _startOnSlot;
    private static int _pointer = -1;

    // A chest-menu drag: the stack under the finger at the press, and whether it came from the chest.
    private static Item? _grabItem;
    private static bool _grabFromChest;

    // The real (X server) mouse since the finger lifted, to notice when someone uses it.
    private static int _realX, _realY, _realWheel;

    // After a drop click: if the item is still carried once the game has handled it, put it back here.
    private static bool _returnArmed;
    private static int _returnX, _returnY;

    private static uint _pumpedTick = uint.MaxValue;

    /// <summary>Drains the bridge into <see cref="Handle"/>; set by <see cref="ModEntry"/>.</summary>
    internal static Action? DrainBridge;

    public static void Apply(Harmony harmony)
    {
        // Only the window overload: Mouse.GetState() calls it, and patching both made the outer call take the inner
        // call's finger position for the real mouse (so touch handed the cursor back to the mouse after every lift).
        harmony.Patch(AccessTools.Method(typeof(Mouse), nameof(Mouse.GetState), new[] { typeof(GameWindow) }),
            postfix: new HarmonyMethod(typeof(TouchInput), nameof(GetState_Postfix)));
        harmony.Patch(AccessTools.Method(typeof(Game1), nameof(Game1.setMousePositionRaw), new[] { typeof(int), typeof(int) }),
            prefix: new HarmonyMethod(typeof(TouchInput), nameof(SetMousePositionRaw_Prefix)));
    }

    /// <summary>One bridge touch (window pixels). Game thread.</summary>
    public static void Handle(byte action, int pointer, float x, float y)
    {
        if (_pointer < 0 && action == Bridge.TouchDown)
            _pointer = pointer;
        if (pointer != _pointer)
            return;
        int px = (int)Math.Round(x), py = (int)Math.Round(y);
        switch (action)
        {
            case Bridge.TouchDown:
                Steps.Enqueue(new Step(Kind.Down, px, py));
                break;
            case Bridge.TouchMove:
                Steps.Enqueue(new Step(Kind.Move, px, py));
                break;
            case Bridge.TouchUp:
            case Bridge.TouchCancel:
                Steps.Enqueue(new Step(Kind.Up, px, py));
                _pointer = -1;
                break;
        }
    }

    /// <summary>
    /// Once per tick, as early as possible (when SMAPI reads the mouse, else from UpdateTicking): applies queued
    /// moves up to the next press or release (at most one of those per tick), then drop and put-back clicks.
    /// </summary>
    public static void Tick()
    {
        if (Game1.ticks == _pumpedTick)
            return;
        _pumpedTick = (uint)Game1.ticks;
        DrainBridge?.Invoke();

        if (Steps.Count == 0 && _returnArmed && !_down)
        {
            // The game handled the drop click last tick: an item still on the cursor did not fit there.
            _returnArmed = false;
            if (CarriedItem() != null)
                Click(_returnX, _returnY);
        }
        while (Steps.Count > 0)
        {
            Step s = Steps.Peek();
            bool press = s.Kind == Kind.Down && !_down, release = s.Kind == Kind.Up && _down;
            if (!press && !release)
            {
                Steps.Dequeue();
                if (s.Kind == Kind.Move || !_down)
                {
                    _x = s.X;
                    _y = s.Y;
                }
                continue;
            }
            Steps.Dequeue();
            if (press)
                Press(s.X, s.Y);
            else
                Release(s.X, s.Y);
            break; // the game sees this press or release, at this position, for a whole tick
        }
    }

    private static void Press(int x, int y)
    {
        _x = _startX = x;
        _y = _startY = y;
        _down = true;
        _active = true;
        if (_returnArmed)
            return; // our own drop or put-back click: no drag bookkeeping
        _carriedAtStart = CarriedItem() != null;
        _startOnSlot = IsInventorySlot(x, y);
        _grabItem = null;
        if (Game1.activeClickableMenu is ItemGrabMenu grab)
        {
            int ux = UiX(x), uy = UiY(y);
            _grabItem = ItemAt(grab.ItemsToGrabMenu, ux, uy);
            _grabFromChest = _grabItem != null;
            _grabItem ??= ItemAt(grab.inventory, ux, uy);
        }
    }

    private static void Release(int x, int y)
    {
        _x = x;
        _y = y;
        _down = false;
        MouseState real = RealMouse;
        _realX = real.X;
        _realY = real.Y;
        _realWheel = real.ScrollWheelValue;
        if (_returnArmed || Math.Abs(x - _startX) + Math.Abs(y - _startY) <= DragPixels)
            return;
        if (_grabItem != null)
        {
            PlaceTransferred(x, y);
            return;
        }
        if (CarriedItem() == null)
            return;
        bool putBack = !_carriedAtStart && _startOnSlot;
        if (IsDropTarget(x, y))
        {
            Click(x, y);
            if (putBack)
            {
                _returnArmed = true;
                _returnX = _startX;
                _returnY = _startY;
            }
        }
        else if (putBack)
        {
            Click(_startX, _startY);
        }
    }

    /// <summary>The stack the chest menu moved on the press goes to the slot under the finger, in the grid it moved to.</summary>
    private static void PlaceTransferred(int x, int y)
    {
        try
        {
            Item item = _grabItem!;
            _grabItem = null;
            if (Game1.activeClickableMenu is not ItemGrabMenu grab)
                return;
            InventoryMenu? target = _grabFromChest ? grab.inventory : grab.ItemsToGrabMenu;
            int to = SlotAt(target, UiX(x), UiY(y));
            if (target == null || to < 0 || to >= target.actualInventory.Count)
                return;
            IList<Item> items = target.actualInventory;
            int from = items.IndexOf(item); // not found: it merged into a stack, or did not move (full)
            if (from < 0 || from == to)
                return;
            (items[from], items[to]) = (items[to], items[from]);
            Game1.playSound("stoneStep");
        }
        catch (Exception ex)
        {
            ModEntry.Log.LogOnce($"GameNative Duo: chest drag failed: {ex.Message}", LogLevel.Warn);
        }
    }

    /// <summary>A press and release at (x, y), each seen by the game for one tick.</summary>
    private static void Click(int x, int y)
    {
        Steps.Enqueue(new Step(Kind.Down, x, y));
        Steps.Enqueue(new Step(Kind.Up, x, y));
    }

    /// <summary>The item on the cursor, in the inventory (the farmer's cursor slot) or in menus with their own held item.</summary>
    private static object? CarriedItem()
    {
        try
        {
            if (Game1.player?.CursorSlotItem != null)
                return Game1.player.CursorSlotItem;
            object? menu = Game1.activeClickableMenu;
            if (menu is GameMenu gm)
                menu = gm.GetCurrentPage();
            if (menu == null)
                return null;
            return HeldItemField(menu.GetType())?.GetValue(menu);
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static readonly Dictionary<Type, FieldInfo?> HeldItemFields = new();

    private static FieldInfo? HeldItemField(Type type)
    {
        if (!HeldItemFields.TryGetValue(type, out FieldInfo? f))
            HeldItemFields[type] = f = AccessTools.Field(type, "heldItem");
        return f;
    }

    /// <summary>Window px to the menus' UI coordinates (the bottom screen, see Patches.MouseX_Postfix).</summary>
    private static int UiX(int x) => (int)((x - ModEntry.Layout!.Bottom.X) / Game1.options.uiScale);

    private static int UiY(int y) => (int)((y - ModEntry.Layout!.Bottom.Y) / Game1.options.uiScale);

    /// <summary>Inside the active menu (its inventories and trash can included): a click there never throws the item out.</summary>
    private static bool IsDropTarget(int x, int y)
    {
        try
        {
            IClickableMenu? menu = Game1.activeClickableMenu;
            if (menu == null)
                return false;
            int ux = UiX(x), uy = UiY(y);
            if (menu.isWithinBounds(ux, uy))
                return true;
            if (menu is ItemGrabMenu grab && grab.ItemsToGrabMenu?.isWithinBounds(ux, uy) == true)
                return true;
            if (menu is MenuWithInventory mwi && mwi.trashCan?.containsPoint(ux, uy) == true)
                return true;
            return menu is GameMenu gm && gm.GetCurrentPage() is InventoryPage page && page.trashCan?.containsPoint(ux, uy) == true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>On an item slot of one of the active menu's inventories (not a recipe, an equipment slot or a button).</summary>
    private static bool IsInventorySlot(int x, int y)
    {
        try
        {
            int ux = UiX(x), uy = UiY(y);
            switch (Game1.activeClickableMenu)
            {
                case GameMenu gm:
                    return gm.GetCurrentPage() switch
                    {
                        InventoryPage p => HasSlotAt(p.inventory, ux, uy),
                        CraftingPage c => HasSlotAt(c.inventory, ux, uy),
                        _ => false,
                    };
                case ItemGrabMenu grab:
                    return HasSlotAt(grab.inventory, ux, uy) || HasSlotAt(grab.ItemsToGrabMenu, ux, uy);
                case MenuWithInventory mwi:
                    return HasSlotAt(mwi.inventory, ux, uy);
                default:
                    return false;
            }
        }
        catch (Exception)
        {
            return false;
        }
    }

    private static bool HasSlotAt(InventoryMenu? inventory, int x, int y) => SlotAt(inventory, x, y) >= 0;

    /// <summary>The index (in <see cref="InventoryMenu.actualInventory"/>) of the slot at (x, y), or -1.</summary>
    private static int SlotAt(InventoryMenu? inventory, int x, int y)
    {
        if (inventory?.inventory == null)
            return -1;
        foreach (ClickableComponent slot in inventory.inventory)
            if (slot.containsPoint(x, y))
                return int.TryParse(slot.name, out int i) ? i : -1;
        return -1;
    }

    private static Item? ItemAt(InventoryMenu? inventory, int x, int y)
    {
        int i = SlotAt(inventory, x, y);
        return i >= 0 && i < inventory!.actualInventory.Count ? inventory.actualInventory[i] : null;
    }

    private static bool _readingReal;

    private static MouseState RealMouse
    {
        get
        {
            _readingReal = true;
            try
            {
                return Mouse.GetState();
            }
            finally
            {
                _readingReal = false;
            }
        }
    }

    private static void GetState_Postfix(ref MouseState __result)
    {
        if (_readingReal || !ModEntry.Active)
            return;
        Tick();
        if (!_active)
            return;
        // Back to the real mouse once someone uses it (a mouse, or the top screen's touch, which moves the X pointer).
        if (!_down && Steps.Count == 0 && !_returnArmed
            && (__result.X != _realX || __result.Y != _realY || __result.ScrollWheelValue != _realWheel
                || __result.LeftButton == ButtonState.Pressed || __result.RightButton == ButtonState.Pressed))
        {
            _active = false;
            if (TestHarness.On)
                ModEntry.Log.Log($"touch -> mouse: real {__result.X},{__result.Y} wheel {__result.ScrollWheelValue} L {__result.LeftButton} R {__result.RightButton}", LogLevel.Info);
            return;
        }
        __result = new MouseState(_x, _y, __result.ScrollWheelValue, _down ? ButtonState.Pressed : ButtonState.Released,
            ButtonState.Released, ButtonState.Released, ButtonState.Released, ButtonState.Released);
    }

    /// <summary>While a finger drives the cursor, it decides where the cursor is: the game's warps would move the X pointer.</summary>
    private static bool SetMousePositionRaw_Prefix() => !(_active && ModEntry.Active);

    /// <summary>For the test harness log.</summary>
    internal static string Describe() =>
        $"touch {(_active ? "on" : "off")} {(_down ? "down" : "up")} {_x},{_y} queued {Steps.Count}{(_returnArmed ? " return" : "")}";

    /// <summary>True while touches drive the mouse (the cursor is hidden then).</summary>
    public static bool Driving => _active;

    public static void Reset()
    {
        Steps.Clear();
        _active = _down = _returnArmed = false;
        _grabItem = null;
        _pointer = -1;
        ModEntry.Log.Log("Touch input reset", LogLevel.Trace);
    }
}
