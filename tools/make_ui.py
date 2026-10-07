"""Draws the gilded 16-bit UI pieces in assets/ui/gilded/ (all used as nine-patches by Ui.java).
Run: python3 tools/make_ui.py   (needs Pillow)"""
from PIL import Image
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets", "ui", "gilded")
os.makedirs(OUT, exist_ok=True)

OUTLINE = (42, 20, 8)
GOLD_HI = (255, 236, 150)
GOLD = (243, 180, 52)
GOLD_LO = (190, 112, 22)
GOLD_DEEP = (128, 70, 14)
FACE = (44, 28, 20)
FACE_EDGE = (26, 14, 8)


def blank(w, h):
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))


def cut_corner(x, y, w, h, r):
    """True for pixels outside a pixel-rounded corner of radius r"""
    cx = x if x < r else (w - 1 - x if x >= w - r else None)
    cy = y if y < r else (h - 1 - y if y >= h - r else None)
    if cx is None or cy is None:
        return False
    return cx + cy < r - 1


def panel():
    w = h = 24
    img = blank(w, h)
    px = img.load()
    for y in range(h):
        for x in range(w):
            if cut_corner(x, y, w, h, 3):
                continue
            d = min(x, y, w - 1 - x, h - 1 - y)
            top_left = (x + y) < (w - 1 - x) + (h - 1 - y)
            if d == 0:
                c = OUTLINE
            elif d == 1:
                c = GOLD_HI if top_left else GOLD
            elif d in (2, 3):
                c = GOLD if top_left else GOLD_LO
            elif d == 4:
                c = GOLD_DEEP
            elif d == 5:
                c = FACE_EDGE
            else:
                c = FACE
            px[x, y] = c + ((236,) if d >= 5 else (255,))
    img.save(os.path.join(OUT, "panel.png"))


def button(name, top, mid, bottom, shadow, pressed=False):
    w, h = 24, 18
    img = blank(w, h)
    px = img.load()
    for y in range(h):
        for x in range(w):
            if cut_corner(x, y, w, h, 2):
                continue
            d = min(x, y, w - 1 - x, h - 1 - y)
            if d == 0:
                c = OUTLINE
            elif pressed:
                # pressed in: dark lip on top, flat face
                c = shadow if y <= 2 else mid
            elif y >= h - 4:
                c = shadow                      # thick bottom lip gives the 3D bevel
            elif y == 1 or x == 1:
                c = top                         # bright highlight edge
            elif y < 7:
                c = top if y < 3 else mid
            else:
                c = bottom if y > h - 7 else mid
            px[x, y] = c + (255,)
    img.save(os.path.join(OUT, name + ".png"))


def field():
    w = h = 16
    img = blank(w, h)
    px = img.load()
    for y in range(h):
        for x in range(w):
            if cut_corner(x, y, w, h, 2):
                continue
            d = min(x, y, w - 1 - x, h - 1 - y)
            if d == 0:
                c = GOLD_LO
            elif d == 1:
                c = OUTLINE
            elif d == 2 and (x == 2 or y == 2):
                c = (14, 8, 4)                  # inner shadow: looks sunken
            else:
                c = (30, 18, 12)
            px[x, y] = c + (255,)
    img.save(os.path.join(OUT, "field.png"))


def frame():
    """thin gold frame with an empty middle, for bars"""
    w = h = 12
    img = blank(w, h)
    px = img.load()
    for y in range(h):
        for x in range(w):
            if cut_corner(x, y, w, h, 2):
                continue
            d = min(x, y, w - 1 - x, h - 1 - y)
            top_left = (x + y) < (w - 1 - x) + (h - 1 - y)
            c = [OUTLINE, GOLD_HI if top_left else GOLD, GOLD_LO, OUTLINE]
            if d < 4:
                px[x, y] = c[d] + (255,)
            else:
                px[x, y] = FACE_EDGE + (220,)
    img.save(os.path.join(OUT, "frame.png"))


def solid():
    """1 white pixel, tinted in code for bar fills"""
    img = blank(1, 1)
    img.putpixel((0, 0), (255, 255, 255, 255))
    img.save(os.path.join(OUT, "pixel.png"))


panel()
button("button", (255, 240, 170), (246, 190, 64), (222, 150, 36), (150, 84, 16))
button("button_over", (255, 250, 200), (255, 210, 90), (240, 172, 50), (166, 96, 20))
button("button_down", (255, 240, 170), (226, 160, 40), (210, 140, 30), (120, 64, 10), pressed=True)
button("button_disabled", (176, 160, 140), (130, 112, 94), (112, 96, 80), (74, 62, 52))
field()
frame()
solid()
print("ui ok")
