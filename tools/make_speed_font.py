"""Draws VAR Races' own display font ("Speed"): heavy, wide, slanted capitals with squared rounded
corners and slim counters. Every letter is built from shapes below (no font file needed).

Writes assets/fonts/speed.fnt/.png (plain) and speed_outline.fnt/.png (dark outline, for text over the map).
Lowercase letters use the capitals. Run: python3 tools/make_speed_font.py   (needs Pillow)

Design units: cap height = 100, y goes down from the top of the capitals (0) to the baseline (100).
"""
from PIL import Image, ImageDraw, ImageFilter
import math, os, sys

SLANT = 0.32          # italic: x moves right by 0.32 per unit of height (about 18 degrees)
SPACING = 9           # gap between letters, in design units
BOLD = 2.2            # every edge is pushed out this many units: heavier strokes, slimmer counters
SIZE = 64             # nominal font size in the .fnt
CAP_PX = 46           # capital height in atlas pixels
SS = 8                # supersampling for smooth edges
PAD = 4
OUTLINE_PX = 4
ATLAS_W, ATLAS_H = 1024, 512

T = 26                # vertical stem thickness
R = 22                # big corner radius
r = 5                 # small corner radius


# ---------- shape helpers (design units) ----------

def rrect(x0, y0, x1, y1, tl=r, tr=r, br=r, bl=r):
    """polygon for a rectangle with its own radius at each corner"""
    pts = []
    def arc(cx, cy, rad, a0, a1):
        if rad <= 0:
            pts.append((cx, cy))
            return
        steps = 10
        for i in range(steps + 1):
            a = math.radians(a0 + (a1 - a0) * i / steps)
            pts.append((cx + rad * math.cos(a), cy + rad * math.sin(a)))
    arc(x0 + tl, y0 + tl, tl, 180, 270) if tl else pts.append((x0, y0))
    arc(x1 - tr, y0 + tr, tr, 270, 360) if tr else pts.append((x1, y0))
    arc(x1 - br, y1 - br, br, 0, 90) if br else pts.append((x1, y1))
    arc(x0 + bl, y1 - bl, bl, 90, 180) if bl else pts.append((x0, y1))
    return pts


