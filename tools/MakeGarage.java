import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * Pixel art for the garage: five car classes (see CarModel), three paint jobs each.
 * Every car is 14 x 28 (nose up), drawn as its left half and mirrored so it's symmetric.
 *
 * Run from the project folder:  java tools/MakeGarage.java  ->  assets/ui/cars/(model)_(paint).png
 *
 * Letters: o outline, w tire, y headlight, r taillight, L light paint, B paint, D dark paint,
 * s stripe, g glass, h glass shine, i interior (seats), G spoiler, k black trim, . empty
 */
public class MakeGarage {
    // 0 SPRINTER: a short, rounded hatchback with a big rear window
    static final String[] SPRINTER = {
        ".......", ".......", ".......",
        "....ooo",
        "..oyyLL",
        ".oLLLLL",
        "woLBBBB",
        "woLBBBB",
        "woLBBBB",
        ".oLBBBB",
        ".oDgggg",
        ".oDgggh",
        ".oDgggg",
        ".oDBBBB",
        ".oDLLLL",
        ".oDLLLL",
        ".oDLLLL",
        ".oDBBBB",
        ".oDgggg",
        ".oDgggg",
        "woDBBBB",
        "woLBBBB",
        "woLBBBB",
        ".oDDDDD",
        ".orrDDD",
        "..ooooo",
        ".......", ".......",
    };
    // 1 ROADSTER: open top, two seats behind a short windshield
    static final String[] ROADSTER = {
        ".......",
        "....ooo",
        "..oyyLL",
        ".oLLLLL",
        ".oLBBBB",
        "woLBBBB",
        "woLBBBB",
        "woLBBBB",
        ".oLBBBB",
        ".oLBBBB",
        ".oDgggg",
        ".oDkkkk",
        ".oDiiik",
        ".oDiiik",
        ".oDiiik",
        ".oDkkkk",
        ".oDBBBB",
        ".oDBBBB",
        ".oDBBBB",
        "woDBBBB",
        "woLBBBB",
        "woLBBBB",
        "woLBBBB",
        ".oLBBBB",
        ".oDDDDD",
        ".orrDDD",
        "..ooooo",
        ".......",
    };
    // 2 GT: long and low, a long hood and a big wing
    static final String[] GT = {
        "....ooo",
        "..oyyLs",
        ".oLLLLs",
        ".oLBBBs",
        ".oLBBBs",
        "woLBBBs",
        "woLBBBs",
        "woLBBBs",
        ".oLBBBs",
        ".oLBBBs",
        ".oLBBBs",
        ".oDgggg",
        ".oDgggh",
        ".oDgggg",
        ".oDLLLs",
        ".oDLLLs",
        ".oDgggg",
        ".oDBBBs",
        "woDBBBs",
        "woLBBBs",
        "woLBBBs",
        "woLBBBs",
        ".oDBBBs",
        ".oDDDDs",
        "GGGGGGG",
        ".oGGGGG",
        ".orrDDD",
        "..ooooo",
    };
    // 3 MUSCLE: wide, twin racing stripes and a hood scoop
    static final String[] MUSCLE = {
        "...oooo",
        "..oyyLL",
        ".oLLLss",
        "woLBBss",
        "woLBBss",
        "woLBkss",
        "woLBkss",
        ".oLBBss",
        ".oLBBss",
        ".oDgggg",
        ".oDgggh",
        ".oDgggg",
        ".oDLLss",
        ".oDLLss",
        ".oDLLss",
        ".oDLLss",
        ".oDgggg",
        ".oDgggg",
        ".oDBBss",
        "woDBBss",
        "woLBBss",
        "woLBBss",
        "woLBBss",
        ".oDBBss",
        ".oDDDss",
        ".orrrDD",
        "..ooooo",
        ".......",
    };
    // 4 HAULER: a pickup truck, square cab up front and an open bed behind
    static final String[] HAULER = {
        "..ooooo",
        ".oyyLLL",
        "woLLLLL",
        "woLBBBB",
        "woLBBBB",
        "woLBBBB",
        ".oLBBBB",
        ".oDgggg",
        ".oDgggh",
        ".oDLLLL",
        ".oDLLLL",
        ".oDLLLL",
        ".oDgggg",
        ".okkkkk",
        ".oDkiii",
        ".oDkDDD",
        ".oDkiii",
        ".oDkDDD",
        "woDkiii",
        "woDkDDD",
        "woDkiii",
        "woDkDDD",
        ".oDkiii",
        ".oDkkkk",
        ".oDDDDD",
        ".orrDDD",
        ".oooooo",
        ".......",
    };
    static final String[][] MODELS = {SPRINTER, ROADSTER, GT, MUSCLE, HAULER};
    static final String[] NAMES = {"sprinter", "roadster", "gt", "muscle", "hauler"};

