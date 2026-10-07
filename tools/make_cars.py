"""Draws the 16-bit top-down race cars: assets/ui/car1.png ... car6.png (14x28 pixel art, nose up).
car1-3 are the skins players pick, car4-6 are the CPU cars.
Every car uses the same template, mirrored from the left half so it's perfectly symmetric.
Run: python3 tools/make_cars.py   (needs Pillow)"""
from PIL import Image
import os

# left half of the car (columns 0-6); the right half is the mirror image
#  o outline   L light paint   B paint   D dark paint   s stripe   g glass   h glass shine
#  w tire      y headlight     r taillight   G gold spoiler   . empty
#  (a single windshield glint is added after mirroring)
HALF = [
    "....ooo",
    "..oyyLs",
    ".oLLLLs",
    ".oLLLLs",
    "woLBBBs",
    "woLBBBs",
    "woLBBBs",
    "woLBBBs",
    ".oLBBBs",
    ".oDDggg",
    "DoDgggg",
    ".oDgggg",
    ".oDBBBs",
    ".oDLLLs",
    ".oDLLLs",
    ".oDLLLs",
    ".oDBBBs",
    ".oDgggg",
    ".oDDggg",
    "woDBBBs",
    "woLBBBs",
    "woLBBBs",
    "woLBBBs",
    ".oLBBBs",
    ".oDDDDs",
    ".ooGGGG",
    ".orrDDD",
    "..ooooo",
]

COMMON = {
    "o": (42, 20, 8),
    "w": (28, 24, 24),
    "y": (255, 240, 170),
    "r": (232, 52, 40),
    "g": (38, 52, 78),
    "h": (150, 190, 232),
    "G": (243, 180, 52),
}

GOLD_STRIPE = (243, 186, 60)
DARK_STRIPE = (60, 30, 12)

# (light, mid, dark, stripe) per car
PAINT = {
    1: ((236, 238, 244), (196, 200, 212), (138, 142, 158), GOLD_STRIPE),   # silver
    2: ((250, 96, 72), (214, 48, 40), (148, 24, 24), GOLD_STRIPE),         # red
    3: ((255, 226, 110), (240, 184, 48), (190, 128, 24), DARK_STRIPE),     # yellow
    4: ((96, 156, 250), (52, 108, 222), (30, 62, 150), GOLD_STRIPE),       # blue (CPU1)
    5: ((104, 214, 120), (44, 160, 80), (24, 104, 52), GOLD_STRIPE),       # green (CPU2)
    6: ((190, 130, 250), (136, 72, 204), (86, 40, 140), GOLD_STRIPE),      # purple (CPU3)
}

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets", "ui")
for car, (light, mid, dark, stripe) in PAINT.items():
    colors = dict(COMMON, L=light, B=mid, D=dark, s=stripe)
    img = Image.new("RGBA", (14, 28), (0, 0, 0, 0))
    for y, half in enumerate(HALF):
        row = half + half[::-1]
        for x, ch in enumerate(row):
            if ch != ".":
                img.putpixel((x, y), colors[ch] + (255,))
    img.putpixel((4, 10), colors["h"] + (255,))   # one glint on the windshield, top-left
    img.save(os.path.join(out, f"car{car}.png"))
print("cars ok")
