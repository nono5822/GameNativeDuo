using System;
using System.Reflection;
using HarmonyLib;
using Microsoft.Xna.Framework;
using Microsoft.Xna.Framework.Graphics;
using StardewModdingAPI;
using StardewValley;
using StardewValley.Menus;

namespace GameNativeDuo.StardewSplit;

/// <summary>
/// Harmony patches on <see cref="Game1"/>. Each one only acts while <see cref="ModEntry.Active"/>; otherwise the
/// game behaves exactly as without the mod. Every patch catches its own errors so a bug here cannot crash the game.
/// </summary>
internal static class Patches
{
    private static Layout L => ModEntry.Layout!;

    public static void Apply(Harmony harmony)
    {
        Patch(harmony, nameof(Game1.updateViewportForScreenSizeChange), prefix: nameof(UpdateViewport_Prefix));
        Patch(harmony, nameof(Game1.SetWindowSize), prefix: nameof(SetWindowSize_Prefix), postfix: nameof(SetWindowSize_Postfix));
        Patch(harmony, nameof(Game1.PushUIMode), postfix: nameof(PushUIMode_Postfix));
        // Not Game1.ShouldDrawOnBuffer: forcing it on the title and loading screens hung the GPU (kgsl "GPU hang
        // detected" within a minute of launch, every time); SMAPI already turns it on once a save is loaded.
        Patch(harmony, nameof(Game1.IsRenderingNonNativeUIScale), prefix: nameof(ForceTrue_Prefix));
        Patch(harmony, "renderScreenBuffer", prefix: nameof(RenderScreenBuffer_Prefix));
        Patch(harmony, "allocateLightmap", prefix: nameof(AllocateLightmap_Prefix));
        harmony.Patch(
            AccessTools.Method(typeof(Game1), nameof(Game1.DrawWorld)),
            prefix: new HarmonyMethod(typeof(Patches), nameof(DrawWorld_Prefix)),
            finalizer: new HarmonyMethod(typeof(Patches), nameof(DrawWorld_Finalizer))
        );
        Patch(harmony, nameof(Game1.drawMouseCursor), prefix: nameof(DrawMouseCursor_Prefix));
        // Menus draw their own cursor (the inventory showed it next to a dragged item).
        harmony.Patch(AccessTools.Method(typeof(IClickableMenu), nameof(IClickableMenu.drawMouse)),
            prefix: new HarmonyMethod(typeof(Patches), nameof(MenuDrawMouse_Prefix)));
        foreach (string name in new[] { nameof(Game1.getMouseX), nameof(Game1.getOldMouseX) })
            Patch(harmony, name, postfix: nameof(MouseX_Postfix), args: new[] { typeof(bool) });
        foreach (string name in new[] { nameof(Game1.getMouseY), nameof(Game1.getOldMouseY) })
            Patch(harmony, name, postfix: nameof(MouseY_Postfix), args: new[] { typeof(bool) });
    }


    private static void Patch(Harmony harmony, string method, string? prefix = null, string? postfix = null, Type[]? args = null)
    {
        var original = args == null ? AccessTools.Method(typeof(Game1), method) : AccessTools.Method(typeof(Game1), method, args);
        harmony.Patch(
            original,
            prefix: prefix == null ? null : new HarmonyMethod(typeof(Patches), prefix),
            postfix: postfix == null ? null : new HarmonyMethod(typeof(Patches), postfix)
        );
    }

    /// <summary>The world viewport (and lightmap) only covers the top screen.</summary>
    private static void UpdateViewport_Prefix(ref int width, ref int height)
    {
        if (ModEntry.Layout == null)
            return;
        ModEntry.Active = L.Matches(width, height) || (ModEntry.Active && width == L.Top.Width && height == L.Top.Height);
        if (ModEntry.Active)
        {
            width = L.Top.Width;
            height = L.Top.Height;
        }
    }

    /// <summary>
    /// Skip a resize to the size the window already has (Wine reports one now and then, e.g. around taps): it would
    /// reallocate every full-window render target, a visible hitch. Zoom and UI scale changes still go through, since
    /// they change the targets' sizes.
    /// </summary>
    private static bool SetWindowSize_Prefix(Game1 __instance, int w, int h)
    {
        try
        {
            if (!ModEntry.Active || !L.Matches(w, h) || __instance.screen == null || __instance.uiScreen == null)
                return true;
            float zoom = Game1.options.zoomLevel, ui = Game1.options.uiScale;
            bool same = __instance.screen.Width == (int)Math.Ceiling(L.World.X / zoom) && __instance.screen.Height == (int)Math.Ceiling(L.World.Y / zoom)
                && __instance.uiScreen.Width == (int)Math.Ceiling(L.Bottom.Width / ui) && __instance.uiScreen.Height == (int)Math.Ceiling(L.Bottom.Height / ui)
                && Game1.graphics.PreferredBackBufferWidth == w && Game1.graphics.PreferredBackBufferHeight == h;
            return !same;
        }
        catch (Exception)
        {
            return true;
        }
    }

