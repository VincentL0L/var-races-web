"""Builds 64px BMFont atlases (assets/fonts/<name>.fnt + .png) that the game scales down for sharp text.
  michroma: small text (leaderboard, labels). Titles use our own font, see make_speed_font.py
Run: python3 tools/make_font.py   (needs Pillow; Michroma is SIL OFL licensed, see Michroma-OFL.txt)"""
from PIL import Image, ImageDraw, ImageFont
import os

SIZE, PAD, W, H = 64, 4, 1024, 512
here = os.path.dirname(os.path.abspath(__file__))

def build(ttf, name, stroke=0):
    """stroke > 0 bakes a dark outline around each letter (16-bit title style)"""
    font = ImageFont.truetype(os.path.join(here, ttf), SIZE)
    ascent, descent = font.getmetrics()
    img = Image.new("RGBA", (W, H), (255, 255, 255, 0))
    draw = ImageDraw.Draw(img)
    x, y, row_h, chars = 1, 1, 0, []
    for code in range(32, 127):
        ch = chr(code)
        l, t, r, b = font.getbbox(ch, anchor="ls", stroke_width=stroke)
        gw, gh = (r - l) + 2 * PAD, (b - t) + 2 * PAD
        if code == 32:
            gw, gh, l, t = 0, 0, 0, 0
        if x + gw + 1 > W:
            x, y, row_h = 1, y + row_h + 1, 0
        if gw:
            draw.text((x + PAD - l, y + PAD - t), ch, font=font, fill=(255, 255, 255, 255), anchor="ls",
                      stroke_width=stroke, stroke_fill=(26, 12, 4, 255))
        chars.append((code, x, y, gw, gh, l - PAD, ascent + t - PAD, round(font.getlength(ch)) + stroke))
        x += gw + 1
        row_h = max(row_h, gh)
    assert y + row_h < H, "atlas too small"
    out = os.path.join(here, "..", "assets", "fonts")
    img.save(os.path.join(out, name + ".png"))
    with open(os.path.join(out, name + ".fnt"), "w") as f:
        f.write(f'info face="{name}" size={SIZE} bold=0 italic=0 charset="" unicode=1 stretchH=100 smooth=1 aa=1 padding={PAD},{PAD},{PAD},{PAD} spacing=1,1\n')
        f.write(f"common lineHeight={ascent + descent} base={ascent} scaleW={W} scaleH={H} pages=1 packed=0\n")
        f.write(f'page id=0 file="{name}.png"\n')
        f.write(f"chars count={len(chars)}\n")
        for c in chars:
            f.write("char id={} x={} y={} width={} height={} xoffset={} yoffset={} xadvance={} page=0 chnl=15\n".format(*c))
    print(name, "ok", len(chars), "glyphs")


build("Michroma-Regular.ttf", "michroma")
