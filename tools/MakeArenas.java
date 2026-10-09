import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Paints the battle arenas (demolition derby, see BattleSystem): Junkyard, Stadium and Ice
 * Rink. Each is an open floor inside a wall, with a few obstacles to ram people into.
 *
 * Run from the project folder:  java tools/MakeArenas.java
 *
 * Maps are 1920 x 1440 world pixels, saved at 1/3 size (the game scales them up 3x, see
 * TrackMap.scale). The road mask is white wherever you can drive.
 */
public class MakeArenas {
    static final int W = 1920, H = 1440, S = 3, AW = W / S, AH = H / S;
    /** the floor: inside this rectangle (world coords), corners rounded */
    static final int X0 = 140, Y0 = 140, X1 = 1780, Y1 = 1300, CORNER = 160;
    static int[] art;
    static boolean[] floor;
    static Random rnd;

    static final int OUTLINE = rgb(0x221a17), WHITE = rgb(0xf6f3ea), BLACK = rgb(0x1b1b1f), RED = rgb(0xd9433b),
        YELLOW = rgb(0xf2c84b), LIT = rgb(0xffe58a);
    static final int[] PAINT = {rgb(0xd9433b), rgb(0x3b6fd9), rgb(0xf2c84b), rgb(0x3fae49), rgb(0xe08a2e),
        rgb(0x8a4ae0), rgb(0xf3f5f8), rgb(0x2a2a30)};

    public static void main(String[] args) throws Exception {
        junkyard();
        save("junkyard");
        stadium();
        save("stadium");
        rink();
        save("rink");
        System.out.println("arenas painted");
    }

    // ================================================================== layout

