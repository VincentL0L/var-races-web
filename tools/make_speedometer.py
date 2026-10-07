"""Draws assets/ui/speedometer.png, a pixel-art gauge face (needle is drawn by the game).
Run: python3 tools/make_speedometer.py   (needs Pillow)"""
from PIL import Image
import math, os

W, H = 48, 44
CX, CY, R = 23.5, 23.5, 22.5
OUTLINE, RIM_HI, RIM, RIM_LO = (52, 26, 10), (255, 220, 110), (240, 170, 40), (190, 110, 20)
FACE_EDGE, FACE, TICK, TICK_DIM = (24, 14, 10), (44, 28, 20), (250, 190, 70), (170, 120, 50)
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
px = img.load()

def inside(x, y):
    dx, dy = x + 0.5 - CX, y + 0.5 - CY
    d = math.hypot(dx, dy)
    dome = d <= R and y <= CY + 13          # round top, flat-ish bottom
    tab = abs(dx) <= 7 and y <= CY + 17 and math.hypot(dx * 0.9, (y - CY - 12) * 1.2) <= 8
    return dome or tab

mask = [[inside(x, y) for x in range(W)] for y in range(H)]
def depth(x, y):  # distance in pixels to the outside of the shape
    for k in range(0, 8):
        for ox in range(-k, k + 1):
            for oy in range(-k, k + 1):
                if max(abs(ox), abs(oy)) != k:
                    continue
                nx, ny = x + ox, y + oy
                if not (0 <= nx < W and 0 <= ny < H) or not mask[ny][nx]:
                    return k
    return 8

for y in range(H):
    for x in range(W):
        if not mask[y][x]:
            continue
        d = depth(x, y)
        light = (CY - y) / R  # top of the rim catches the light
        if d <= 1:
            c = OUTLINE
        elif d <= 4:
            c = RIM_HI if (d == 2 and light > 0.2) else RIM_LO if (d == 4 or light < -0.4) else RIM
        elif d == 5:
            c = FACE_EDGE
        else:
            c = FACE
        px[x, y] = c + (255,)

# tick marks from 210 degrees (0 mph) clockwise to -30 degrees (max), like the game's needle
for i in range(11):
    a = math.radians(210 - 24 * i)
    major = i % 2 == 0
    for r in ([R - 7, R - 8] if major else [R - 7]):
        x, y = int(CX + r * math.cos(a)), int(CY - r * math.sin(a))
        px[x, y] = (TICK if major else TICK_DIM) + (255,)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets", "ui", "speedometer.png"))
print("gauge ok", W, H)
