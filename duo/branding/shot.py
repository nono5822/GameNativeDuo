#!/usr/bin/env python3
"""README footage from the AYN Thor: both screens at their physical scale, hinge included.

  shot.py still NAME                 capture both screens now -> .github/assets/NAME.jpg
  shot.py video NAME SECONDS [W]     record both screens -> .github/assets/NAME.webp (W px wide, default 720)
  shot.py encode NAME START SECS [W] re-encode a part of the raw recording
  shot.py top NAME                   top screen only (app UI) -> .github/assets/NAME.jpg
  shot.py compose TOP BOTTOM OUT     assemble two existing screenshots

Geometry comes from the Thor's calibration (duo/display/ScreenCalibration.kt): the bottom screen is drawn at
1/bottomScale, centred, gap px below the top one. Needs adb, ImageMagick (magick) and ffmpeg.
"""
import os
import subprocess
import sys
import tempfile
import time

TOP_ID, BOTTOM_ID = "4630946441858561667", "4630946482288158084"
GAP, SCALE, SHIFT = 249, 1.142, 0
TOP_W, TOP_H, BOT_W, BOT_H = 1920, 1080, 1240, 1080
M, BEZEL, RADIUS = 72, 22, 34          # margin, bezel width, bezel corner radius (top-screen px)
BG, BODY, EDGE = "#0E1120", "#05060B", "#262C46"
ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".github", "assets")

bw, bh = round(BOT_W / SCALE), round(BOT_H / SCALE)
W, H = TOP_W + 2 * M, M + TOP_H + GAP + bh + M
TOP_XY = (M, M)
BOT_XY = (M + (TOP_W - bw) // 2 + SHIFT, M + TOP_H + GAP)


def run(*cmd, **kw):
    return subprocess.run(cmd, check=True, **kw)


def frame(path):
    """Background with the two screen bodies (bezels), screens cut out."""
    def body(x, y, w, h):
        return ["-fill", BODY, "-stroke", EDGE, "-strokewidth", "2", "-draw",
                f"roundrectangle {x - BEZEL},{y - BEZEL} {x + w + BEZEL},{y + h + BEZEL} {RADIUS},{RADIUS}"]
    run("magick", "-size", f"{W}x{H}", f"xc:{BG}", *body(*TOP_XY, TOP_W, TOP_H), *body(*BOT_XY, bw, bh), path)


def compose(top, bottom, out, width=1600):
    with tempfile.TemporaryDirectory() as d:
        bg = os.path.join(d, "bg.png")
        frame(bg)
        run("magick", bg, top, "-geometry", f"+{TOP_XY[0]}+{TOP_XY[1]}", "-composite",
            "(", bottom, "-filter", "Lanczos", "-resize", f"{bw}x{bh}!", ")",
            "-geometry", f"+{BOT_XY[0]}+{BOT_XY[1]}", "-composite",
            "-resize", f"{width}x", "-quality", "90", "-strip", out)


def screencap(display, path):
    with open(path, "wb") as f:
        run("adb", "exec-out", "screencap", "-p", "-d", display, stdout=f)


def still(name):
    with tempfile.TemporaryDirectory() as d:
        t, b = os.path.join(d, "t.png"), os.path.join(d, "b.png")
        screencap(TOP_ID, t)
        screencap(BOTTOM_ID, b)
        compose(t, b, os.path.join(ASSETS, name + ".jpg"))


def top_only(name, width=1600):
    with tempfile.TemporaryDirectory() as d:
        t = os.path.join(d, "t.png")
        screencap(TOP_ID, t)
        run("magick", t, "-resize", f"{width}x", "-quality", "90", "-strip", os.path.join(ASSETS, name + ".jpg"))


RAW = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "agents", "runs", "footage")


def video(name, seconds, width=720):
    """Record both screens at once, keep the raw clips (duo/agents/runs/footage/, ignored), then encode."""
    secs = int(seconds)
    os.makedirs(RAW, exist_ok=True)
    procs = [subprocess.Popen(["adb", "shell", "screenrecord", "--display-id", disp, "--time-limit", str(secs),
                               "--bit-rate", "20000000", f"/sdcard/duo-{tag}.mp4"])
             for disp, tag in ((TOP_ID, "top"), (BOTTOM_ID, "bottom"))]
    for p in procs:
        p.wait()
    time.sleep(1)
    for tag in ("top", "bottom"):
        run("adb", "pull", "-q", f"/sdcard/duo-{tag}.mp4", os.path.join(RAW, f"{name}-{tag}.mp4"))
        run("adb", "shell", "rm", f"/sdcard/duo-{tag}.mp4")
    encode(name, 0, secs, width)


def encode(name, start, seconds, width=720, fps=24, quality=72):
    """Raw clips -> .github/assets/NAME.webp (animated WebP: a fraction of a GIF's size, shown by GitHub)."""
    with tempfile.TemporaryDirectory() as d:
        bg = os.path.join(d, "bg.png")
        frame(bg)
        graph = (f"[1:v]fps={fps},scale={TOP_W}:{TOP_H}[t];[2:v]fps={fps},scale={bw}:{bh}:flags=lanczos[b];"
                 f"[0:v][t]overlay={TOP_XY[0]}:{TOP_XY[1]}[x];[x][b]overlay={BOT_XY[0]}:{BOT_XY[1]}:shortest=1,"
                 f"scale={width}:-2:flags=lanczos")
        run("ffmpeg", "-v", "error", "-y", "-loop", "1", "-i", bg,
            "-ss", str(start), "-i", os.path.join(RAW, f"{name}-top.mp4"),
            "-ss", str(start), "-i", os.path.join(RAW, f"{name}-bottom.mp4"),
            "-filter_complex", graph, "-t", str(seconds), "-c:v", "libwebp_anim", "-quality", str(quality),
            "-compression_level", "6", "-loop", "0", os.path.join(ASSETS, name + ".webp"))


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["still"]:
        still(a[1])
    elif a[:1] == ["top"]:
        top_only(a[1])
    elif a[:1] == ["video"]:
        video(a[1], a[2], int(a[3]) if len(a) > 3 else 720)
    elif a[:1] == ["encode"]:
        encode(a[1], float(a[2]), float(a[3]), int(a[4]) if len(a) > 4 else 720)
    elif a[:1] == ["compose"]:
        compose(a[1], a[2], a[3])
    else:
        sys.exit(__doc__)
