using System;
using HarmonyLib;
using Microsoft.Xna.Framework;
using Microsoft.Xna.Framework.Graphics;
using StardewModdingAPI;
using StardewValley;
using StardewValley.Menus;

namespace GameNativeDuo.StardewSplit;

/// <summary>
/// The title screen as one scene over both screens, like a dual-screen handheld's own title:
/// - top screen: the sky, the sign (logo) with its birds and leaves, and the opening logos, laid out for the top
///   screen. The sign stays there while the load list or character creation is open below.
/// - bottom screen: the same scene further down (the sign scrolled out of it), with the buttons, the load list and
///   every other submenu, exactly where they were (so taps need no change). During the opening logos it shows the
///   same white, and a tap anywhere skips them (the logo they skip on is on the top screen now).
/// The top screen is a second draw of the title menu into its own render target, only while the title is shown.
/// </summary>
internal static class TitleScreen
{
    /// <summary>How far down (UI px) the bottom screen looks into the scene: past the sign (~560 px tall at 3x).</summary>
    private const float BottomScroll = 720f;

    private static readonly AccessTools.FieldRef<IClickableMenu> SubMenu = AccessTools.StaticFieldRefAccess<IClickableMenu>(
        AccessTools.Field(typeof(TitleMenu), "_subMenu"));

    private static readonly AccessTools.FieldRef<TitleMenu, string> WhichSubMenu = AccessTools.FieldRefAccess<TitleMenu, string>("whichSubMenu");
    private static readonly AccessTools.FieldRef<TitleMenu, int> AmuzioTimer = AccessTools.FieldRefAccess<TitleMenu, int>("amuzioTimer");

    /// <summary>How far (UI px) the top screen's sky colour reaches into the bottom screen before fading out.</summary>
    private const int SkyBlend = 380;

    private static RenderTarget2D? _top;

    /// <summary>The title's sky gradient (Cursors 703,1912, 264 px), read once.</summary>
    private static Color[]? _sky;

    /// <summary>The top screen's height (UI px) as last drawn, for the sky colour at its bottom edge.</summary>
    private static int _topHeight = 1080;

    /// <summary>True while the top screen's copy is drawn (no buttons, submenus or cursor in it).</summary>
    internal static bool DrawingTop { get; private set; }

    public static void Apply(Harmony harmony)
    {
        harmony.Patch(AccessTools.Method(typeof(TitleMenu), nameof(TitleMenu.draw), new[] { typeof(SpriteBatch) }),
            prefix: new HarmonyMethod(typeof(TitleScreen), nameof(Draw_Prefix)),
            finalizer: new HarmonyMethod(typeof(TitleScreen), nameof(Draw_Finalizer)));
        harmony.Patch(AccessTools.Method(typeof(TitleMenu), nameof(TitleMenu.receiveLeftClick)),
            prefix: new HarmonyMethod(typeof(TitleScreen), nameof(Click_Prefix)));
    }

    /// <summary>The bottom screen's state while the game draws the title menu (see <see cref="Draw_Finalizer"/>).</summary>
    private struct Saved
    {
        public bool Changed;
        public float ViewportY;
        public int LogoFade, FadeFromWhite, Amuzio;
    }

    private static void Draw_Prefix(TitleMenu __instance, out Saved __state)
    {
        __state = default;
        if (!ModEntry.Active || DrawingTop)
            return;
        // Bottom screen: the scene below the top screen's, without the sign or the opening logos (drawn on top).
        __state = new Saved
        {
            Changed = true,
            ViewportY = __instance.viewportY,
            LogoFade = __instance.logoFadeTimer,
            FadeFromWhite = __instance.fadeFromWhiteTimer,
            Amuzio = AmuzioTimer(__instance),
        };
        // Never past the scene's lower end (viewportY 0, where the opening pan starts).
        __instance.viewportY = Math.Min(__instance.viewportY + BottomScroll, 0f);
        __instance.logoFadeTimer = __instance.fadeFromWhiteTimer = AmuzioTimer(__instance) = 0;
    }

    private static Exception? Draw_Finalizer(TitleMenu __instance, SpriteBatch b, Saved __state, Exception? __exception)
    {
        if (!__state.Changed)
            return __exception;
        __instance.viewportY = __state.ViewportY;
        __instance.logoFadeTimer = __state.LogoFade;
        __instance.fadeFromWhiteTimer = __state.FadeFromWhite;
        AmuzioTimer(__instance) = __state.Amuzio;
        if (__exception == null)
        {
            // One sky over both screens: the bottom screen starts with the colour the top screen ends with.
            if (SubMenu() == null)
                DrawSkyBlend(b, __instance.width, __state.ViewportY);
            // The white the opening logos sit on, as the game fades it on the top screen.
            float white = __state.Amuzio > 0 || __state.LogoFade > 0 ? 1f : Math.Min(1f, __state.FadeFromWhite / 2000f);
            if (white > 0f)
                b.Draw(Game1.staminaRect, new Rectangle(0, 0, __instance.width, __instance.height), Color.White * white);
        }
        return __exception;
    }

