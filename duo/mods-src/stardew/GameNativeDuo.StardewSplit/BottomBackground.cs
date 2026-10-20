using System;
using System.IO;
using Microsoft.Xna.Framework;
using Microsoft.Xna.Framework.Graphics;
using StardewModdingAPI;
using StardewValley;

namespace GameNativeDuo.StardewSplit;

/// <summary>
/// What the bottom screen shows behind the HUD and menus (the user's choice in GameNative Duo's layout editor): the
/// world continuing below the top screen (default), the world blurred, a solid color, or an image. All on the GPU, in
/// the frame composition: the blur is two downsamples into small targets, scaled back up with linear filtering.
/// </summary>
internal static class BottomBackground
{
    private static Background _bg = Background.Default;
    private static Texture2D? _image;
    private static RenderTarget2D? _blurA, _blurB, _blurC;
    private static bool _imageWanted;
    private static DateTime _imageTime;

    private static Layout L => ModEntry.Layout!;

    /// <summary>New settings from ui.json. Game thread (textures are created there).</summary>
    public static void Set(Background bg)
    {
        _bg = bg;
        _imageWanted = bg.Mode == BackgroundMode.Image && bg.HasImage;
        if (!_imageWanted)
        {
            _image?.Dispose();
            _image = null;
            _imageTime = default;
        }
        if (bg.Mode != BackgroundMode.Blur)
            DisposeBlur();
    }

    /// <summary>
    /// Before the frame is composed (render targets can only be switched before drawing to the window): the blurred
    /// copy of the bottom screen's world, and the background image if it changed on disk.
    /// </summary>
    public static void Prepare(GraphicsDevice device, RenderTarget2D world, Rectangle bottomSource)
    {
        if (_imageWanted)
            LoadImage(device);
        if (_bg.Mode != BackgroundMode.Blur || bottomSource.Width <= 0 || bottomSource.Height <= 0)
            return;
        // Blur strength picks the second target's size: a sixth of the source at 0, a twenty-fourth at 1.
        int divA = 4, divB = (int)Math.Round(4 * (1.5f + 4.5f * _bg.Blur));
        Ensure(ref _blurA, device, Math.Max(1, bottomSource.Width / divA), Math.Max(1, bottomSource.Height / divA));
        Ensure(ref _blurB, device, Math.Max(1, bottomSource.Width / divB), Math.Max(1, bottomSource.Height / divB));
        SpriteBatch batch = Game1.spriteBatch;
        device.SetRenderTarget(_blurA);
        batch.Begin(SpriteSortMode.Deferred, BlendState.Opaque, SamplerState.LinearClamp, DepthStencilState.None, RasterizerState.CullNone);
        batch.Draw(world, _blurA!.Bounds, bottomSource, Color.White);
        batch.End();
        device.SetRenderTarget(_blurB);
        batch.Begin(SpriteSortMode.Deferred, BlendState.Opaque, SamplerState.LinearClamp, DepthStencilState.None, RasterizerState.CullNone);
        batch.Draw(_blurA, _blurB!.Bounds, Color.White);
        batch.End();
        // Two passes of a four-tap blur on the tiny target (a few thousand pixels): soft, not blocky, when scaled up.
        Ensure(ref _blurC, device, _blurB.Width, _blurB.Height);
        Taps(device, batch, _blurB, _blurC!, Straight);
        Taps(device, batch, _blurC!, _blurB, Diagonal);
    }

    private static readonly Point[] Straight = { new(1, 0), new(-1, 0), new(0, 1), new(0, -1) };
    private static readonly Point[] Diagonal = { new(1, 1), new(-1, -1), new(1, -1), new(-1, 1) };

    /// <summary>The average of <paramref name="source"/> shifted by each offset (the n-th copy at 1/n alpha averages exactly).</summary>
    private static void Taps(GraphicsDevice device, SpriteBatch batch, RenderTarget2D source, RenderTarget2D target, Point[] offsets)
    {
        device.SetRenderTarget(target);
        batch.Begin(SpriteSortMode.Deferred, BlendState.AlphaBlend, SamplerState.LinearClamp, DepthStencilState.None, RasterizerState.CullNone);
        for (int i = 0; i < offsets.Length; i++)
            batch.Draw(source, new Vector2(offsets[i].X, offsets[i].Y), i == 0 ? Color.White : Color.White * (1f / (i + 1)));
        batch.End();
    }

    /// <summary>Draws the bottom screen's background (inside the frame's opaque batch). Returns false for the default.</summary>
    public static bool Draw(SpriteBatch batch, RenderTarget2D world, Rectangle bottomSource)
    {
        switch (_bg.Mode)
        {
            case BackgroundMode.Blur when _blurB != null:
                batch.Draw(_blurB, L.Bottom, Color.White);
                return true;
            case BackgroundMode.Color:
                batch.Draw(Game1.staminaRect, L.Bottom, _bg.Color);
                return true;
            case BackgroundMode.Image when _image != null:
                batch.Draw(_image, L.Bottom, Cover(_image.Width, _image.Height, L.Bottom.Width, L.Bottom.Height), Color.White);
                return true;
            case BackgroundMode.Image:
                // Not loaded (yet): the color, not the world, so the choice shows at once.
                batch.Draw(Game1.staminaRect, L.Bottom, _bg.Color);
                return true;
            default:
                batch.Draw(world, L.Bottom, bottomSource, Color.White);
                return true;
        }
    }

    /// <summary>The dim layer over the background, under the UI (inside an alpha-blended batch).</summary>
    public static void DrawDim(SpriteBatch batch)
    {
        if (_bg.Dim > 0f && _bg.Mode != BackgroundMode.Color)
            batch.Draw(Game1.staminaRect, L.Bottom, Color.Black * _bg.Dim);
    }

    /// <summary>The centred part of an image that covers a <paramref name="w"/> x <paramref name="h"/> area.</summary>
    private static Rectangle Cover(int imageW, int imageH, int w, int h)
    {
        float scale = Math.Max((float)w / imageW, (float)h / imageH);
        int sw = (int)Math.Round(w / scale), sh = (int)Math.Round(h / scale);
        return new Rectangle((imageW - sw) / 2, (imageH - sh) / 2, sw, sh);
    }

    private static void LoadImage(GraphicsDevice device)
    {
        try
        {
            string path = Background.ImagePath;
            if (!File.Exists(path))
                return;
            DateTime time = File.GetLastWriteTimeUtc(path);
            if (_image != null && time == _imageTime)
                return;
            _imageTime = time;
            using FileStream stream = File.OpenRead(path);
            Texture2D image = Texture2D.FromStream(device, stream);
            _image?.Dispose();
            _image = image;
        }
        catch (Exception ex)
        {
            _imageWanted = false;
            ModEntry.Log.Log($"Background image could not be loaded: {ex.Message}", LogLevel.Warn);
        }
    }

    private static void Ensure(ref RenderTarget2D? target, GraphicsDevice device, int w, int h)
    {
        if (target != null && target.Width == w && target.Height == h && !target.IsDisposed)
            return;
        target?.Dispose();
        target = new RenderTarget2D(device, w, h, false, SurfaceFormat.Color, DepthFormat.None, 0, RenderTargetUsage.DiscardContents)
            { Name = "@GameNativeDuo.blur" };
    }

    private static void DisposeBlur()
    {
        _blurA?.Dispose();
        _blurB?.Dispose();
        _blurC?.Dispose();
        _blurA = _blurB = _blurC = null;
    }
}
