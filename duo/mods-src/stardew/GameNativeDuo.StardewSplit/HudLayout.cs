using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using HarmonyLib;
using Microsoft.Xna.Framework;
using Microsoft.Xna.Framework.Graphics;
using StardewModdingAPI;
using StardewValley;
using StardewValley.Menus;

namespace GameNativeDuo.StardewSplit;

/// <summary>
/// The user's UI layout (<see cref="UiRules"/>) for Stardew Valley's HUD: each element can be resized, moved, hidden,
/// and the toolbar, clock and buffs can go to the top screen.
///
/// How: an element's draw method runs inside its own SpriteBatch transform (scale about its centre, then the shift),
/// and the mouse coordinates its click/hover methods receive go through the inverse, so taps land on what is drawn.
/// Elements sent to the top screen are skipped in the normal UI pass and drawn again, laid out for the top screen,
/// into a small render target that is put on the top screen (only while something is there).
///
/// Cost: nothing for elements without a rule (one dictionary lookup per draw); the top pass only runs while an
/// element is on the top screen.
/// </summary>
internal static class HudLayout
{
    private sealed class Element
    {
        public string Id = "", Name = "";
        public Type? MenuType;
        public UiRule? Rule;
        public Matrix Transform = Matrix.Identity, Inverse = Matrix.Identity;
    }

    private static readonly Element[] Elements =
    {
        new() { Id = "hud/toolbar", Name = "Toolbar", MenuType = typeof(Toolbar) },
        new() { Id = "hud/clock", Name = "Clock and money", MenuType = typeof(DayTimeMoneyBox) },
        new() { Id = "hud/buffs", Name = "Buffs", MenuType = typeof(BuffsDisplay) },
        new() { Id = "hud/chat", Name = "Chat", MenuType = typeof(ChatBox) },
        new() { Id = "hud/bars", Name = "Health and energy", MenuType = null }, // drawn by Game1.drawHUD
    };

    private static readonly Dictionary<Type, Element> ByType = Elements.Where(e => e.MenuType != null).ToDictionary(e => e.MenuType!);
    private static readonly Element Bars = Elements.First(e => e.Id == "hud/bars");
    private static UiRules _rules = UiRules.Empty;
    private static bool _drawingTop;
    private static RenderTarget2D? _topUi;
    private static Layout L => ModEntry.Layout!;

    public static void Apply(Harmony harmony)
    {
        foreach (Type type in ByType.Keys)
        {
            Patch(harmony, type, "draw", new[] { typeof(SpriteBatch) }, nameof(Draw_Prefix), nameof(Draw_Postfix));
            Patch(harmony, type, "isWithinBounds", new[] { typeof(int), typeof(int) }, nameof(Within_Prefix), null);
            foreach (string click in new[] { "receiveLeftClick", "receiveRightClick" })
                Patch(harmony, type, click, new[] { typeof(int), typeof(int), typeof(bool) }, nameof(Click_Prefix), null);
            foreach (string pointer in new[] { "leftClickHeld", "releaseLeftClick", "performHoverAction" })
                Patch(harmony, type, pointer, new[] { typeof(int), typeof(int) }, nameof(Click_Prefix), null);
        }
        Patch(harmony, typeof(Game1), "drawHUD", Type.EmptyTypes, nameof(DrawHud_Prefix), nameof(DrawHud_Postfix));
    }

    /// <summary>Each method is patched once (two elements could share a type).</summary>
    private static readonly HashSet<MethodBase> Patched = new();

    private static void Patch(Harmony harmony, Type type, string method, Type[] args, string? prefix, string? postfix)
    {
        try
        {
            MethodInfo? m = AccessTools.Method(type, method, args);
            if (m != null && m.DeclaringType != type)
                return; // inherited from IClickableMenu, which every menu shares: nothing of this element's to adjust
            if (m == null)
            {
                ModEntry.Log.Log($"UI layout: {type.Name}.{method} not found; that element keeps its default layout", LogLevel.Warn);
                return;
            }
            if (!Patched.Add(m))
                return;
            harmony.Patch(m,
                prefix: prefix == null ? null : new HarmonyMethod(typeof(HudLayout), prefix),
                postfix: postfix == null ? null : new HarmonyMethod(typeof(HudLayout), postfix));
        }
        catch (Exception ex)
        {
            ModEntry.Log.Log($"UI layout: could not patch {type.Name}.{method}: {ex.Message}", LogLevel.Warn);
        }
    }

    public static void SetRules(UiRules rules)
    {
        _rules = rules;
        foreach (Element e in Elements)
            e.Rule = rules.Get(e.Id);
    }