    private static void DrawSkyBlend(SpriteBatch b, int width, float viewportY)
    {
        // Only once the top screen's lower edge is sky: during the opening pan it is still the hills.
        int row = SkyRowAtTopEdge(viewportY);
        float strength = Math.Clamp((240 - row) / 24f, 0f, 1f);
        if (strength <= 0f || _sky == null)
            return;
        Color edge = _sky[Math.Clamp(row, 0, 263)];
        const int strip = 10;
        for (int y = 0; y < SkyBlend; y += strip)
        {
            float alpha = 1f - (float)y / SkyBlend;
            b.Draw(Game1.staminaRect, new Rectangle(0, y, width, strip), edge * (alpha * alpha * strength));
        }
    }

    /// <summary>
    /// The sky gradient's row at the top screen's bottom edge (beyond 263: below the sky). TitleMenu.draw stretches the gradient over
    /// y = -300*3 - viewportY*0.66 .. + (300*3 + height - 120*3); this is the row at y = height of the top screen.
    /// </summary>
    private static int SkyRowAtTopEdge(float viewportY)
    {
        try
        {
            if (_sky == null)
            {
                var column = new Color[264];
                Game1.mouseCursors.GetData(0, new Rectangle(703, 1912, 1, 264), column, 0, 264);
                _sky = column;
            }
            float top = -900f - viewportY * 0.66f, span = 900f + _topHeight - 360f;
            return (int)((_topHeight - top) / span * 264f);
        }
        catch (Exception)
        {
            return int.MaxValue;
        }
    }

    /// <summary>During the opening logos, a tap anywhere on the bottom screen skips them.</summary>
    private static void Click_Prefix(TitleMenu __instance, ref int x, ref int y)
    {
        if (ModEntry.Active && __instance.logoFadeTimer > 0 && __instance.skipButton != null)
        {
            x = __instance.skipButton.bounds.Center.X;
            y = __instance.skipButton.bounds.Center.Y;
        }
    }

    /// <summary>
    /// Draws the top screen's copy of the title menu (UI units, top screen size) and returns it, or null when the
    /// title is not shown. Called with no render target set, after the game's own UI pass.
    /// </summary>
    public static RenderTarget2D? DrawTop(Layout layout)
    {
        if (Game1.activeClickableMenu is not TitleMenu menu)
        {
            Release();
            return null;
        }
        var device = Game1.graphics.GraphicsDevice;
        float ui = Game1.options.uiScale;
        int w = (int)Math.Ceiling(layout.Top.Width / ui), h = (int)Math.Ceiling(layout.Top.Height / ui);
        if (_top == null || _top.Width != w || _top.Height != h || _top.IsDisposed)
        {
            _top?.Dispose();
            _top = new RenderTarget2D(device, w, h, false, SurfaceFormat.Color, DepthFormat.None) { Name = "GameNativeDuo.TitleTop" };
        }

        _topHeight = h;
        int width = menu.width, height = menu.height, buttonsToShow = menu.buttonsToShow, globalXOffset = menu.globalXOffset;
        bool transitioning = menu.isTransitioningButtons;
        bool muteVisible = menu.muteMusicButton?.visible ?? false, windowedVisible = menu.windowedButton?.visible ?? false;
        IClickableMenu subMenu = SubMenu();
        string which = WhichSubMenu(menu);
        xTile.Dimensions.Rectangle uiViewport = Game1.uiViewport;
        device.SetRenderTarget(_top);
        device.Clear(Color.Black);
        DrawingTop = true;
        try
        {
            // The scene and the sign at the top screen's size; no buttons, submenu or cursor (all on the bottom).
            menu.width = w;
            menu.height = h;
            Game1.uiViewport = new xTile.Dimensions.Rectangle(0, 0, w, h);
            SubMenu() = null!;
            WhichSubMenu(menu) = "";
            menu.buttonsToShow = 0;
            menu.isTransitioningButtons = true; // hides the about and language buttons
            menu.globalXOffset = 0; // the sign stays while submenus are open below
            if (menu.muteMusicButton != null)
                menu.muteMusicButton.visible = false;
            if (menu.windowedButton != null)
                menu.windowedButton.visible = false;
            var batch = Game1.spriteBatch;
            batch.Begin(SpriteSortMode.Deferred, BlendState.AlphaBlend, SamplerState.PointClamp, DepthStencilState.Default, RasterizerState.CullNone);
            try
            {
                menu.draw(batch);
            }
            finally
            {
                batch.End();
            }
        }
        catch (Exception ex)
        {
            ModEntry.Log.LogOnce($"GameNative Duo: title screen (top) failed: {ex}", LogLevel.Error);
            return null;
        }
        finally
        {
            DrawingTop = false;
            menu.width = width;
            menu.height = height;
            Game1.uiViewport = uiViewport;
            SubMenu() = subMenu;
            WhichSubMenu(menu) = which;
            menu.buttonsToShow = buttonsToShow;
            menu.isTransitioningButtons = transitioning;
            menu.globalXOffset = globalXOffset;
            if (menu.muteMusicButton != null)
                menu.muteMusicButton.visible = muteVisible;
            if (menu.windowedButton != null)
                menu.windowedButton.visible = windowedVisible;
            device.SetRenderTarget(null);
        }
        return _top;
    }

    /// <summary>Frees the top screen's target once the title is gone.</summary>
    public static void Release()
    {
        _top?.Dispose();
        _top = null;
    }
}