    /// <summary>Size the world render target to the whole window (both screens) and the UI render target to the bottom screen.</summary>
    private static void SetWindowSize_Postfix(Game1 __instance, int w, int h)
    {
        try
        {
            bool wasActive = ModEntry.Active;
            ModEntry.Active = L.Matches(w, h);
            if (ModEntry.Active != wasActive)
                ModEntry.Log.Log($"Screen split {(ModEntry.Active ? "on" : "off")} (window {w}x{h})", LogLevel.Info);
            if (!ModEntry.Active)
                return;

            CreateRenderTargets(__instance);
            Game1.updateViewportForScreenSizeChange(fullscreenChange: false, w, h);

            // Lay open menus out again for the bottom screen, in UI units (menus centre themselves in these bounds).
            Game1.PushUIMode();
            try
            {
                var bounds = new Rectangle(0, 0, Game1.uiViewport.Width, Game1.uiViewport.Height);
                foreach (IClickableMenu menu in Game1.onScreenMenus)
                    menu.gameWindowSizeChanged(bounds, bounds);
                Game1.activeClickableMenu?.gameWindowSizeChanged(bounds, bounds);
            }
            finally
            {
                Game1.PopUIMode();
            }
        }
        catch (Exception ex)
        {
            ModEntry.Log.Log($"SetWindowSize patch failed: {ex}", LogLevel.Error);
        }
    }

    /// <summary>
    /// World render target for both screens plus the hidden gap rows, UI render target for the bottom screen, and a
    /// lightmap matching the world. Called on window resize and when the gap changes.
    /// </summary>
    public static void CreateRenderTargets(Game1 game)
    {
        var device = Game1.graphics.GraphicsDevice;
        float zoom = Game1.options.zoomLevel;
        float ui = Game1.options.uiScale;
        RenderTarget2D? oldScreen = game.screen, oldUi = game.uiScreen;
        game.screen = new RenderTarget2D(device, (int)Math.Ceiling(L.World.X / zoom), (int)Math.Ceiling(L.World.Y / zoom),
            false, SurfaceFormat.Color, DepthFormat.None, 0, RenderTargetUsage.PreserveContents) { Name = "@Game1.screen" };
        game.uiScreen = new RenderTarget2D(device, (int)Math.Ceiling(L.Bottom.Width / ui), (int)Math.Ceiling(L.Bottom.Height / ui),
            false, SurfaceFormat.Color, DepthFormat.None, 0, RenderTargetUsage.PreserveContents) { Name = "@Game1.uiScreen" };
        oldScreen?.Dispose();
        oldUi?.Dispose();
        AllocateLightmap(L.World.X, L.World.Y);
    }

    /// <summary>True when the world render target cannot hold <see cref="Layout.World"/> (sliders only grow it).</summary>
    public static bool WorldTargetTooSmall(Game1 game)
    {
        float zoom = Game1.options.zoomLevel;
        return game.screen == null || game.screen.Width < (int)Math.Ceiling(L.World.X / zoom) || game.screen.Height < (int)Math.Ceiling(L.World.Y / zoom);
    }

    private static readonly MethodInfo AllocateLightmapMethod = AccessTools.Method(typeof(Game1), "allocateLightmap");

    private static void AllocateLightmap(int width, int height) => AllocateLightmapMethod.Invoke(null, new object[] { width, height });

    /// <summary>The HUD and menus lay themselves out inside the bottom screen.</summary>
    private static void PushUIMode_Postfix()
    {
        if (!ModEntry.Active || !Game1.uiMode)
            return;
        float ui = Game1.options.uiScale;
        Game1.uiViewport = new xTile.Dimensions.Rectangle(Game1.viewport.X, Game1.viewport.Y,
            (int)Math.Ceiling(L.Bottom.Width / ui), (int)Math.Ceiling(L.Bottom.Height / ui));
    }

    /// <summary>Always draw the HUD and menus through the UI render target, so it can be placed on the bottom screen.</summary>
    private static bool ForceTrue_Prefix(ref bool __result)
    {
        if (!ModEntry.Active)
            return true;
        __result = true;
        return false;
    }