    private static bool AnyOnTop => Elements.Any(e => e.Rule?.Screen == DuoScreen.Top && e.MenuType != null);

    // ---- transforms --------------------------------------------------------------------------------------------

    /// <summary>The element's transform in UI units: scale about the centre of <paramref name="bounds"/>, then the shift.</summary>
    private static void UpdateTransform(Element e, Rectangle bounds)
    {
        UiRule? r = e.Rule;
        if (r == null || !r.ChangesTransform)
        {
            e.Transform = e.Inverse = Matrix.Identity;
            return;
        }
        float ui = Game1.options.uiScale;
        Rectangle region = r.Screen == DuoScreen.Top ? L.Top : L.Bottom;
        var centre = new Vector3(bounds.Center.X, bounds.Center.Y, 0f);
        var shift = new Vector3(r.X * region.Width / ui, r.Y * region.Height / ui, 0f);
        e.Transform = Matrix.CreateTranslation(-centre) * Matrix.CreateScale(r.Scale, r.Scale, 1f) * Matrix.CreateTranslation(centre + shift);
        e.Inverse = Matrix.Invert(e.Transform);
    }

    private static Rectangle Bounds(IClickableMenu m) => m is Toolbar t
        // Toolbar.draw centres an 800-wide box itself and keeps the box's *bottom* edge (+8) in its own
        // yPositionOnScreen, a field that hides IClickableMenu's.
        ? new Rectangle(Game1.uiViewport.Width / 2 - 400, t.yPositionOnScreen - 104, 800, 96)
        : new Rectangle(m.xPositionOnScreen, m.yPositionOnScreen, m.width, m.height);

    /// <summary>
    /// Health and energy bars: drawn by Game1.drawHUD at the UI viewport's bottom-right (taller with more max energy).
    /// Approximate, for the transform's centre and the editor's box.
    /// </summary>
    private static Rectangle BarsBounds()
    {
        int w = Game1.uiViewport.Width, h = Game1.uiViewport.Height;
        return new Rectangle(w - 140, h - 340, 132, 330);
    }

    private static Element? For(IClickableMenu menu) => ByType.TryGetValue(menu.GetType(), out Element? e) ? e : null;

    // Draw takes its SpriteBatch from __args (read only): it works whatever the game names that parameter.
    private static bool Draw_Prefix(IClickableMenu __instance, object[] __args, out bool __state)
    {
        __state = false;
        if (__args.Length == 0 || __args[0] is not SpriteBatch b)
            return true;
        if (!ModEntry.Active)
            return true;
        try
        {
            Element? e = For(__instance);
            if (e?.Rule == null)
                return true;
            if (e.Rule.Screen == DuoScreen.Hidden)
                return false;
            if (e.Rule.Screen == DuoScreen.Top && !_drawingTop)
                return false; // drawn in the top pass
            UpdateTransform(e, Bounds(__instance));
            __state = e.Rule.ChangesTransform && BatchState.Push(b, e.Transform);
        }
        catch (Exception ex)
        {
            ModEntry.Log.LogOnce($"UI layout draw failed: {ex}", LogLevel.Error);
        }
        return true;
    }

    private static void Draw_Postfix(object[] __args, bool __state)
    {
        if (__state && __args.Length > 0 && __args[0] is SpriteBatch b)
            BatchState.Pop(b);
    }

    private static bool _barsPushed;

    private static bool DrawHud_Prefix()
    {
        _barsPushed = false;
        if (!ModEntry.Active || Bars.Rule == null)
            return true;
        if (Bars.Rule.Screen == DuoScreen.Hidden)
            return false;
        UpdateTransform(Bars, BarsBounds());
        _barsPushed = Bars.Rule.ChangesTransform && BatchState.Push(Game1.spriteBatch, Bars.Transform);
        return true;
    }

    private static void DrawHud_Postfix()
    {
        if (_barsPushed)
            BatchState.Pop(Game1.spriteBatch);
        _barsPushed = false;
    }

    /// <summary>Taps reach a moved or resized element where it is drawn; an element on the top screen or hidden is out of reach.</summary>
    // The pointer arrives as the methods' (x, y) in UI units; every patched method names them so (checked against
    // Stardew Valley 1.6). By ref, not through __args: Harmony does not write __args changes back to value arguments.
    private static bool Within_Prefix(IClickableMenu __instance, ref int x, ref int y, ref bool __result)
    {
        if (!ModEntry.Active)
            return true;
        Element? e = For(__instance);
        if (e?.Rule == null)
            return true;
        if (e.Rule.Screen is DuoScreen.Top or DuoScreen.Hidden)
        {
            __result = false;
            return false;
        }
        Untransform(e, ref x, ref y);
        return true;
    }

