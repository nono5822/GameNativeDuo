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
        Patch(harmony, nameof(Game1.SetWindowSize), postfix: nameof(SetWindowSize_Postfix));
        Patch(harmony, nameof(Game1.PushUIMode), postfix: nameof(PushUIMode_Postfix));
        Patch(harmony, nameof(Game1.ShouldDrawOnBuffer), prefix: nameof(ForceTrue_Prefix));
        Patch(harmony, nameof(Game1.IsRenderingNonNativeUIScale), prefix: nameof(ForceTrue_Prefix));
        Patch(harmony, "renderScreenBuffer", prefix: nameof(RenderScreenBuffer_Prefix));
        Patch(harmony, nameof(Game1.drawMouseCursor), prefix: nameof(DrawMouseCursor_Prefix));
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

    /// <summary>Size the world render target to the top screen and the UI render target to the bottom screen.</summary>
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

            var device = Game1.graphics.GraphicsDevice;
            float zoom = Game1.options.zoomLevel;
            float ui = Game1.options.uiScale;
            __instance.screen = new RenderTarget2D(device, (int)Math.Ceiling(L.Top.Width / zoom), (int)Math.Ceiling(L.Top.Height / zoom),
                false, SurfaceFormat.Color, DepthFormat.None, 0, RenderTargetUsage.PreserveContents) { Name = "@Game1.screen" };
            __instance.uiScreen = new RenderTarget2D(device, (int)Math.Ceiling(L.Bottom.Width / ui), (int)Math.Ceiling(L.Bottom.Height / ui),
                false, SurfaceFormat.Color, DepthFormat.None, 0, RenderTargetUsage.PreserveContents) { Name = "@Game1.uiScreen" };
            Game1.updateViewportForScreenSizeChange(fullscreenChange: false, w, h);

            // Lay open menus out again for the bottom screen.
            var bounds = new Rectangle(Game1.viewport.X, Game1.viewport.Y, Game1.viewport.Width, Game1.viewport.Height);
            Game1.PushUIMode();
            try
            {
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

    /// <summary>The HUD and menus lay themselves out inside the bottom screen.</summary>
    private static void PushUIMode_Postfix()
    {
        if (!ModEntry.Active || !Game1.uiMode)
            return;
        float ui = Game1.options.uiScale;
        Game1.uiViewport = new xTile.Dimensions.Rectangle(Game1.viewport.X, Game1.viewport.Y,
            (int)Math.Ceiling(L.Bottom.Width / ui), (int)Math.Ceiling(L.Bottom.Height / ui));
    }

    /// <summary>Always render through the world/UI render targets so they can be placed on different screens.</summary>
    private static bool ForceTrue_Prefix(ref bool __result)
    {
        if (!ModEntry.Active)
            return true;
        __result = true;
        return false;
    }

    /// <summary>Draw the world render target on the top screen and the UI render target on the bottom screen.</summary>
    private static bool RenderScreenBuffer_Prefix(Game1 __instance, RenderTarget2D target_screen)
    {
        if (!ModEntry.Active || __instance.takingMapScreenshot || LocalMultiplayer.IsLocalMultiplayer() || target_screen == null || target_screen.IsContentLost)
            return true;
        try
        {
            var device = Game1.graphics.GraphicsDevice;
            var batch = Game1.spriteBatch;
            LogDiagnostics(__instance, target_screen);
            device.SetRenderTarget(null);
            device.Clear(Color.Black);
            // Copy the world's colour but keep the window opaque: Stardew's lighting pass can leave the world
            // target's alpha at 0, and GameNative's compositor (unlike a Windows desktop) honours window alpha.
            batch.Begin(SpriteSortMode.Deferred, OpaqueColorOnly, SamplerState.LinearClamp, DepthStencilState.Default, RasterizerState.CullNone);
            batch.Draw(target_screen, new Vector2(L.Top.X, L.Top.Y), target_screen.Bounds, Color.White, 0f, Vector2.Zero, Game1.options.zoomLevel, SpriteEffects.None, 1f);
            batch.Draw(target_screen, new Vector2(L.Bottom.X, L.Bottom.Bottom - target_screen.Height / 4f), target_screen.Bounds, Color.White, 0f, Vector2.Zero, 0.25f, SpriteEffects.None, 1f); // DEBUG thumbnail
            for (int i = 0; i < 4; i++) // DEBUG markers: 200x200 magenta squares at x=0,600 of the top half and bottom half
            {
                int mx = (i % 2) * 600, my = i < 2 ? 0 : L.Bottom.Y;
                batch.Draw(Game1.staminaRect, new Rectangle(mx + 100, my + 100, 200, 200), Color.Magenta);
            }
            // DEBUG: a marker that moves every frame, and the frame number, on the top screen.
            int moving = (int)(_frames * 8 % (L.Top.Width - 120));
            batch.Draw(Game1.staminaRect, new Rectangle(L.Top.X + moving, L.Top.Y + 500, 120, 120), Color.Lime);
            batch.DrawString(Game1.dialogueFont, $"frame {_frames}", new Vector2(L.Top.X + 1000, L.Top.Y + 150), Color.Yellow);
            batch.End();
            if (__instance.uiScreen != null)
            {
                batch.Begin(SpriteSortMode.Deferred, BlendState.AlphaBlend, SamplerState.LinearClamp, DepthStencilState.Default, RasterizerState.CullNone);
                batch.Draw(__instance.uiScreen, new Vector2(L.Bottom.X, L.Bottom.Y), __instance.uiScreen.Bounds, Color.White, 0f, Vector2.Zero, Game1.options.uiScale, SpriteEffects.None, 1f);
                batch.End();
            }
            return false;
        }
        catch (Exception ex)
        {
            ModEntry.Log.Log($"renderScreenBuffer patch failed: {ex}", LogLevel.Error);
            return true;
        }
    }

    private static int _frames;

    /// <summary>Writes the source colour, leaves the (cleared, opaque) destination alpha alone.</summary>
    private static readonly BlendState OpaqueColorOnly = new()
    {
        Name = "GameNativeDuo.OpaqueColorOnly",
        ColorSourceBlend = Blend.One,
        ColorDestinationBlend = Blend.Zero,
        AlphaSourceBlend = Blend.Zero,
        AlphaDestinationBlend = Blend.One,
    };

    /// <summary>Every ~5 s: render target sizes, scales, viewports, and a few world pixels (to tell "not drawn" from "not shown").</summary>
    private static void LogDiagnostics(Game1 game, RenderTarget2D world)
    {
        if (_frames++ % 300 != 0)
            return;
        string sample = "";
        try
        {
            var data = new Color[world.Width * world.Height];
            world.GetData(data);
            int lit = 0, opaque = 0;
            for (int i = 0; i < data.Length; i += 997)
            {
                if (data[i].R + data[i].G + data[i].B > 60)
                    lit++;
                if (data[i].A > 200)
                    opaque++;
            }
            sample = $"lit samples {lit}/{data.Length / 997}, opaque {opaque}";
        }
        catch (Exception ex)
        {
            sample = "sample failed: " + ex.Message;
        }
        ModEntry.Log.Log(
            $"diag: world {world.Width}x{world.Height} ({sample}), ui {game.uiScreen?.Width}x{game.uiScreen?.Height}, " +
            $"zoom {Game1.options.zoomLevel}, uiScale {Game1.options.uiScale}, viewport {Game1.viewport}, uiViewport {Game1.uiViewport}, " +
            $"backbuffer {Game1.graphics.PreferredBackBufferWidth}x{Game1.graphics.PreferredBackBufferHeight}, mode {Game1.gameMode}, fade {Game1.fadeToBlackAlpha}",
            LogLevel.Info);
    }

    /// <summary>No mouse cursor while the pointer is on the bottom (touch) screen.</summary>
    private static bool DrawMouseCursor_Prefix()
    {
        if (!ModEntry.Active)
            return true;
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