    /// <summary>
    /// The camera (centering, clamping, everything the game updates) works on the top screen's viewport, so the
    /// farmer stays centred up there. Only while the world is drawn, the viewport grows to the whole window so the
    /// bottom screen shows the world continuing below the top screen, behind the HUD and menus.
    /// </summary>
    private static void DrawWorld_Prefix(out xTile.Dimensions.Rectangle? __state)
    {
        __state = null;
        if (!ModEntry.Active || Game1.game1.takingMapScreenshot)
            return;
        try
        {
            __state = Game1.viewport;
            Game1.viewport = WorldViewport(Game1.viewport);
        }
        catch (Exception ex)
        {
            ModEntry.Log.Log($"DrawWorld patch failed: {ex}", LogLevel.Error);
        }
    }

    private static Exception? DrawWorld_Finalizer(Exception? __exception, xTile.Dimensions.Rectangle? __state)
    {
        if (__state.HasValue)
            Game1.viewport = __state.Value;
        return __exception;
    }

    /// <summary>The world viewport (window plus gap rows) around the top screen's <paramref name="top"/> viewport.</summary>
    private static xTile.Dimensions.Rectangle WorldViewport(xTile.Dimensions.Rectangle top)
    {
        float zoom = Game1.options.zoomLevel;
        return new xTile.Dimensions.Rectangle(
            top.X - (int)Math.Round(L.Top.X / zoom), top.Y - (int)Math.Round(L.Top.Y / zoom),
            (int)Math.Ceiling(L.World.X / zoom), (int)Math.Ceiling(L.World.Y / zoom));
    }

    /// <summary>The lightmap covers the whole world target, like the world it lights.</summary>
    private static void AllocateLightmap_Prefix(ref int width, ref int height)
    {
        if (!ModEntry.Active)
            return;
        width = L.World.X;
        height = L.World.Y;
    }

    /// <summary>Draw the world across both screens, the UI on the bottom screen, and screen fades on both.</summary>
    private static bool RenderScreenBuffer_Prefix(Game1 __instance, RenderTarget2D target_screen)
    {
        if (!ModEntry.Active || __instance.takingMapScreenshot || LocalMultiplayer.IsLocalMultiplayer() || target_screen?.IsContentLost == true)
            return true;
        if (target_screen == null)
            return DrawUiOnly(__instance);
        try
        {
            var device = Game1.graphics.GraphicsDevice;
            var batch = Game1.spriteBatch;
            // HUD elements the user sent to the top screen, laid out for it (nothing to do when there are none).
            RenderTarget2D? topUi = HudLayout.DrawTopPass(device);
            float zoom = Game1.options.zoomLevel;
            var world = L.BottomWorld;
            var bottomSource = new Rectangle((int)Math.Round(world.X / zoom), (int)Math.Round(world.Y / zoom),
                (int)Math.Round(world.Width / zoom), (int)Math.Round(world.Height / zoom));
            BottomBackground.Prepare(device, target_screen, bottomSource);
            device.SetRenderTarget(null);
            device.Clear(Color.Black);
            // Copy the world's colour but keep the window opaque: Stardew's lighting pass can leave the world
            // target's alpha at 0, and GameNative's compositor (unlike a Windows desktop) honours window alpha.
            batch.Begin(SpriteSortMode.Deferred, OpaqueColorOnly, SamplerState.LinearClamp, DepthStencilState.Default, RasterizerState.CullNone);
            // Top screen: the world's first rows. Bottom screen: the world after the rows hidden by the hinge,
            // magnified to the bottom screen's pixel density so both show it at the same physical size.
            var topRows = new Rectangle(0, 0, target_screen.Width, (int)Math.Ceiling(L.Top.Bottom / zoom));
            batch.Draw(target_screen, Vector2.Zero, topRows, Color.White, 0f, Vector2.Zero, zoom, SpriteEffects.None, 1f);
            // The bottom screen's background: the world (default), blurred, a color or an image (user's choice).
            BottomBackground.Draw(batch, target_screen, bottomSource);
            batch.End();
            batch.Begin(SpriteSortMode.Deferred, BlendState.AlphaBlend, SamplerState.LinearClamp, DepthStencilState.Default, RasterizerState.CullNone);
            BottomBackground.DrawDim(batch);
            // The game draws its fade to black in the UI layer, which only covers the bottom screen.
            if ((Game1.fadeToBlack || Game1.globalFade) && Game1.fadeToBlackAlpha > 0f)
            {
                float alpha = Game1.gameMode == 0 ? 1f - Game1.fadeToBlackAlpha : Game1.fadeToBlackAlpha;
                batch.Draw(Game1.fadeToBlackRect, L.Top, Color.Black * alpha);
            }
            if (__instance.uiScreen != null)
                batch.Draw(__instance.uiScreen, new Vector2(L.Bottom.X, L.Bottom.Y), __instance.uiScreen.Bounds, Color.White, 0f, Vector2.Zero, Game1.options.uiScale, SpriteEffects.None, 1f);
            if (topUi != null)
                batch.Draw(topUi, new Vector2(L.Top.X, L.Top.Y), topUi.Bounds, Color.White, 0f, Vector2.Zero, Game1.options.uiScale, SpriteEffects.None, 1f);
            batch.End();
            return false;
        }
        catch (Exception ex)
        {
            ModEntry.Log.Log($"renderScreenBuffer patch failed: {ex}", LogLevel.Error);
            return true;
        }
    }