    private static bool Click_Prefix(IClickableMenu __instance, ref int x, ref int y)
    {
        if (!ModEntry.Active)
            return true;
        Element? e = For(__instance);
        if (e?.Rule == null)
            return true;
        if (e.Rule.Screen is DuoScreen.Top or DuoScreen.Hidden)
            return false;
        Untransform(e, ref x, ref y);
        return true;
    }

    /// <summary>Maps the pointer's UI coordinates back through the element's transform.</summary>
    private static void Untransform(Element e, ref int x, ref int y)
    {
        if (e.Rule == null || !e.Rule.ChangesTransform)
            return;
        Vector2 p = Vector2.Transform(new Vector2(x, y), e.Inverse);
        x = (int)Math.Round(p.X);
        y = (int)Math.Round(p.Y);
    }

    // ---- the top screen ------------------------------------------------------------------------------------------

    /// <summary>
    /// Draws the elements sent to the top screen into their own render target, laid out for the top screen (they
    /// position themselves from Game1.uiViewport). Called from the frame composition; returns null when there are none.
    /// </summary>
    public static RenderTarget2D? DrawTopPass(GraphicsDevice device)
    {
        if (!AnyOnTop || !Context.IsWorldReady)
        {
            _topUi?.Dispose();
            _topUi = null;
            return null;
        }
        float ui = Game1.options.uiScale;
        int w = (int)Math.Ceiling(L.Top.Width / ui), h = (int)Math.Ceiling(L.Top.Height / ui);
        if (_topUi == null || _topUi.Width != w || _topUi.Height != h)
        {
            _topUi?.Dispose();
            _topUi = new RenderTarget2D(device, w, h, false, SurfaceFormat.Color, DepthFormat.None, 0, RenderTargetUsage.DiscardContents)
                { Name = "@GameNativeDuo.topUi" };
        }
        device.SetRenderTarget(_topUi);
        device.Clear(Color.Transparent);
        SpriteBatch batch = Game1.spriteBatch;
        Game1.PushUIMode();
        xTile.Dimensions.Rectangle saved = Game1.uiViewport;
        _drawingTop = true;
        try
        {
            Game1.uiViewport = new xTile.Dimensions.Rectangle(saved.X, saved.Y, w, h);
            batch.Begin(SpriteSortMode.Deferred, BlendState.AlphaBlend, SamplerState.PointClamp, null, null);
            foreach (IClickableMenu menu in Game1.onScreenMenus)
            {
                Element? e = For(menu);
                if (e?.Rule?.Screen == DuoScreen.Top)
                    menu.draw(batch);
            }
            batch.End();
        }
        finally
        {
            _drawingTop = false;
            Game1.uiViewport = saved;
            Game1.PopUIMode();
            device.SetRenderTarget(null);
        }
        return _topUi;
    }

    // ---- element list for the editor ---------------------------------------------------------------------------

    /// <summary>The HUD elements with their rectangles in window pixels, for GameNative Duo's layout editor.</summary>
    public static void WriteElements(string path)
    {
        var sb = new StringBuilder(2048);
        sb.Append("{\"engine\":\"stardew-smapi\",\"screen\":{\"w\":").Append(L.Screen.X).Append(",\"h\":").Append(L.Screen.Y).Append("},\"elements\":[");
        Append(sb, "hud", "HUD", -1, DuoScreen.Bottom, L.Bottom, false, first: true);
        foreach (Element e in Elements)
        {
            IClickableMenu? menu = e.MenuType == null ? null : Game1.onScreenMenus.FirstOrDefault(m => m.GetType() == e.MenuType);
            if (e.MenuType != null && menu == null)
                continue;
            DuoScreen screen = e.Rule?.Screen is DuoScreen.Top or DuoScreen.Hidden ? e.Rule.Screen : DuoScreen.Bottom;
            Rectangle ui = menu != null ? Bounds(menu) : BarsBounds();
            UpdateTransform(e, ui);
            Append(sb, e.Id, e.Name, 0, screen, ToWindow(ui, e.Transform, screen), true, first: false);
        }
        sb.Append("]}");
        string tmp = path + ".tmp";
        File.WriteAllText(tmp, sb.ToString());
        File.Move(tmp, path, overwrite: true);
    }

