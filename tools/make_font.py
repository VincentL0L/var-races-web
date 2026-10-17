"""Converts the Michroma typeface (SIL Open Font License, see Michroma-OFL.txt) to the web font
the game uses for small text: assets/fonts/michroma.woff2.
Run: python3 tools/make_font.py   (needs fonttools and brotli: pip install fonttools brotli)
"""
import os

from fontTools.ttLib import TTFont

here = os.path.dirname(os.path.abspath(__file__))
font = TTFont(os.path.join(here, "Michroma-Regular.ttf"))
font.flavor = "woff2"
out = os.path.join(here, "..", "assets", "fonts", "michroma.woff2")
font.save(out)
print("wrote", os.path.normpath(out), os.path.getsize(out), "bytes")