    /// <summary>
    /// Title and loading screens (no save loaded, so no world render target): the game drew straight to the window;
    /// put the UI layer on the bottom screen over it, as the game would at the window's top-left, and the title
    /// screen's top half (<see cref="TitleScreen"/>) on the top screen, fading with the game's fades.
    /// </summary>
    private static bool DrawUiOnly(Game1 game)
    {
        if (game.uiScreen == null)
            return true;
        try
        {
            var batch = Game1.spriteBatch;
            RenderTarget2D? top = TitleScreen.DrawTop(L);
            Game1.graphics.GraphicsDevice.SetRenderTarget(null);
            if (top != null)
                Game1.graphics.GraphicsDevice.Clear(Color.Black);
            batch.Begin(SpriteSortMode.Deferred, BlendState.AlphaBlend, SamplerState.LinearClamp, DepthStencilState.Default, RasterizerState.CullNone);
            if (top != null)
            {
                batch.Draw(top, new Vector2(L.Top.X, L.Top.Y), top.Bounds, Color.White, 0f, Vector2.Zero, Game1.options.uiScale, SpriteEffects.None, 1f);
                if ((Game1.fadeToBlack || Game1.globalFade) && Game1.fadeToBlackAlpha > 0f)
                    batch.Draw(Game1.fadeToBlackRect, L.Top, Color.Black * (Game1.gameMode == 0 ? 1f - Game1.fadeToBlackAlpha : Game1.fadeToBlackAlpha));
            }
            batch.Draw(game.uiScreen, new Vector2(L.Bottom.X, L.Bottom.Y), game.uiScreen.Bounds, Color.White, 0f, Vector2.Zero, Game1.options.uiScale, SpriteEffects.None, 1f);
            batch.End();
            return false;
        }
        catch (Exception ex)
        {
            ModEntry.Log.Log($"renderScreenBuffer patch failed: {ex}", LogLevel.Error);
            return true;
        }
    }

    /// <summary>Writes the source colour, leaves the (cleared, opaque) destination alpha alone.</summary>
    private static readonly BlendState OpaqueColorOnly = new()
    {
        Name = "GameNativeDuo.OpaqueColorOnly",
        ColorSourceBlend = Blend.One,
        ColorDestinationBlend = Blend.Zero,
        AlphaSourceBlend = Blend.Zero,
        AlphaDestinationBlend = Blend.One,
    };

    /// <summary>No menu cursor while a finger drives the game (held items are drawn separately and stay).</summary>
    private static bool MenuDrawMouse_Prefix() => !(ModEntry.Active && (TouchInput.Driving || TitleScreen.DrawingTop));

    /// <summary>No mouse cursor while the pointer is on the bottom (touch) screen or a finger drives it.</summary>
    private static bool DrawMouseCursor_Prefix()
    {
        if (!ModEntry.Active)
            return true;
        if (TouchInput.Driving)
            return false;
        var mouse = Game1.input.GetMouseState();
        return !L.Bottom.Contains(mouse.X, mouse.Y);
    }

    /// <summary>UI coordinates are relative to the bottom screen.</summary>
    private static void MouseX_Postfix(bool ui_scale, ref int __result, MethodBase __originalMethod)
    {
        if (!ModEntry.Active || !ui_scale)
            return;
        int raw = __originalMethod.Name.Contains("Old") ? Game1.oldMouseState.X : Game1.input.GetMouseState().X;
        __result = (int)((raw - L.Bottom.X) / Game1.options.uiScale);
    }

    private static void MouseY_Postfix(bool ui_scale, ref int __result, MethodBase __originalMethod)
    {
        if (!ModEntry.Active || !ui_scale)
            return;
        int raw = __originalMethod.Name.Contains("Old") ? Game1.oldMouseState.Y : Game1.input.GetMouseState().Y;
        __result = (int)((raw - L.Bottom.Y) / Game1.options.uiScale);
    }
}