    private static Rectangle ToWindow(Rectangle ui, Matrix transform, DuoScreen screen)
    {
        float scale = Game1.options.uiScale;
        Rectangle region = screen == DuoScreen.Top ? L.Top : L.Bottom;
        Vector2 a = Vector2.Transform(new Vector2(ui.Left, ui.Top), transform);
        Vector2 c = Vector2.Transform(new Vector2(ui.Right, ui.Bottom), transform);
        int x = region.X + (int)Math.Round(Math.Min(a.X, c.X) * scale), y = region.Y + (int)Math.Round(Math.Min(a.Y, c.Y) * scale);
        return new Rectangle(x, y, (int)Math.Round(Math.Abs(c.X - a.X) * scale), (int)Math.Round(Math.Abs(c.Y - a.Y) * scale));
    }

    private static void Append(StringBuilder sb, string id, string name, int parent, DuoScreen screen, Rectangle r, bool graphic, bool first)
    {
        if (!first)
            sb.Append(',');
        sb.Append("{\"id\":\"").Append(id).Append("\",\"name\":\"").Append(name.Replace("\"", "'")).Append("\",\"parent\":").Append(parent)
            .Append(",\"screen\":\"").Append(UiRule.ScreenId(screen)).Append('"')
            .Append(",\"x\":").Append(r.X).Append(",\"y\":").Append(r.Y).Append(",\"w\":").Append(r.Width).Append(",\"h\":").Append(r.Height)
            .Append(",\"graphic\":").Append(graphic ? "true" : "false").Append('}');
    }
}

/// <summary>
/// Re-begins the current SpriteBatch with an extra transform and back, keeping its other settings. MonoGame does not
/// expose them, so they are read from its private fields (MonoGame 3.8 names); if they cannot be found the transforms
/// are skipped (logged once) and the game draws as usual.
/// </summary>
internal static class BatchState
{
    private sealed class Saved
    {
        public SpriteSortMode Sort;
        public BlendState? Blend;
        public SamplerState? Sampler;
        public DepthStencilState? Depth;
        public RasterizerState? Raster;
        public Effect? Effect;
        public Matrix? Matrix;
    }

    private static readonly FieldInfo? SortF = F("_sortMode"), BlendF = F("_blendState"), SamplerF = F("_samplerState"),
        DepthF = F("_depthStencilState"), RasterF = F("_rasterizerState"), EffectF = F("_effect"), BegunF = F("_beginCalled"),
        SpriteEffectF = F("_spriteEffect");

    private static readonly PropertyInfo? MatrixP = SpriteEffectF?.FieldType.GetProperty("TransformMatrix");
    private static readonly Stack<Saved> Stack = new();
    private static bool _warned;

    private static FieldInfo? F(string name) => typeof(SpriteBatch).GetField(name, BindingFlags.Instance | BindingFlags.NonPublic);

    private static bool Available => SortF != null && BlendF != null && SamplerF != null && DepthF != null && RasterF != null &&
        EffectF != null && BegunF != null && SpriteEffectF != null && MatrixP != null;

    /// <summary>Ends the batch and begins it again with <paramref name="m"/> applied first. False (nothing changed) if it cannot.</summary>
    public static bool Push(SpriteBatch b, Matrix m)
    {
        if (!Available)
        {
            if (!_warned)
            {
                _warned = true;
                ModEntry.Log.Log("UI layout: this MonoGame's SpriteBatch is not the expected one; elements cannot be resized or moved", LogLevel.Warn);
            }
            return false;
        }
        if (!(bool)BegunF!.GetValue(b)!)
            return false;
        var s = new Saved
        {
            Sort = (SpriteSortMode)SortF!.GetValue(b)!,
            Blend = (BlendState?)BlendF!.GetValue(b),
            Sampler = (SamplerState?)SamplerF!.GetValue(b),
            Depth = (DepthStencilState?)DepthF!.GetValue(b),
            Raster = (RasterizerState?)RasterF!.GetValue(b),
            Effect = (Effect?)EffectF!.GetValue(b),
            Matrix = (Matrix?)MatrixP!.GetValue(SpriteEffectF!.GetValue(b)),
        };
        b.End();
        b.Begin(s.Sort, s.Blend, s.Sampler, s.Depth, s.Raster, s.Effect, m * (s.Matrix ?? Matrix.Identity));
        Stack.Push(s);
        return true;
    }

    public static void Pop(SpriteBatch b)
    {
        if (Stack.Count == 0)
            return;
        Saved s = Stack.Pop();
        b.End();
        b.Begin(s.Sort, s.Blend, s.Sampler, s.Depth, s.Raster, s.Effect, s.Matrix);
    }
}