def rect(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


# Each glyph: (advance width, [(+1 fill / -1 cut, polygon), ...]) drawn in order.
G = {}

def glyph(ch, width, *ops):
    G[ch] = (width, list(ops))

F, C = 1, -1

glyph("A", 120,
      (F, [(0, 100), (40, 0), (80, 0), (120, 100)]),
      (C, [(45, 56), (57, 26), (63, 26), (75, 56)]),
      (C, [(27, 100), (37, 75), (83, 75), (93, 100)]))
glyph("B", 108,
      (F, rrect(0, 0, 104, 100, 4, R, R, 4)),
      (C, [(104, 43), (92, 50), (104, 57)]),
      (C, rrect(T, 22, 78, 40, 2, 4, 4, 2)),
      (C, rrect(T, 60, 80, 78, 2, 4, 4, 2)))
glyph("C", 104,
      (F, rrect(0, 0, 104, 100, R, 12, 12, R)),
      (C, rrect(T, 22, 120, 78, 6, 0, 0, 6)))
glyph("D", 108,
      (F, rrect(0, 0, 108, 100, 4, 28, 28, 4)),
      (C, rrect(T, 22, 82, 78, 2, 8, 8, 2)))
glyph("E", 98,
      (F, rrect(0, 0, 98, 100, 6, 4, 4, 6)),
      (C, rect(T, 22, 120, 40)),
      (C, rect(T, 60, 120, 78)),
      (C, rect(84, 39, 120, 61)))
glyph("F", 96,
      (F, rrect(0, 0, 96, 22, 6, 4, 0, 0)),
      (F, rrect(0, 0, T, 100, 6, 0, 4, 4)),
      (F, rect(0, 40, 84, 60)))
glyph("G", 108,
      (F, rrect(0, 0, 108, 100, R, 12, R, R)),
      (C, rrect(T, 22, 82, 78, 6, 2, 2, 6)),
      (C, rect(82, 22, 120, 44)),
      (F, rect(54, 44, 108, 60)))
glyph("H", 110,
      (F, rrect(0, 0, T, 100, 4, 4, 4, 4)),
      (F, rrect(84, 0, 110, 100, 4, 4, 4, 4)),
      (F, rect(0, 40, 110, 60)))
glyph("I", 26,
      (F, rrect(0, 0, T, 100, 4, 4, 4, 4)))
glyph("J", 98,
      (F, rrect(72, 0, 98, 100, 4, 4, R, 0)),
      (F, rrect(0, 78, 98, 100, 0, 0, R, 6)),
      (F, rrect(0, 56, T, 100, 4, 4, 0, 6)))
glyph("K", 110,
      (F, rrect(0, 0, T, 100, 4, 4, 4, 4)),
      (F, [(T, 40), (72, 0), (110, 0), (52, 52), (T, 60)]),
      (F, [(42, 46), (72, 46), (112, 100), (78, 100)]))
glyph("L", 92,
      (F, rrect(0, 0, T, 100, 4, 4, 0, 6)),
      (F, rrect(0, 78, 92, 100, 0, 4, 4, 6)))
glyph("M", 132,
      (F, rrect(0, 0, T, 100, 6, 0, 4, 4)),
      (F, rrect(106, 0, 132, 100, 0, 6, 4, 4)),
      (F, [(0, 0), (40, 0), (66, 40), (92, 0), (132, 0), (80, 70), (52, 70)]))
glyph("N", 112,
      (F, rrect(0, 0, T, 100, 6, 0, 4, 4)),
      (F, rrect(86, 0, 112, 100, 4, 4, 6, 0)),
      (F, [(0, 0), (36, 0), (112, 100), (76, 100)]))
glyph("O", 114,
      (F, rrect(0, 0, 114, 100, R, R, R, R)),
      (C, rrect(T, 22, 88, 78, 6, 6, 6, 6)))
glyph("P", 104,
      (F, rrect(0, 0, 104, 64, 4, R, R, 0)),
      (F, rrect(0, 0, T, 100, 4, 0, 4, 4)),
      (C, rrect(T, 22, 78, 42, 2, 4, 4, 2)))
glyph("Q", 114,
      (F, rrect(0, 0, 114, 100, R, R, R, R)),
      (C, rrect(T, 22, 88, 78, 6, 6, 6, 6)),
      (F, [(62, 66), (90, 66), (122, 100), (94, 100)]))
glyph("R", 108,
      (F, rrect(0, 0, 106, 62, 4, R, R, 0)),
      (F, rrect(0, 0, T, 100, 4, 0, 4, 4)),
      (C, rrect(T, 22, 80, 40, 2, 4, 4, 2)),
      (F, [(52, 56), (84, 56), (110, 100), (78, 100)]))
glyph("S", 104,
      (F, rrect(0, 0, 104, 100, R, 8, R, 8)),
      (C, rect(T, 22, 120, 40)),
      (C, rect(-10, 60, 104 - T, 78)))
glyph("T", 104,
      (F, rrect(0, 0, 104, 22, 6, 6, 0, 0)),
      (F, rrect(39, 0, 65, 100, 0, 0, 4, 4)))
glyph("U", 110,
      (F, rrect(0, 0, 110, 100, 4, 4, R, R)),
      (C, rrect(T, -10, 84, 78, 0, 0, 6, 6)))
glyph("V", 116,
      (F, [(0, 0), (32, 0), (58, 70), (84, 0), (116, 0), (76, 100), (40, 100)]))
glyph("W", 154,
      (F, [(0, 0), (30, 0), (44, 62), (62, 0), (92, 0), (110, 62), (124, 0), (154, 0),
           (128, 100), (96, 100), (77, 40), (58, 100), (26, 100)]))
glyph("X", 116,
      (F, [(0, 0), (36, 0), (116, 100), (80, 100)]),
      (F, [(80, 0), (116, 0), (36, 100), (0, 100)]))
glyph("Y", 112,
      (F, [(0, 0), (34, 0), (56, 36), (78, 0), (112, 0), (70, 62), (42, 62)]),
      (F, rrect(43, 50, 69, 100, 0, 0, 4, 4)))
glyph("Z", 104,
      (F, rrect(0, 0, 104, 22, 6, 6, 0, 0)),
      (F, rrect(0, 78, 104, 100, 0, 0, 6, 6)),
      (F, [(68, 22), (104, 22), (36, 78), (0, 78)]))

# digits
glyph("0", 104,
      (F, rrect(0, 0, 104, 100, R, R, R, R)),
      (C, rrect(T, 22, 78, 78, 6, 6, 6, 6)))
glyph("1", 64,
      (F, rrect(38, 0, 64, 100, 4, 4, 4, 4)),
      (F, rrect(4, 0, 64, 22, 6, 4, 0, 0)))
glyph("2", 104,
      (F, rrect(0, 0, 104, 100, 8, R, 4, 4)),
      (C, rect(-10, 22, 104 - T, 40)),
      (C, rect(T, 60, 120, 78)))
glyph("3", 104,
      (F, rrect(0, 0, 104, 100, 6, R, R, 6)),
      (C, rect(-10, 22, 104 - T, 40)),
      (C, rect(-10, 60, 104 - T, 78)),
      (C, rect(-10, 39, 24, 61)))
glyph("4", 104,
      (F, rrect(0, 0, T, 62, 4, 4, 0, 6)),
      (F, rect(0, 42, 104, 62)),
      (F, rrect(72, 0, 98, 100, 4, 4, 4, 4)))
glyph("5", 104,
      (F, rrect(0, 0, 104, 100, 6, 6, R, 8)),
      (C, rect(T, 22, 120, 40)),
      (C, rect(-10, 60, 104 - T, 78)))
glyph("6", 104,
      (F, rrect(0, 0, 104, 100, R, 8, R, R)),
      (C, rect(T, 22, 120, 40)),
      (C, rrect(T, 60, 78, 78, 2, 4, 4, 2)))
glyph("7", 100,
      (F, rrect(0, 0, 100, 22, 6, 6, 0, 0)),
      (F, [(64, 22), (100, 22), (48, 100), (14, 100)]))
glyph("8", 104,
      (F, rrect(0, 0, 104, 100, R, R, R, R)),
      (C, rrect(T, 22, 78, 40, 2, 4, 4, 2)),
      (C, rrect(T, 60, 78, 78, 2, 4, 4, 2)),
      (C, [(0, 43), (12, 50), (0, 57)]),
      (C, [(104, 43), (92, 50), (104, 57)]))
glyph("9", 104,
      (F, rrect(0, 0, 104, 100, R, R, R, 8)),
      (C, rrect(T, 22, 78, 40, 2, 4, 4, 2)),
      (C, rect(-10, 60, 104 - T, 78)))

# punctuation
glyph(" ", 40)
glyph("!", 26, (F, rrect(0, 0, T, 68, 4, 4, 2, 2)), (F, rrect(0, 78, T, 100, 2, 2, 4, 4)))
glyph(".", 26, (F, rrect(0, 78, T, 100, 2, 2, 4, 4)))
glyph(",", 26, (F, [(0, 78), (T, 78), (T, 100), (8, 116), (0, 116)]))
glyph(":", 26, (F, rrect(0, 26, T, 48, 2, 2, 2, 2)), (F, rrect(0, 78, T, 100, 2, 2, 4, 4)))
glyph(";", 26, (F, rrect(0, 26, T, 48, 2, 2, 2, 2)), (F, [(0, 78), (T, 78), (T, 100), (8, 116), (0, 116)]))
glyph("-", 56, (F, rrect(0, 40, 56, 60, 2, 2, 2, 2)))
glyph("_", 90, (F, rect(0, 90, 90, 104)))
glyph("+", 82, (F, rect(30, 22, 52, 78)), (F, rect(4, 40, 78, 60)))
glyph("=", 82, (F, rect(4, 26, 78, 44)), (F, rect(4, 56, 78, 74)))
glyph("'", 22, (F, rrect(0, 0, 22, 36, 3, 3, 3, 3)))
glyph('"', 54, (F, rrect(0, 0, 22, 36, 3, 3, 3, 3)), (F, rrect(32, 0, 54, 36, 3, 3, 3, 3)))
glyph("/", 90, (F, [(62, 0), (92, 0), (30, 100), (0, 100)]))
glyph("(", 40, (F, rrect(0, -6, 40, 106, R, 0, 0, R)), (C, rrect(22, 12, 60, 88, 6, 0, 0, 6)))
glyph(")", 40, (F, rrect(0, -6, 40, 106, 0, R, R, 0)), (C, rrect(-20, 12, 18, 88, 0, 6, 6, 0)))
glyph("[", 40, (F, rect(0, -6, 40, 106)), (C, rect(22, 12, 60, 88)))
glyph("]", 40, (F, rect(0, -6, 40, 106)), (C, rect(-20, 12, 18, 88)))
glyph("?", 96,
      (F, rrect(0, 0, 96, 60, 8, R, R, 0)),
      (C, rect(-10, 22, 96 - T, 40)),
      (C, rect(-10, 40, 34, 70)),
      (F, rect(34, 40, 60, 66)),
      (F, rrect(34, 78, 60, 100, 2, 2, 4, 4)))
glyph("%", 120,
      (F, rrect(0, 0, 44, 40, 8, 8, 8, 8)), (C, rect(14, 12, 30, 28)),
      (F, rrect(76, 60, 120, 100, 8, 8, 8, 8)), (C, rect(90, 72, 106, 88)),
      (F, [(84, 0), (114, 0), (36, 100), (6, 100)]))
glyph("#", 110, (F, [(30, 0), (52, 0), (40, 100), (18, 100)]), (F, [(70, 0), (92, 0), (80, 100), (58, 100)]),
      (F, rect(4, 26, 106, 42)), (F, rect(4, 58, 106, 74)))
glyph("&", 112,
      (F, rrect(0, 0, 104, 100, R, R, R, R)),
      (C, rrect(T, 22, 78, 40, 2, 4, 4, 2)),
      (C, rrect(T, 60, 78, 78, 2, 4, 4, 2)),
      (C, rect(78, 40, 120, 60)),
      (F, [(80, 48), (104, 48), (112, 100), (90, 100)]))
glyph("<", 80, (F, [(80, 14), (80, 38), (34, 50), (80, 62), (80, 86), (0, 60), (0, 40)]))
glyph(">", 80, (F, [(0, 14), (80, 40), (80, 60), (0, 86), (0, 62), (46, 50), (0, 38)]))
glyph("*", 64, (F, rect(22, 0, 42, 44)), (F, [(0, 10), (64, 30), (64, 44), (0, 24)]),
      (F, [(0, 30), (64, 10), (64, 24), (0, 44)]))
glyph("@", 120,
      (F, rrect(0, 0, 120, 100, R, R, R, R)), (C, rrect(18, 16, 102, 84, 12, 12, 12, 12)),
      (F, rrect(36, 30, 92, 70, 6, 6, 6, 6)), (C, rect(52, 44, 76, 56)), (C, rect(102, 60, 130, 84)))
glyph("$", 104,
      (F, rrect(0, 0, 104, 100, R, 8, R, 8)),
      (C, rect(T, 22, 120, 40)),
      (C, rect(-10, 60, 104 - T, 78)),
      (F, rect(40, -12, 64, 112)))
glyph("^", 80, (F, [(0, 40), (28, 0), (52, 0), (80, 40), (56, 40), (40, 18), (24, 40)]))
glyph("`", 30, (F, [(0, 0), (22, 0), (30, 22), (12, 22)]))
glyph("|", 22, (F, rect(0, -6, 22, 106)))
glyph("\\", 90, (F, [(0, 0), (30, 0), (92, 100), (62, 100)]))
glyph("{", 44, (F, rrect(0, -6, 44, 106, R, 0, 0, R)), (C, rrect(22, 12, 60, 42, 6, 0, 0, 6)),
      (C, rrect(22, 58, 60, 88, 6, 0, 0, 6)))
glyph("}", 44, (F, rrect(0, -6, 44, 106, 0, R, R, 0)), (C, rrect(-20, 12, 22, 42, 0, 6, 6, 0)),
      (C, rrect(-20, 58, 22, 88, 0, 6, 6, 0)))
glyph("~", 90, (F, [(0, 56), (24, 40), (66, 52), (90, 40), (90, 54), (66, 66), (24, 54), (0, 70)]))


# ---------- rendering ----------

UNIT = CAP_PX / 100.0                 # atlas pixels per design unit
TOP_MARGIN = 10                       # units of space above capitals
DESC = 20                             # units below the baseline
BASE_PX = round((TOP_MARGIN + 100) * UNIT)
LINE_PX = round((TOP_MARGIN + 100 + DESC) * UNIT)


def render(ch):
    """returns (image of the glyph, x offset of its left edge in units)"""
    width, ops = G[ch]
    if not ops:
        return None, 0
    ys = [p[1] for _, poly in ops for p in poly]
    y_min, y_max = min(min(ys), 0), max(max(ys), 100)
    xs = [p[0] + SLANT * (100 - p[1]) for _, poly in ops for p in poly]
    x_min, x_max = min(xs), max(xs)
    x_min, x_max, y_min, y_max = x_min - BOLD, x_max + BOLD, y_min - BOLD, y_max + BOLD
    u = UNIT * SS
    w = int(math.ceil((x_max - x_min) * u)) + 2
    h = int(math.ceil((y_max - y_min) * u)) + 2
    mask = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(mask)
    for sign, poly in ops:
        pts = [((x + SLANT * (100 - y) - x_min) * u + 1, (y - y_min) * u + 1) for x, y in poly]
        d.polygon(pts, fill=255 if sign > 0 else 0)
    grow = int(round(BOLD * u)) * 2 + 1
    mask = mask.filter(ImageFilter.MaxFilter(grow))
    small = mask.resize((max(1, round(w / SS)), max(1, round(h / SS))), Image.LANCZOS)
    return small, x_min, y_min


def build(name, outline):
    atlas = Image.new("RGBA", (ATLAS_W, ATLAS_H), (0, 0, 0, 0))
    chars = []
    x, y, row_h = 1, 1, 0
    for code in range(32, 127):
        ch = chr(code)
        key = ch.upper() if ch.upper() in G else ch
        if key not in G:
            key = " "
        width, ops = G[key]
        advance = round((width + SPACING) * UNIT) + (outline if outline else 0)
        if not ops:
            chars.append((code, 0, 0, 0, 0, 0, 0, advance))
            continue
        mask, x_min, y_min = render(key)
        pad = PAD + outline
        gw, gh = mask.width + 2 * pad, mask.height + 2 * pad
        glyph_img = Image.new("RGBA", (gw, gh), (0, 0, 0, 0))
        if outline:
            m = Image.new("L", (gw, gh), 0)
            m.paste(mask, (pad, pad))
            grown = m.filter(ImageFilter.MaxFilter(outline * 2 + 1))
            glyph_img.paste((26, 12, 4, 255), (0, 0), grown)
            glyph_img.paste((255, 255, 255, 255), (0, 0), m)
        else:
            glyph_img.paste((255, 255, 255, 255), (pad, pad), mask)
        if x + gw + 1 > ATLAS_W:
            x, y, row_h = 1, y + row_h + 1, 0
        atlas.alpha_composite(glyph_img, (x, y))
        xoff = round(x_min * UNIT) - pad
        yoff = round((TOP_MARGIN + y_min) * UNIT) - pad
        chars.append((code, x, y, gw, gh, xoff, yoff, advance))
        x += gw + 1
        row_h = max(row_h, gh)
    assert y + row_h < ATLAS_H, "atlas too small"

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets", "fonts")
    atlas.save(os.path.join(out, name + ".png"))
    with open(os.path.join(out, name + ".fnt"), "w") as f:
        p = PAD + outline  # tells libGDX how much empty border each glyph has, so text centers correctly
        f.write(f'info face="{name}" size={SIZE} bold=1 italic=1 charset="" unicode=1 stretchH=100 smooth=1 aa=1 padding={p},{p},{p},{p} spacing=1,1\n')
        f.write(f"common lineHeight={LINE_PX} base={BASE_PX} scaleW={ATLAS_W} scaleH={ATLAS_H} pages=1 packed=0\n")
        f.write(f'page id=0 file="{name}.png"\n')
        f.write(f"chars count={len(chars)}\n")
        for c in chars:
            f.write("char id={} x={} y={} width={} height={} xoffset={} yoffset={} xadvance={} page=0 chnl=15\n".format(*c))
    print(name, "ok")


def preview(path, text_lines, scale=1.0):
    """renders text the way the game will, red on black, to compare with the reference"""
    lines = []
    for text, size in text_lines:
        lines.append((text, size))
    W, Hh = 1100, 40 + sum(int(s * 1.3) + 20 for _, s in lines)
    img = Image.new("RGB", (W, Hh), (10, 10, 12))
    yy = 30
    for text, size in lines:
        k = size / CAP_PX
        widths = [round((G[c.upper()][0] + SPACING) * UNIT * k) if c.upper() in G else 0 for c in text]
        xx = (W - sum(widths)) // 2
        for c, adv in zip(text, widths):
            key = c.upper()
            if key in G and G[key][1]:
                mask, x_min, y_min = render(key)
                m = mask.resize((max(1, round(mask.width * k)), max(1, round(mask.height * k))), Image.LANCZOS)
                img.paste((226, 30, 30), (xx + round(x_min * UNIT * k), yy + round(y_min * UNIT * k)), m)
            xx += adv
        yy += int(size * 1.3) + 20
    img.save(path)


if __name__ == "__main__":
    build("speed", 0)
    build("speed_outline", OUTLINE_PX)
    if len(sys.argv) > 1:
        preview(sys.argv[1], [("SPEED", 110), ("ABCDEFG", 70), ("HIJKLMN", 70), ("OPQRSTU", 70),
                              ("VWXYZ", 70), ("0123456789", 56), ("GO! FINISH! RACE LOBBY", 40)])