    // paint jobs per model: {light, mid, dark, stripe}
    static final int[][][] PAINTS = {
        {{0x9fe7ff, 0x3fb6e8, 0x1f7aa8, 0xffffff}, {0xfff2a8, 0xf2d04a, 0xb8962a, 0x2a2a30}, {0xffb6d9, 0xf06aa8, 0xb03a76, 0xffffff}},
        {{0xff9a86, 0xe0402e, 0x9a2420, 0xf3ba3c}, {0xf3f5f8, 0xc8ccd6, 0x8a8e9e, 0xe0402e}, {0x9ff0b8, 0x3fbf6a, 0x22804a, 0xffffff}},
        {{0xe8ecf4, 0xb8bfcc, 0x7c8494, 0xf3ba3c}, {0x6a7cff, 0x2f44d8, 0x1a2690, 0xffffff}, {0xffb86a, 0xf0802a, 0xb04f12, 0x2a2a30}},
        {{0xffd36a, 0xf2a81c, 0xa86a0a, 0x1c1c22}, {0x5a5c66, 0x2e3038, 0x18191e, 0xe0402e}, {0xc8a0ff, 0x8a4ae0, 0x562a9a, 0xffffff}},
        {{0xc9a07a, 0x8a6240, 0x5a3e26, 0xf3ba3c}, {0x8fc88a, 0x4f8f4a, 0x2f5a2c, 0xf3f5f8}, {0xf3f5f8, 0xd8dbe2, 0x9aa0ac, 0x2f44d8}},
    };

    public static void main(String[] args) throws Exception {
        new File("assets/ui/cars").mkdirs();
        for (int m = 0; m < MODELS.length; m++) {
            for (int p = 0; p < 3; p++) {
                ImageIO.write(draw(MODELS[m], PAINTS[m][p]), "png",
                    new File("assets/ui/cars/" + NAMES[m] + "_" + p + ".png"));
            }
        }
        System.out.println("garage cars drawn");
    }

    static BufferedImage draw(String[] half, int[] paint) {
        if (half.length != 28) {
            throw new IllegalStateException("car needs 28 rows, has " + half.length);
        }
        BufferedImage img = new BufferedImage(14, 28, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 28; y++) {
            String row = half[y];
            if (row.length() != 7) {
                throw new IllegalStateException("row " + y + " is " + row.length() + " wide: " + row);
            }
            for (int x = 0; x < 7; x++) {
                int c = color(row.charAt(x), paint);
                img.setRGB(x, y, c);
                // the right half mirrors the left, except the glass shine (one glint only)
                char mirror = row.charAt(x) == 'h' ? 'g' : row.charAt(x);
                img.setRGB(13 - x, y, color(mirror, paint));
            }
        }
        return img;
    }

    static int color(char c, int[] paint) {
        switch (c) {
            case 'o': return 0xff2a1408;
            case 'w': return 0xff1c1818;
            case 'y': return 0xfffff0aa;
            case 'r': return 0xffe83428;
            case 'g': return 0xff26344e;
            case 'h': return 0xff96bee8;
            case 'i': return 0xff4a3426;
            case 'k': return 0xff1e1e24;
            case 'G': return 0xff1e1e24;
            case 'L': return 0xff000000 | paint[0];
            case 'B': return 0xff000000 | paint[1];
            case 'D': return 0xff000000 | paint[2];
            case 's': return 0xff000000 | paint[3];
            default: return 0;
        }
    }
}
