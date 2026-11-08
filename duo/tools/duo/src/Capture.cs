using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Text;

namespace GameNativeDuo.Tool;

/// <summary>
/// The sim's screen pictures: the game window (found by title, else by its size matching layout.json) captured and
/// cropped to the top or bottom part, as PNG. Linux: X11 (Proton games run under Xwayland or X11); Windows:
/// PrintWindow. Null when nothing can be captured (the editor then shows outlines only).
/// </summary>
public static unsafe class Capture
{
    public static byte[]? Screen(SimHost sim, string which, string? title)
    {
        try
        {
            var r = sim.Rect(which);
            var image = OperatingSystem.IsWindows() ? Win.Grab(sim.ScreenW, sim.ScreenH, title) : X11.Grab(sim.ScreenW, sim.ScreenH, title);
            if (image == null)
                return null;
            var (w, h, bgra) = image.Value;
            int cx = Math.Clamp(r[0], 0, w), cy = Math.Clamp(r[1], 0, h);
            int cw = Math.Clamp(r[2], 0, w - cx), ch = Math.Clamp(r[3], 0, h - cy);
            return cw == 0 || ch == 0 ? null : Png(bgra, w, cx, cy, cw, ch);
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>A whole window by title, as PNG (`duo capture`).</summary>
    public static byte[]? Window(string title)
    {
        var image = OperatingSystem.IsWindows() ? Win.Grab(0, 0, title) : X11.Grab(0, 0, title);
        if (image == null)
            return null;
        var (w, h, bgra) = image.Value;
        return Png(bgra, w, 0, 0, w, h);
    }

    // ---- PNG (no library: zlib from the BCL, CRC32 here) --------------------------------------------------------------

    public static byte[] Png(byte[] bgra, int stride, int x0, int y0, int w, int h)
    {
        using var raw = new MemoryStream();
        using (var z = new ZLibStream(raw, CompressionLevel.Fastest, true))
        {
            var row = new byte[1 + w * 3];
            for (int y = 0; y < h; y++)
            {
                int src = ((y0 + y) * stride + x0) * 4;
                for (int x = 0; x < w; x++, src += 4)
                {
                    row[1 + x * 3] = bgra[src + 2];
                    row[2 + x * 3] = bgra[src + 1];
                    row[3 + x * 3] = bgra[src];
                }
                z.Write(row);
            }
        }
        using var png = new MemoryStream();
        png.Write(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 });
        var ihdr = new byte[13];
        BE(ihdr, 0, w);
        BE(ihdr, 4, h);
        ihdr[8] = 8; // bit depth
        ihdr[9] = 2; // RGB
        Chunk(png, "IHDR", ihdr);
        Chunk(png, "IDAT", raw.ToArray());
        Chunk(png, "IEND", Array.Empty<byte>());
        return png.ToArray();
    }

    private static void Chunk(Stream s, string type, byte[] data)
    {
        var len = new byte[4];
        BE(len, 0, data.Length);
        s.Write(len);
        var td = new byte[4 + data.Length];
        Encoding.ASCII.GetBytes(type).CopyTo(td, 0);
        data.CopyTo(td, 4);
        s.Write(td);
        var crc = new byte[4];
        BE(crc, 0, (int)Crc32(td));
        s.Write(crc);
    }

    private static readonly uint[] CrcTable = Enumerable.Range(0, 256).Select(n =>
    {
        uint c = (uint)n;
        for (int k = 0; k < 8; k++)
            c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1;
        return c;
    }).ToArray();

    public static uint Crc32(byte[] data)
    {
        uint c = 0xFFFFFFFF;
        foreach (byte b in data)
            c = CrcTable[(c ^ b) & 0xFF] ^ (c >> 8);
        return c ^ 0xFFFFFFFF;
    }

    private static void BE(byte[] b, int at, int v)
    {
        b[at] = (byte)(v >> 24);
        b[at + 1] = (byte)(v >> 16);
        b[at + 2] = (byte)(v >> 8);
        b[at + 3] = (byte)v;
    }

    // ---- X11 ---------------------------------------------------------------------------------------------------------

    private static class X11
    {
        private const string Lib = "libX11.so.6";

        [DllImport(Lib)] private static extern IntPtr XOpenDisplay(IntPtr name);
        [DllImport(Lib)] private static extern int XCloseDisplay(IntPtr d);
        [DllImport(Lib)] private static extern IntPtr XDefaultRootWindow(IntPtr d);
        [DllImport(Lib)] private static extern int XQueryTree(IntPtr d, IntPtr w, out IntPtr root, out IntPtr parent, out IntPtr children, out uint n);
        [DllImport(Lib)] private static extern int XFetchName(IntPtr d, IntPtr w, out IntPtr name);
        [DllImport(Lib)] private static extern int XFree(IntPtr p);
        [DllImport(Lib)] private static extern int XGetGeometry(IntPtr d, IntPtr w, out IntPtr root, out int x, out int y, out uint width, out uint height, out uint border, out uint depth);
        [DllImport(Lib)] private static extern IntPtr XGetImage(IntPtr d, IntPtr w, int x, int y, uint width, uint height, ulong planes, int format);

        private static IntPtr _found;

        public static (int, int, byte[])? Grab(int wantW, int wantH, string? title)
        {
            IntPtr d = XOpenDisplay(IntPtr.Zero);
            if (d == IntPtr.Zero)
                return null;
            try
            {
                _found = IntPtr.Zero;
                Find(d, XDefaultRootWindow(d), wantW, wantH, title, 0);
                if (_found == IntPtr.Zero)
                    return null;
                XGetGeometry(d, _found, out _, out _, out _, out uint w, out uint h, out _, out _);
                IntPtr img = XGetImage(d, _found, 0, 0, w, h, ulong.MaxValue, 2 /* ZPixmap */);
                if (img == IntPtr.Zero)
                    return null;
                try
                {
                    // XImage: width 0, height 4, data 16, bytes_per_line 44, bits_per_pixel 48 (LP64).
                    IntPtr data = Marshal.ReadIntPtr(img, 16);
                    int bpl = Marshal.ReadInt32(img, 44), bpp = Marshal.ReadInt32(img, 48);
                    if (bpp != 32)
                        return null;
                    var bgra = new byte[w * h * 4];
                    for (int y = 0; y < h; y++)
                        Marshal.Copy(data + y * bpl, bgra, (int)(y * w * 4), (int)(w * 4));
                    return ((int)w, (int)h, bgra);
                }
                finally
                {
                    // XDestroyImage is a macro: image->f.destroy_image (offset 96).
                    var destroy = (delegate* unmanaged<IntPtr, int>)Marshal.ReadIntPtr(img, 96);
                    destroy(img);
                }
            }
            finally
            {
                XCloseDisplay(d);
            }
        }

        private static void Find(IntPtr d, IntPtr w, int wantW, int wantH, string? title, int depth)
        {
            if (_found != IntPtr.Zero || depth > 6)
                return;
            if (title != null)
            {
                if (XFetchName(d, w, out IntPtr name) != 0 && name != IntPtr.Zero)
                {
                    string? s = Marshal.PtrToStringUTF8(name);
                    XFree(name);
                    if (s != null && s.Contains(title, StringComparison.OrdinalIgnoreCase))
                    {
                        _found = w;
                        return;
                    }
                }
            }
            else if (depth > 0 && XGetGeometry(d, w, out _, out _, out _, out uint gw, out uint gh, out _, out _) != 0 &&
                     Math.Abs((int)gw - wantW) <= 2 && Math.Abs((int)gh - wantH) <= 2)
            {
                _found = w;
                return;
            }
            if (XQueryTree(d, w, out _, out _, out IntPtr children, out uint n) == 0 || children == IntPtr.Zero)
                return;
            try
            {
                for (int i = 0; i < n && _found == IntPtr.Zero; i++)
                    Find(d, Marshal.ReadIntPtr(children, i * IntPtr.Size), wantW, wantH, title, depth + 1);
            }
            finally
            {
                XFree(children);
            }
        }
    }

    // ---- Windows -----------------------------------------------------------------------------------------------------

    private static class Win
    {
        [DllImport("user32.dll")] private static extern int EnumWindows(delegate* unmanaged<IntPtr, IntPtr, int> fn, IntPtr lParam);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowTextW(IntPtr w, char* text, int max);
        [DllImport("user32.dll")] private static extern int GetClientRect(IntPtr w, out RECT r);
        [DllImport("user32.dll")] private static extern int IsWindowVisible(IntPtr w);
        [DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr w);
        [DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr w, IntPtr dc);
        [DllImport("user32.dll")] private static extern int PrintWindow(IntPtr w, IntPtr dc, uint flags);
        [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleDC(IntPtr dc);
        [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleBitmap(IntPtr dc, int w, int h);
        [DllImport("gdi32.dll")] private static extern IntPtr SelectObject(IntPtr dc, IntPtr o);
        [DllImport("gdi32.dll")] private static extern int DeleteObject(IntPtr o);
        [DllImport("gdi32.dll")] private static extern int DeleteDC(IntPtr dc);
        [DllImport("gdi32.dll")] private static extern int GetDIBits(IntPtr dc, IntPtr bmp, uint start, uint lines, byte* bits, BITMAPINFOHEADER* info, uint usage);

        [StructLayout(LayoutKind.Sequential)] private struct RECT { public int Left, Top, Right, Bottom; }

        [StructLayout(LayoutKind.Sequential)]
        private struct BITMAPINFOHEADER
        {
            public uint Size;
            public int Width, Height;
            public ushort Planes, BitCount;
            public uint Compression, SizeImage;
            public int XPels, YPels;
            public uint ClrUsed, ClrImportant;
        }

        private static IntPtr _found;
        private static int _wantW, _wantH;
        private static string? _title;

        [UnmanagedCallersOnly]
        private static int Visit(IntPtr w, IntPtr _)
        {
            if (IsWindowVisible(w) == 0)
                return 1;
            if (_title != null)
            {
                char* buf = stackalloc char[256];
                int n = GetWindowTextW(w, buf, 256);
                if (n > 0 && new string(buf, 0, n).Contains(_title, StringComparison.OrdinalIgnoreCase))
                {
                    _found = w;
                    return 0;
                }
                return 1;
            }
            if (GetClientRect(w, out var r) != 0 && Math.Abs(r.Right - _wantW) <= 2 && Math.Abs(r.Bottom - _wantH) <= 2)
            {
                _found = w;
                return 0;
            }
            return 1;
        }

        public static (int, int, byte[])? Grab(int wantW, int wantH, string? title)
        {
            _found = IntPtr.Zero;
            _wantW = wantW;
            _wantH = wantH;
            _title = title;
            EnumWindows(&Visit, IntPtr.Zero);
            if (_found == IntPtr.Zero || GetClientRect(_found, out var r) == 0)
                return null;
            int w = r.Right, h = r.Bottom;
            IntPtr screen = GetDC(_found), mem = CreateCompatibleDC(screen), bmp = CreateCompatibleBitmap(screen, w, h);
            IntPtr old = SelectObject(mem, bmp);
            try
            {
                PrintWindow(_found, mem, 3); // PW_CLIENTONLY | PW_RENDERFULLCONTENT
                var info = new BITMAPINFOHEADER { Size = 40, Width = w, Height = -h, Planes = 1, BitCount = 32 };
                var bgra = new byte[w * h * 4];
                fixed (byte* p = bgra)
                    GetDIBits(mem, bmp, 0, (uint)h, p, &info, 0);
                return (w, h, bgra);
            }
            finally
            {
                SelectObject(mem, old);
                DeleteObject(bmp);
                DeleteDC(mem);
                ReleaseDC(_found, screen);
            }
        }
    }
}