    static void begin(long seed) {
        art = new int[AW * AH];
        floor = new boolean[AW * AH];
        rnd = new Random(seed);
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                floor[ay * AW + ax] = insideFloor(wx(ax), wy(ay));
            }
        }
    }

    /** inside the rounded rectangle of the arena floor */
    static boolean insideFloor(float x, float y) {
        float cx = Math.max(X0 + CORNER, Math.min(X1 - CORNER, x));
        float cy = Math.max(Y0 + CORNER, Math.min(Y1 - CORNER, y));
        return x >= X0 && x <= X1 && y >= Y0 && y <= Y1 && Math.hypot(x - cx, y - cy) <= CORNER;
    }

    /** distance outside the floor's edge (negative inside) */
    static float outside(float x, float y) {
        float cx = Math.max(X0 + CORNER, Math.min(X1 - CORNER, x));
        float cy = Math.max(Y0 + CORNER, Math.min(Y1 - CORNER, y));
        return (float) Math.hypot(x - cx, y - cy) - CORNER;
    }

    /** an obstacle: takes a disc out of the floor (you can't drive through it) */
    static void obstacle(float x, float y, float r) {
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                if (Math.hypot(wx(ax) - x, wy(ay) - y) <= r) {
                    floor[ay * AW + ax] = false;
                }
            }
        }
    }

    static float wx(int ax) {
        return ax * S + 1.5f;
    }

    static float wy(int ay) {
        return H - ay * S - 1.5f;
    }

    // ================================================================== JUNKYARD

    static void junkyard() {
        begin(1);
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                float n = noise(ax, ay);
                int c = n > 0.62f ? rgb(0x8a6a48) : n > 0.35f ? rgb(0x7c5e40) : rgb(0x6f5338);
                if ((ax * 7 + ay * 3) % 23 == 0) c = rgb(0x5e4630);
                if ((ax * 5 + ay * 11) % 41 == 0) c = rgb(0x9a7a56);
                set(ax, ay, c);
            }
        }
        // oil stains and tyre marks on the floor
        for (int i = 0; i < 30; i++) {
            int ax = rnd.nextInt(AW), ay = rnd.nextInt(AH), r = 3 + rnd.nextInt(6);
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r * 2; dx <= r * 2; dx++) {
                    if (dx * dx / 4 + dy * dy <= r * r && rnd.nextInt(3) > 0) {
                        set(ax + dx, ay + dy, blend(get(ax + dx, ay + dy), BLACK, 0.35));
                    }
                }
            }
        }
        // scrap piles in the arena to dodge round (and ram people into)
        float[][] piles = {{700, 560, 70}, {1220, 880, 80}, {500, 900, 55}, {1430, 560, 55}, {960, 720, 40}};
        for (float[] p : piles) {
            obstacle(p[0], p[1], p[2]);
        }
        // the wall: crushed cars stacked all the way round
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                float o = outside(wx(ax), wy(ay));
                if (o > 0 && o < 70) {
                    int bx = ax / 7, by = ay / 4;
                    int c = PAINT[(bx * 3 + by * 5) % PAINT.length];
                    c = blend(c, rgb(0x5a4a3e), 0.45);
                    if (ax % 7 == 0 || ay % 4 == 0) c = OUTLINE;
                    else if ((ax + ay) % 9 == 0) c = rgb(0x8a5a36);        // rust
                    set(ax, ay, c);
                } else if (o >= 70) {
                    set(ax, ay, (ax + ay) % 5 == 0 ? rgb(0x5a4a3a) : rgb(0x4f4132));
                }
            }
        }
        for (float[] p : piles) {
            scrapPile(p[0], p[1], p[2]);
        }
        // tyre stacks and a crane in the corner
        for (int i = 0; i < 14; i++) {
            float x = X0 + 40 + rnd.nextInt(X1 - X0 - 80), y = Y0 + 30;
            if (i >= 7) y = Y1 - 30;
            tyres((int) (x / S), (int) ((H - y) / S));
        }
        crane(1600, 1180);
        floodlight(170, 170);
        floodlight(1750, 170);
        floodlight(170, 1270);
    }

    static void scrapPile(float x, float y, float r) {
        int cx = (int) (x / S), cy = (int) ((H - y) / S), rr = (int) (r / S);
        for (int dy = -rr - 2; dy <= rr + 2; dy++) {
            for (int dx = -rr - 2; dx <= rr + 2; dx++) {
                double d = Math.hypot(dx, dy) / rr;
                if (d <= 1.0) {
                    // chunks of scrap: blocks of rusty metal in a few muted colours, lit from the top left
                    int bx = Math.floorDiv(dx + 64, 4), by = Math.floorDiv(dy + 64, 3);
                    int hash = (bx * 73856093) ^ (by * 19349663) ^ cx;
                    int[] metal = {rgb(0x8a7a6a), rgb(0x6f6458), rgb(0x9a5a3a), rgb(0x5a6470), rgb(0x7a4a32)};
                    int c = metal[Math.floorMod(hash >>> 3, metal.length)];
                    if (Math.floorMod(dx + 64, 4) == 0 || Math.floorMod(dy + 64, 3) == 0) c = blend(c, OUTLINE, 0.5);
                    c = blend(c, (dx + dy < 0) ? WHITE : BLACK, Math.min(0.35, 0.25 * Math.abs(dx + dy) / Math.max(1, rr)));
                    set(cx + dx, cy + dy, c);
                } else if (d <= 1.12) {
                    set(cx + dx, cy + dy, OUTLINE);
                } else if (d < 1.4 && dx + dy > 0) {
                    shade(cx + dx, cy + dy, 0.75);
                }
            }
        }
        // a crushed car on top
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -5; dx <= 5; dx++) {
                set(cx + dx - 2, cy + dy - 3, dy == 0 ? rgb(0x26344e) : PAINT[(cx + cy) % PAINT.length]);
            }
        }
    }

    static void tyres(int ax, int ay) {
        for (int k = 0; k < 3; k++) {
            disc(ax + k * 2, ay - k, 3, OUTLINE);
            disc(ax + k * 2, ay - k, 2, rgb(0x2a2a2e));
            set(ax + k * 2, ay - k, rgb(0x4a4a50));
        }
    }

    static void crane(float x, float y) {
        int ax = (int) (x / S), ay = (int) ((H - y) / S);
        for (int dy = 0; dy < 12; dy++) {
            for (int dx = 0; dx < 12; dx++) {
                set(ax + dx, ay + dy, dy < 2 ? rgb(0xffd25a) : YELLOW);
            }
        }
        outline(ax, ay, 12, 12);
        for (int k = 0; k < 50; k++) {
            set(ax + 6 - k, ay + 6 - k / 3, k % 4 == 0 ? OUTLINE : YELLOW);
            set(ax + 6 - k, ay + 7 - k / 3, k % 4 == 0 ? OUTLINE : rgb(0xc99a12));
        }
        disc(ax - 44, ay - 10, 4, rgb(0x5a5c62));   // the magnet
        circle(ax - 44, ay - 10, 4, OUTLINE);
    }

    // ================================================================== STADIUM

    static void stadium() {
        begin(2);
        // the crowd in the stands, all round
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                float o = outside(wx(ax), wy(ay));
                int c;
                if (o <= 0) {
                    float n = noise(ax, ay);
                    c = n > 0.55f ? rgb(0xa9744a) : rgb(0x9c6a42);                 // clay
                    if ((ax * 7 + ay * 3) % 19 == 0) c = rgb(0x8a5c38);
                } else if (o < 30) {
                    c = (o < 6) ? WHITE : ((ax / 4 + ay / 4) % 2 == 0 ? RED : WHITE);   // the barrier
                    if (o >= 6 && o < 9) c = OUTLINE;
                } else if (o < 46) {
                    c = rgb(0x5a5c62);                                              // walkway
                } else {
                    // spectators in team colours, red one end and blue the other
                    boolean redTeam = ax < AW / 2;
                    c = crowd(ax, ay, redTeam ? new int[] {RED, rgb(0xff8a7a), WHITE, rgb(0xb02a22)}
                        : new int[] {rgb(0x3b6fd9), rgb(0x8fb8e8), WHITE, rgb(0x24489a)});
                }
                set(ax, ay, c);
            }
        }
        // hay bales and barrels in the middle, a big VAR logo painted on the dirt
        float[][] bales = {{760, 720, 36}, {1160, 720, 36}, {960, 500, 30}, {960, 940, 30}};
        for (float[] b : bales) {
            obstacle(b[0], b[1], b[2]);
        }
        logo(960, 720);
        for (float[] b : bales) {
            hay(b[0], b[1], b[2]);
        }
        for (int i = 0; i < 4; i++) {
            floodlight(i % 2 == 0 ? 90 : 1830, i < 2 ? 90 : 1350);
        }
        // the scoreboard over the top stand
        int sx = 760 / S, sy = (H - 1400) / S;
        for (int dy = 0; dy < 12; dy++) {
            for (int dx = 0; dx < 134; dx++) {
                set(sx + dx, sy + dy, dy == 0 || dy == 11 || dx == 0 || dx == 133 ? OUTLINE : (dy > 3 && dy < 8 && dx % 6 < 4 ? LIT : BLACK));
            }
        }
    }

    static void hay(float x, float y, float r) {
        int cx = (int) (x / S), cy = (int) ((H - y) / S), rr = (int) (r / S);
        for (int dy = -rr; dy <= rr; dy++) {
            for (int dx = -rr; dx <= rr; dx++) {
                double d = Math.hypot(dx, dy);
                if (d <= rr) {
                    int c = d > rr - 1 ? rgb(0x8a6a1e) : (Math.abs(dx) % 4 == 0 ? rgb(0xd8a83a) : rgb(0xf2c84b));
                    set(cx + dx, cy + dy, c);
                }
            }
        }
        circle(cx, cy, rr, OUTLINE);
        for (int k = 1; k < 4; k++) {
            shade(cx + rr + k, cy + k, 0.7);
        }
    }

    /** "VAR" in big pale letters across the middle of the arena floor */
    static void logo(float x, float y) {
        String[] letters = {
            "#...#.###.####.",
            "#...#.#.#.#..#.",
            "#...#.###.####.",
            ".#.#..#.#.#.#..",
            "..#...#.#.#..#."};
        int cx = (int) (x / S) - 15 * 3 / 2, cy = (int) ((H - y) / S) - 5 * 3 / 2;
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 15; c++) {
                if (letters[r].charAt(c) == '#') {
                    for (int k = 0; k < 9; k++) {
                        int px = cx + c * 3 + k % 3, py = cy + r * 3 + k / 3;
                        set(px, py, blend(get(px, py), WHITE, 0.35));
                    }
                }
            }
        }
    }

    // ================================================================== ICE RINK

    static void rink() {
        begin(3);
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                float x = wx(ax), y = wy(ay);
                float o = outside(x, y);
                int c;
                if (o <= 0) {
                    float n = noise(ax, ay);
                    c = n > 0.6f ? rgb(0xeaf4fb) : n > 0.3f ? rgb(0xdfeef8) : rgb(0xd2e6f3);
                    if ((ax * 5 + ay * 3) % 37 == 0) c = WHITE;                       // scratches
                } else if (o < 18) {
                    c = o < 4 ? rgb(0xf2c84b) : WHITE;                                 // the boards
                    if (o >= 4 && o < 7) c = rgb(0x9fb6c8);
                } else if (o < 26) {
                    c = rgb(0x8fb8e8);                                                 // glass
                } else {
                    c = crowd(ax, ay, PAINT);
                }
                set(ax, ay, c);
            }
        }
        // hockey markings: centre line, blue lines, faceoff circles and the goal creases
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                float x = wx(ax), y = wy(ay);
                if (outside(x, y) > 0) continue;
                if (Math.abs(x - 960) < 6) set(ax, ay, RED);
                if (Math.abs(x - 640) < 7 || Math.abs(x - 1280) < 7) set(ax, ay, rgb(0x3b6fd9));
                double dc = Math.hypot(x - 960, y - 720);
                if (Math.abs(dc - 150) < 4) set(ax, ay, rgb(0x3b6fd9));
                for (float[] f : new float[][] {{400, 420}, {400, 1020}, {1520, 420}, {1520, 1020}}) {
                    if (Math.abs(Math.hypot(x - f[0], y - f[1]) - 110) < 4) set(ax, ay, RED);
                }
            }
        }
        disc(960 / S, (H - 720) / S, 4, rgb(0x3b6fd9));
        // goals at both ends (things to ram people into) and pylons
        obstacle(250, 720, 40);
        obstacle(1670, 720, 40);
        goal(250, 720, 1);
        goal(1670, 720, -1);
        float[][] pylons = {{640, 720}, {1280, 720}};
        for (float[] p : pylons) {
            obstacle(p[0], p[1], 22);
            pylon(p[0], p[1]);
        }
        for (int i = 0; i < 4; i++) {
            floodlight(i % 2 == 0 ? 90 : 1830, i < 2 ? 90 : 1350);
        }
    }

    static void goal(float x, float y, int facing) {
        int cx = (int) (x / S), cy = (int) ((H - y) / S);
        for (int dy = -12; dy <= 12; dy++) {
            for (int dx = -9; dx <= 9; dx++) {
                boolean frame = Math.abs(dy) >= 11 || (facing > 0 ? dx <= -8 : dx >= 8);
                boolean net = (dx + dy) % 3 == 0 || (dx - dy) % 3 == 0;
                if (frame) set(cx + dx, cy + dy, RED);
                else if (net && (facing > 0 ? dx < 4 : dx > -4)) set(cx + dx, cy + dy, WHITE);
            }
        }
    }

    static void pylon(float x, float y) {
        int cx = (int) (x / S), cy = (int) ((H - y) / S);
        disc(cx, cy, 7, OUTLINE);
        disc(cx, cy, 6, rgb(0xf08a2e));
        disc(cx, cy, 3, WHITE);
        disc(cx, cy, 1, rgb(0xf08a2e));
    }

    // ================================================================== shared

    /**
     * a crowd seen from above: rows of seats, each with a head and shoulders (or empty)
     * @param shirts the colours people are wearing
     */
    static int crowd(int ax, int ay, int[] shirts) {
        int cx = ax / 3, cy = ay / 4, px = ax % 3, py = ay % 4;
        int hash = (cx * 73856093) ^ (cy * 19349663);
        hash ^= hash >>> 13;
        boolean empty = (hash & 7) == 0;
        if (py == 3) {
            return rgb(0x2a2c33);                                        // the step behind the row
        }
        if (empty || px == 2) {
            return py == 2 ? rgb(0x4a4d56) : rgb(0x3a3d46);              // an empty seat / the gap
        }
        if (py == 0) {
            int[] hair = {rgb(0x2a1d17), rgb(0x5a3a22), rgb(0xd8b04a), rgb(0x1b1b1f)};
            return px == 0 ? hair[(hash >>> 4) & 3] : rgb(0xf0c8a0);    // a head
        }
        return shirts[((hash >>> 6) & 0x7fff) % shirts.length];          // shoulders
    }

    static void floodlight(int x, int y) {
        int cx = x / S, cy = (H - y) / S;
        for (int dy = 0; dy < 6; dy++) {
            for (int dx = 0; dx < 10; dx++) {
                set(cx - 5 + dx, cy - 3 + dy, dy == 0 || dy == 5 || dx == 0 || dx == 9 ? OUTLINE : (dx % 3 == 1 ? LIT : rgb(0xd8d4ca)));
            }
        }
    }

    static void save(String folder) throws Exception {
        new File("assets/maps/" + folder).mkdirs();
        BufferedImage map = new BufferedImage(AW, AH, BufferedImage.TYPE_INT_ARGB);
        BufferedImage mask = new BufferedImage(AW, AH, BufferedImage.TYPE_INT_RGB);
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                map.setRGB(ax, ay, art[ay * AW + ax]);
                mask.setRGB(ax, ay, floor[ay * AW + ax] ? 0xffffff : 0);
            }
        }
        ImageIO.write(map, "png", new File("assets/maps/" + folder + "/background.png"));
        ImageIO.write(mask, "png", new File("assets/maps/" + folder + "/road_mask.png"));
        // the picker's preview: the arena centred on a dark background
        BufferedImage preview = new BufferedImage(384, 216, BufferedImage.TYPE_INT_RGB);
        double scale = Math.min(384.0 / AW, 216.0 / AH);
        int pw = (int) (AW * scale), ph = (int) (AH * scale), ox = (384 - pw) / 2, oy = (216 - ph) / 2;
        for (int y = 0; y < 216; y++) {
            for (int x = 0; x < 384; x++) {
                int sx = (int) ((x - ox) / scale), sy = (int) ((y - oy) / scale);
                preview.setRGB(x, y, sx >= 0 && sy >= 0 && sx < AW && sy < AH ? art[sy * AW + sx] : 0x1c1714);
            }
        }
        ImageIO.write(preview, "png", new File("assets/maps/" + folder + "/preview.png"));
        System.out.println(folder);
    }

    static float noise(int ax, int ay) {
        double v = Math.sin(ax * 0.07) * Math.cos(ay * 0.05) + Math.sin((ax + ay) * 0.031) * 0.7
            + Math.sin(ax * 0.19 + ay * 0.13) * 0.3;
        return (float) ((v + 2) / 4);
    }

    static int rgb(int hex) {
        return 0xff000000 | hex;
    }

    static void set(int ax, int ay, int c) {
        if (ax >= 0 && ay >= 0 && ax < AW && ay < AH) {
            art[ay * AW + ax] = c;
        }
    }

    static int get(int ax, int ay) {
        return ax >= 0 && ay >= 0 && ax < AW && ay < AH ? art[ay * AW + ax] : 0xff000000;
    }

    static void shade(int ax, int ay, double f) {
        set(ax, ay, blend(get(ax, ay), rgb(0), 1 - f));
    }

    static void disc(int cx, int cy, int r, int c) {
        for (int y = -r; y <= r; y++) {
            for (int x = -r; x <= r; x++) {
                if (x * x + y * y <= r * r + r / 2) {
                    set(cx + x, cy + y, c);
                }
            }
        }
    }

    static void circle(int cx, int cy, int r, int c) {
        for (int a = 0; a < 360; a += 3) {
            set(cx + (int) Math.round(r * Math.cos(Math.toRadians(a))), cy + (int) Math.round(r * Math.sin(Math.toRadians(a))), c);
        }
    }

    static void outline(int ax, int ay, int w, int h) {
        for (int x = -1; x <= w; x++) {
            set(ax + x, ay - 1, OUTLINE);
            set(ax + x, ay + h, OUTLINE);
        }
        for (int y = -1; y <= h; y++) {
            set(ax - 1, ay + y, OUTLINE);
            set(ax + w, ay + y, OUTLINE);
        }
    }

    static int blend(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }
}
