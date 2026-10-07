"""Draws assets/ui/speedometer.png: a round, mirror-symmetric pixel-art gauge face.
The needle, hub and speed number are drawn by the game (Overlay.java).

Layout (64x64 art pixels, center at 32,32):
  needle sweeps 240 degrees, from 210 (0 mph, lower left) to -30 (max, lower right)
  9 dot markers along the sweep (big dots every 50 mph, the last ones red = redline)
  a recessed readout window in the gap at the bottom for the digital speed
Run: python3 tools/make_speedometer.py   (needs Pillow)"""
from PIL import Image
import math, os

S = 64
C = S / 2                      # 32.0: exact center, so the art is symmetric
OUTLINE = (42, 20, 8)
GOLD_HI = (255, 230, 140)
GOLD = (243, 180, 52)
GOLD_LO = (196, 118, 24)
GOLD_DEEP = (132, 72, 14)
FACE_EDGE = (22, 12, 7)
FACE = (40, 26, 18)
DOT = (250, 196, 80)
DOT_DIM = (150, 104, 48)
RED = (232, 64, 40)
WINDOW = (14, 8, 4)

img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
px = img.load()

for y in range(S):
    for x in range(S):
        dx, dy = x + 0.5 - C, y + 0.5 - C
        d = math.hypot(dx, dy)
        top = dy < 0                       # light comes from above, same on both sides
        if d > 31.2:
            continue
        elif d > 30.2:
            c = OUTLINE
        elif d > 29.2:
            c = GOLD_HI if top else GOLD
        elif d > 27.2:
            c = GOLD if top else GOLD_LO
        elif d > 26.2:
            c = GOLD_DEEP
        elif d > 25.2:
            c = FACE_EDGE
        else:
            c = FACE
        px[x, y] = c + (255,)

# dot markers: compute the left half and mirror it, so both sides match exactly
def put(x, y, color):
    px[x, y] = color + (255,)
    px[S - 1 - x, y] = color + (255,)

marks = 9
for i in range(marks // 2 + 1):            # 210 deg down to 90 deg (top)
    a = math.radians(210 - 240 * i / (marks - 1))
    major = i % 2 == 0
    rad = 22.0
    fx, fy = C + rad * math.cos(a), C - rad * math.sin(a)
    if major:
        x0, y0 = int(round(fx - 1)), int(round(fy - 1))
        for ox in range(2):
            for oy in range(2):
                put(x0 + ox, y0 + oy, DOT)
    else:
        put(int(fx), int(fy), DOT_DIM)

# redline: recolor the last two markers on the right side (175 and 200 mph)
for i in (marks - 2, marks - 1):
    a = math.radians(210 - 240 * i / (marks - 1))
    fx, fy = C + 22.0 * math.cos(a), C - 22.0 * math.sin(a)
    if i % 2 == 0:
        x0, y0 = int(round(fx - 1)), int(round(fy - 1))
        for ox in range(2):
            for oy in range(2):
                px[x0 + ox, y0 + oy] = RED + (255,)
    else:
        px[int(fx), int(fy)] = RED + (255,)

# readout window centered under the hub (x 22..41 is symmetric around 32)
for y in range(39, 49):
    for x in range(22, 42):
        edge = y in (39, 48) or x in (22, 41)
        px[x, y] = (GOLD_LO if edge else WINDOW) + (255,)
for x in range(23, 41):                    # inner shadow under the top edge: looks sunken
    px[x, 40] = (6, 3, 1, 255)

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets", "ui", "speedometer.png")
img.save(out)
print("gauge ok", S, S)
