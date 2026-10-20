using System;
using System.Collections.Generic;
using System.Reflection;
using HarmonyLib;
using Microsoft.Xna.Framework.Input;
using StardewModdingAPI;
using StardewValley;

namespace GameNativeDuo.StardewSplit;

/// <summary>
/// Bottom-screen touches, from GameNative Duo's bridge, as the game's mouse: while a finger is down, MonoGame's
/// Mouse.GetState() reports the finger's position and a pressed left button. The X server's pointer never moves, so
/// nothing outside the game reacts, and SMAPI and the game see the same input, so menus, the toolbar and inventory
/// clicks, holds and releases work as with a mouse.
///
/// Touchscreen behaviour on top of that:
/// - every press and release lasts at least one game tick (a quick tap between two ticks is not lost);
/// - drag and drop: lifting a finger somewhere else while it carries an item (picked up by the same touch) drops it
///   there, with one more click;
/// - after the finger lifts, the game keeps seeing the mouse where the finger was until the real mouse moves, so it
///   does not jump to wherever the X pointer happens to be.
/// Only the first finger counts (Stardew Valley has one cursor).
/// </summary>
internal static class TouchInput
{
    private const int DragDropPixels = 24;

    private readonly struct Step
    {
        public readonly bool Down;
        public readonly int X, Y;

        public Step(bool down, int x, int y)
        {
            Down = down;
            X = x;
            Y = y;
        }
    }

    private static readonly Queue<Step> Steps = new();
    private static bool _active, _down;
    private static int _x, _y, _startX, _startY;
    private static bool _carriedAtStart;
    private static int _realX = int.MinValue, _realY = int.MinValue;
    private static bool _changedThisTick;
    private static int _pointer = -1;

    public static void Apply(Harmony harmony)
    {
        foreach (MethodInfo m in typeof(Mouse).GetMethods(BindingFlags.Public | BindingFlags.Static))
        {
            if (m.Name == nameof(Mouse.GetState) && m.ReturnType == typeof(MouseState))
                harmony.Patch(m, postfix: new HarmonyMethod(typeof(TouchInput), nameof(GetState_Postfix)));
        }
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
                Steps.Enqueue(new Step(true, px, py));
                break;
            case Bridge.TouchMove:
                Steps.Enqueue(new Step(_down || PendingDown(), px, py));
                break;
            case Bridge.TouchUp:
            case Bridge.TouchCancel:
                Steps.Enqueue(new Step(false, px, py));
                _pointer = -1;
                break;
        }
    }

    private static bool PendingDown()
    {
        foreach (Step s in Steps)
            if (s.Down)
                return true;
        return false;
    }

    /// <summary>
    /// Once per tick, before the game updates: moves up to the next press/release (at most one per tick), then
    /// drag-and-drop on release.
    /// </summary>
    public static void Tick()
    {
        _changedThisTick = false;
        while (Steps.Count > 0)
        {
            Step s = Steps.Peek();
            if (s.Down != _down)
            {
                if (_changedThisTick)
                    break; // the game sees each press and release for at least one tick
                _changedThisTick = true;
                Steps.Dequeue();
                Press(s);
                continue;
            }
            Steps.Dequeue();
            _x = s.X;
            _y = s.Y;
        }
    }

    private static void Press(Step s)
    {
        bool wasDown = _down;
        _x = s.X;
        _y = s.Y;
        _down = s.Down;
        _active = true;
        if (s.Down)
        {
            _startX = s.X;
            _startY = s.Y;
            _carriedAtStart = CarriedItem() != null;
            MouseState real = RealMouse;
            _realX = real.X;
            _realY = real.Y;
            return;
        }
        // Lifted after dragging an item that this touch picked up: drop it where the finger is.
        if (wasDown && !_carriedAtStart && Math.Abs(s.X - _startX) + Math.Abs(s.Y - _startY) > DragDropPixels && CarriedItem() != null)
        {
            Steps.Enqueue(new Step(true, s.X, s.Y));
            Steps.Enqueue(new Step(false, s.X, s.Y));
        }
    }

    /// <summary>The item on the cursor, in the inventory (the farmer's cursor slot) or in menus with their own held item.</summary>
    private static object? CarriedItem()
    {
        try
        {
            if (Game1.player?.CursorSlotItem != null)
                return Game1.player.CursorSlotItem;
            object? menu = Game1.activeClickableMenu;
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
        if (!_active || _readingReal)
            return;
        // Back to the real mouse once it moves (someone uses a mouse or the top screen's touch).
        if (!_down && Steps.Count == 0 && (__result.X != _realX || __result.Y != _realY))
        {
            _active = false;
            return;
        }
        __result = new MouseState(_x, _y, __result.ScrollWheelValue, _down ? ButtonState.Pressed : ButtonState.Released,
            ButtonState.Released, ButtonState.Released, ButtonState.Released, ButtonState.Released);
    }

    /// <summary>True while touches drive the mouse (the cursor is hidden then).</summary>
    public static bool Driving => _active;

    public static void Reset()
    {
        Steps.Clear();
        _active = _down = false;
        _pointer = -1;
        ModEntry.Log.Log("Touch input reset", LogLevel.Trace);
    }
}
