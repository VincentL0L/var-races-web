import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Paints the four sprint maps (one run from start to finish, see TrackMap.pointToPoint):
 * Summit Sprint, Rush Hour Getaway, Rooftop Run and Moon Base, plus the rover sprite and the
 * map previews.
 *
 * Run from the project folder:  java tools/MakeSprints.java
 *
 * Like the other maps every course starts at (200, 300) heading up, so the starting grid
 * works the same; the road also runs a little way back below the start for the grid.
 * Roads can go at any angle and change width along the way. Maps are painted at 1/3 size
 * and scaled up 3x; coordinates are world coordinates (y up) unless called ax / ay.
 */
public class MakeSprints {
    static final int S = 3;
    static int W, H, AW, AH;
    static int[] art;
    /** art pixels taken by water or landmarks, so trees and rocks go around them */
    static boolean[] blocked;
    static Random rnd;

    /** the road: points from the grid (below the start) to the run-off past the finish */
    static float[][] pts;
    /** half the road's width along each stretch */
    static float[] half;
    /** drivable run-off beside the road (the sidewalk / verge) */
    static final float WALK = 18;

    // palette
    static final int OUTLINE = rgb(0x221a17), WHITE = rgb(0xf6f3ea), BLACK = rgb(0x1b1b1f), LIT = rgb(0xffe58a),
        GLASS = rgb(0x9fd3f0), GLASS_DARK = rgb(0x6aa6cf), RED = rgb(0xd9433b), YELLOW = rgb(0xf2c84b);

    public static void main(String[] args) throws Exception {
        summit();
        save("summit");
        rushHour();
        save("rushhour");
        rooftop();
        save("rooftop");
        moon();
        save("moon");
        ImageIO.write(rover(), "png", new File("assets/ui/rover.png"));
        System.out.println("sprint maps painted");
    }

    // ================================================================== the courses (same as Waypoints)

    /** Summit Sprint: five sets of switchbacks up the mountain (an S-bend in some legs) */
    static float[][] summitCourse() {
        java.util.List<float[]> p = new java.util.ArrayList<>();
        p.add(new float[] {200, 120});
        p.add(new float[] {200, 300});
        p.add(new float[] {200, 620});
        float y = 620;
        for (int cycle = 0; cycle < 5; cycle++) {
            boolean bend = cycle % 2 == 1;
            if (bend) {
                p.add(new float[] {900, y + 70});
                p.add(new float[] {1050, y + 230});
            }
            p.add(new float[] {1650, y + 300});
            p.add(new float[] {1730, y + 560});
            if (!bend) {
                p.add(new float[] {1000, y + 640});
                p.add(new float[] {850, y + 800});
            }
            p.add(new float[] {300, y + 860});
            p.add(new float[] {230, y + 1120});
            y += 1120;
        }
        p.add(new float[] {1100, y + 260});
        p.add(new float[] {1100, y + 560});
        p.add(new float[] {1100, y + 700});
        return p.toArray(new float[0][]);
    }

    static final float[][] RUSH = {
        {200, 120}, {200, 300}, {200, 1100}, {1200, 1100}, {1200, 1700}, {1750, 1700}, {2550, 1700},
        {2550, 1960}, {1950, 1960}, {1950, 2220}, {2650, 2220}, {3050, 2220}, {3050, 3000}, {1700, 3000},
        {1100, 3000}, {1100, 3480}, {3450, 3480}, {3450, 2850}, {3450, 2700}};
    static final float[] RUSH_HALF = {60, 60, 60, 50, 54, 54, 54, 54, 54, 54, 60, 60, 60, 60, 60, 60, 60, 60};

    static final float[][] ROOF = {
        {200, 120}, {200, 300}, {200, 900}, {700, 900}, {1100, 900}, {1500, 900}, {1500, 1400}, {2100, 1400},
        {2500, 1400}, {2900, 1400}, {3300, 1000}, {3700, 1000}, {4100, 1000}, {4500, 1000}, {4500, 500},
        {5000, 500}, {5400, 500}, {5800, 500}, {5800, 1200}, {6300, 1200}, {6650, 1200}, {6900, 1200}, {7050, 1200}};
    /** which stretches are sky bridges (narrow, with railings) */
    static final int[] ROOF_BRIDGES = {3, 7, 11, 15, 19};
    /** the rooftops the course runs across: x0, y0, x1, y1, roof colour */
    static final int[][] ROOF_TOPS = {
        {60, 100, 860, 1060, 0xa58f7c}, {1260, 760, 2290, 1560, 0x9aa5b1}, {2690, 850, 3900, 1560, 0xb3a998},
        {4260, 340, 5170, 1160, 0x8796a6}, {5560, 340, 6460, 1360, 0xa58f7c}, {6610, 980, 7170, 1440, 0x9aa5b1}};

    static final float[][] MOON = {
        {200, 120}, {200, 300}, {200, 1100}, {1100, 1100}, {1700, 1800}, {1700, 2700}, {2900, 2700},
        {3500, 2000}, {3500, 900}, {4400, 900}, {4900, 1500}, {4900, 2550}, {4900, 2700}};

    // ================================================================== geometry

    /** distance from (x, y) to the nearest road edge: negative on the road, positive off it */
    static float edge(float x, float y) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i + 1 < pts.length; i++) {
            float d = segDist(x, y, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]) - half[i];
            best = Math.min(best, d);
        }
        return best;
    }

    /** which stretch is nearest, and how far along it (0..1), for road markings */
    static float[] nearest(float x, float y) {
        float best = Float.MAX_VALUE, bi = 0, bt = 0;
        for (int i = 0; i + 1 < pts.length; i++) {
            float ax = pts[i][0], ay = pts[i][1], bx = pts[i + 1][0], by = pts[i + 1][1];
            float t = proj(x, y, ax, ay, bx, by);
            float d = (float) Math.hypot(x - (ax + (bx - ax) * t), y - (ay + (by - ay) * t)) - half[i];
            if (d < best) {
                best = d;
                bi = i;
                bt = t;
            }
        }
        return new float[] {bi, bt, best};
    }

    static float proj(float x, float y, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        return l2 < 1e-6f ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / l2));
    }

    static float segDist(float x, float y, float ax, float ay, float bx, float by) {
        float t = proj(x, y, ax, ay, bx, by);
        return (float) Math.hypot(x - (ax + (bx - ax) * t), y - (ay + (by - ay) * t));
    }

    static void begin(float[][] course, int w, int h, long seed, float startHalf, float endHalf) {
        printWaypoints(course);
        pts = course;
        W = w;
        H = h;
        AW = W / S;
        AH = H / S;
        art = new int[AW * AH];
        blocked = new boolean[AW * AH];
        rnd = new Random(seed);
        half = new float[pts.length - 1];
        for (int i = 0; i < half.length; i++) {
            half[i] = startHalf + (endHalf - startHalf) * i / Math.max(1, half.length - 1);
        }
    }

    /** world point of art pixel */
    static float wx(int ax) {
        return ax * S + 1.5f;
    }

    static float wy(int ay) {
        return H - ay * S - 1.5f;
    }

    /** room for scenery: well clear of the road */
    static boolean clear(float x, float y, float margin) {
        int ax = (int) (x / S), ay = (int) ((H - y) / S);
        return x > 0 && y > 0 && x < W && y < H && edge(x, y) > WALK + 4 + margin
            && (ax < 0 || ay < 0 || ax >= AW || ay >= AH || !blocked[ay * AW + ax]);
    }

    // ================================================================== SUMMIT SPRINT

    static void summit() {
        // tall: the road climbs the whole way up, getting narrower near the top
        begin(summitCourse(), 1920, 7200, 3000, 64, 44);
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                float y = wy(ay);
                float n = noise(ax, ay);
                // forest at the bottom, rock in the middle, snow at the top (dithered edges)
                float zone = y / H + (n - 0.5f) * 0.06f;
                int c;
                if (zone < 0.36f) {
                    c = ((ax / 2 + ay / 2) % 2 == 0) ? rgb(0x5e9e48) : rgb(0x56943f);
                    if ((ax * 7 + ay * 3) % 29 == 0) c = rgb(0x7cbc5a);
                } else if (zone < 0.66f) {
                    c = n > 0.6f ? rgb(0x9a8f80) : n > 0.3f ? rgb(0x8a8072) : rgb(0x7c7366);
                    if ((ax * 5 + ay * 11) % 37 == 0) c = rgb(0xb3a998);
                } else {
                    c = n > 0.55f ? rgb(0xf4f7fb) : rgb(0xe3ebf3);
                    if ((ax * 3 + ay * 7) % 41 == 0) c = rgb(0xc9d6e4);
                }
                set(ax, ay, c);
            }
        }
        // a mountain lake in the forest, cliffs and boulders on the slopes
        lake(1000, 430, 230, 110);
        lake(1500, 1900, 160, 80);
        for (int i = 0; i < 160; i++) {
            float x = rnd.nextInt(W), y = H * 0.36f + rnd.nextInt((int) (H * 0.3f));
            if (clear(x, y, 20)) {
                boulder((int) (x / S), (int) ((H - y) / S), 3 + rnd.nextInt(5));
            }
        }
        for (int i = 0; i < 70; i++) {
            float x = rnd.nextInt(W), y = H * 0.35f + rnd.nextInt((int) (H * 0.33f));
            if (clear(x, y, 40)) {
                cliff((int) (x / S), (int) ((H - y) / S), 16 + rnd.nextInt(18));
            }
        }
        // pine trees: thick in the forest, thinning out, snowy up high
        for (int i = 0; i < 3600; i++) {
            float x = rnd.nextInt(W), y = rnd.nextInt(H);
            float zone = y / H;
            if (zone > 0.36f && rnd.nextFloat() < (zone - 0.36f) * 3.2f) {
                continue;
            }
            if (zone > 0.8f) {
                continue;
            }
            if (clear(x, y, 8)) {
                pine((int) (x / S), (int) ((H - y) / S), zone > 0.6f, 4 + rnd.nextInt(3));
            }
        }
        paintRoad(rgb(0x45474e), rgb(0x3f4147), rgb(0xf2c84b), false, MountainSide.INSTANCE);
        observatory(1100, 6860);
        startLine();
        finishLine();
    }

    /** the guard rail on the mountain road: white posts and a steel rail */
    enum MountainSide { INSTANCE }

    static void lake(int cx, int cy, int rx, int ry) {
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                float x = wx(ax), y = wy(ay);
                double d = Math.pow((x - cx) / rx, 2) + Math.pow((y - cy) / ry, 2) + 0.08 * Math.sin(x / 23.0);
                if (d < 1.25 && ax >= 0) {
                    blocked[ay * AW + ax] = true;
                }
                if (d < 1 && edge(x, y) > WALK + 6) {
                    int c = d > 0.85 ? rgb(0x8a8072) : ((ax * 5 + ay * 9) % 31 == 0) ? rgb(0xbfe6ff) : d > 0.6 ? rgb(0x3378c0) : rgb(0x3f8fd8);
                    set(ax, ay, c);
                }
            }
        }
    }

    static void pine(int ax, int ay, boolean snowy, int size) {
        for (int dx = -size; dx <= size; dx++) {
            shade(ax + dx + 1, ay + 2, 0.72);
        }
        set(ax, ay + 1, rgb(0x5e3820));
        set(ax, ay, rgb(0x7a4a2a));
        // three tiers, wider at the bottom
        for (int tier = 0; tier < 3; tier++) {
            int top = ay - 2 - tier * (size - 1);
            int w = size - tier;
            for (int dy = 0; dy < size; dy++) {
                int span = Math.max(0, w * dy / size);
                for (int dx = -span; dx <= span; dx++) {
                    boolean edgePx = Math.abs(dx) == span || dy == size - 1;
                    int c = edgePx ? rgb(0x1e3b24) : dx < 0 ? rgb(0x3d7a3f) : rgb(0x2c5e31);
                    if (snowy && dy < 2 && !edgePx) c = WHITE;
                    set(ax + dx, top - size + dy + 1, c);
                }
            }
        }
    }

    static void boulder(int ax, int ay, int r) {
        disc(ax + 1, ay + 1, r, rgb(0x4f4840));
        disc(ax, ay, r, rgb(0x8f8678));
        disc(ax - r / 3, ay - r / 3, Math.max(1, r / 2), rgb(0xb3aa9b));
        circleOutline(ax, ay, r, OUTLINE);
    }

    /** a rock face: stepped grey ledges with dark cracks */
    static void cliff(int ax, int ay, int size) {
        for (int dy = 0; dy < size; dy++) {
            for (int dx = -size; dx <= size; dx++) {
                double d = Math.pow(dx / (double) size, 2) + Math.pow((dy - size / 2.0) / (size / 2.0), 2);
                if (d < 1 && freeAt(ax + dx, ay + dy)) {
                    int ledge = (dy + Math.abs(dx) / 3) % 6;
                    int c = ledge == 0 ? rgb(0x5a5248) : ledge < 3 ? rgb(0xa49a8a) : rgb(0x887e70);
                    if ((dx * 7 + dy * 13) % 23 == 0) c = rgb(0x3e3832);
                    set(ax + dx, ay + dy, c);
                }
            }
        }
    }

    static void observatory(int x, int y) {
        int ax = x / S, ay = (H - y) / S;
        int ox = ax + 30, oy = ay - 8;
        // the building, then the silver dome with its open slit
        for (int dy = 0; dy < 8; dy++) {
            for (int dx = -12; dx <= 12; dx++) {
                set(ox + dx, oy + dy, Math.abs(dx) == 12 || dy == 7 ? OUTLINE : dy < 2 ? rgb(0xc9c3b6) : WHITE);
            }
        }
        for (int dy = 0; dy < 12; dy++) {
            int w = (int) Math.round(12 * Math.sqrt(1 - Math.pow(dy / 12.0, 2)));
            for (int dx = -w; dx <= w; dx++) {
                int c = Math.abs(dx) == w ? OUTLINE : dx < -3 ? rgb(0xf2f4f7) : rgb(0xc8ccd4);
                if (Math.abs(dx) <= 1 && dy > 2) c = rgb(0x26344e);
                set(ox + dx, oy - dy, c);
            }
        }
        set(ox, oy - 13, RED);
    }

    // ================================================================== RUSH HOUR GETAWAY

    static void rushHour() {
        begin(RUSH, 3840, 3840, 1929, 60, 60);
        half = RUSH_HALF.clone();
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                set(ax, ay, (ax % 8 == 0 || ay % 8 == 0) ? rgb(0xaaa395) : rgb(0xbdb6a7));
            }
        }
        // the harbor along the top and the right
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                float x = wx(ax), y = wy(ay);
                if (y > H - 110 - 6 * Math.sin(x / 37.0) || (x > W - 110 + 4 * Math.sin(y / 29.0) && y > 400)) {
                    if (edge(x, y) > 0) {
                        int n = (ax * 5 + (ay / 2) * 11) % 29;
                        set(ax, ay, n == 0 ? rgb(0xbfe6ff) : rgb(0x3f8fd8));
                        blocked[ay * AW + ax] = true;
                    }
                }
            }
        }
        garage(1640, 1560, 2760, 2340);
        block(1640, 1560, 2760, 2340);
        bank(200, 200);
        block(200, 160, 500, 330);
        containers(W - 200, 1000, W - 150, 3300);
        block(W - 220, 900, W, 3400);
        city(new int[][] {{0, 0, W, H}});
        alleyDetails(1200, 1100, 1700);
        paintRoad(rgb(0x4d4f57), rgb(0x45474e), rgb(0xf6d24a), true, null);
        warehouse(3450, 2600);
        startLine();
        finishLine();
    }

    /** marks a world rectangle as taken, so nothing else is painted there */
    static void block(int x0, int y0, int x1, int y1) {
        for (int ay = (H - y1) / S; ay <= (H - y0) / S; ay++) {
            for (int ax = x0 / S; ax <= x1 / S; ax++) {
                if (ax >= 0 && ay >= 0 && ax < AW && ay < AH) {
                    blocked[ay * AW + ax] = true;
                }
            }
        }
    }

    /** blocks of towers with flat roofs, rooftop units and lit windows along the front */
    static void city(int[][] blocks) {
        int[] roofs = {rgb(0x9aa5b1), rgb(0x8796a6), rgb(0xb3b8bd), rgb(0x7f8a99), rgb(0xa9b4c4), rgb(0xa58f7c)};
        for (int[] b : blocks) {
            for (int y = b[1]; y < b[3] - 60; y += 96) {
                int x = b[0];
                while (x < b[2] - 60) {
                    int w = 90 + 30 * rnd.nextInt(4), h = 66 + 15 * rnd.nextInt(3);
                    if (fitsBox(x, y, Math.min(w, b[2] - x), h)) {
                        tower(x / S, (H - y - h) / S, Math.min(w, b[2] - x) / S, h / S, roofs[rnd.nextInt(roofs.length)]);
                    }
                    x += w + 12;
                }
            }
        }
    }

    static boolean fitsBox(int x, int y, int w, int h) {
        for (int yy = y; yy <= y + h; yy += 6) {
            for (int xx = x; xx <= x + w; xx += 6) {
                if (!clear(xx, yy, 2) || !freeAt(xx / S, (H - yy) / S)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** a building seen from above, a strip of its front showing at the bottom */
    static void tower(int ax, int ay, int w, int h, int roof) {
        if (w < 5 || h < 5) {
            return;
        }
        for (int y = 1; y <= h + 1; y++) {
            shade(ax + w + 1, ay + y, 0.65);
        }
        for (int x = 1; x <= w + 1; x++) {
            shade(ax + x, ay + h + 1, 0.65);
        }
        int front = Math.max(3, h / 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c;
                if (y >= h - front) {
                    // the front wall: rows of windows, some lit
                    c = (x % 3 == 0 || (y - (h - front)) % 2 == 0) ? blend(roof, OUTLINE, 0.45)
                        : (rnd.nextInt(6) == 0 ? LIT : GLASS_DARK);
                } else {
                    c = y == 0 || x == 0 ? blend(roof, WHITE, 0.25) : ((x + y) % 9 == 0 ? blend(roof, OUTLINE, 0.12) : roof);
                }
                set(ax + x, ay + y, c);
            }
        }
        // rooftop units
        for (int i = 0; i < 2 + w / 12; i++) {
            int ux = ax + 2 + rnd.nextInt(Math.max(1, w - 6)), uy = ay + 2 + rnd.nextInt(Math.max(1, h - front - 5));
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 3; dx++) {
                    set(ux + dx, uy + dy, dy == 0 ? rgb(0xd5d8dc) : rgb(0x8d9298));
                }
            }
        }
        outline(ax, ay, w, h);
    }

    /** the multi-storey car park: concrete deck, painted bays, pillars and ramp arrows */
    static void garage(int x0, int y0, int x1, int y1) {
        for (int ay = (H - y1) / S; ay <= (H - y0) / S; ay++) {
            for (int ax = x0 / S; ax <= x1 / S; ax++) {
                float x = wx(ax), y = wy(ay);
                boolean edgePx = ax == x0 / S || ax == x1 / S || ay == (H - y1) / S || ay == (H - y0) / S;
                int c = edgePx ? OUTLINE : ((ax + ay) % 13 == 0) ? rgb(0x9a9a9a) : rgb(0xa8a8a6);
                if (!edgePx && edge(x, y) > 0) {
                    // parking bays with white lines, a pillar every few bays
                    int bx = (ax - x0 / S) % 10, by = (ay - (H - y1) / S) % 22;
                    if (bx == 0 && by < 14) c = WHITE;
                    if (bx == 0 && by == 0) c = rgb(0x6f6f6f);
                    if ((ax - x0 / S) % 30 == 15 && (ay - (H - y1) / S) % 22 == 18) c = rgb(0x5a5a5a);
                }
                set(ax, ay, c);
            }
        }
        // parked cars in some bays
        int[] paint = {RED, rgb(0x3b6fd9), WHITE, rgb(0x2a2a30), YELLOW, rgb(0x3fae49)};
        for (int i = 0; i < 70; i++) {
            int ax = x0 / S + 3 + rnd.nextInt((x1 - x0) / S - 6), ay = (H - y1) / S + 2 + rnd.nextInt((y1 - y0) / S - 6);
            if (edge(wx(ax), wy(ay)) > 10 && edge(wx(ax), wy(ay + 4)) > 10) {
                miniCar(ax, ay, paint[rnd.nextInt(paint.length)]);
            }
        }
    }

    static void miniCar(int ax, int ay, int color) {
        for (int dy = 0; dy < 5; dy++) {
            for (int dx = 0; dx < 3; dx++) {
                set(ax + dx, ay + dy, dy == 1 ? GLASS_DARK : color);
            }
        }
        outline(ax, ay, 3, 5);
    }

    /** brick walls, dumpsters and fire escapes down the alley */
    static void alleyDetails(int x, int y0, int y1) {
        for (int y = y0 + 80; y < y1 - 60; y += 70) {
            for (int side : new int[] {-1, 1}) {
                float px = x + side * (42 + 26);
                if (edge(px, y) > 0) {
                    int ax = (int) (px / S), ay = (H - y) / S;
                    for (int dy = 0; dy < 4; dy++) {
                        for (int dx = 0; dx < 6; dx++) {
                            set(ax + dx - 3, ay + dy, dy == 0 ? rgb(0x2f6f42) : rgb(0x3f8a54));   // a dumpster
                        }
                    }
                    outline(ax - 3, ay, 6, 4);
                }
            }
        }
    }

    /** the bank at the start: steps, columns and a gold roof line */
    static void bank(int x, int y) {
        int ax = x / S + 22, ay = (H - y) / S - 34;
        for (int dy = 0; dy < 30; dy++) {
            for (int dx = 0; dx < 60; dx++) {
                int c;
                if (dy < 6) {
                    c = dy == 0 ? rgb(0xd8b84a) : rgb(0xe8e2d0);                 // the pediment
                } else if (dy > 25) {
                    c = (dy % 2 == 0) ? rgb(0xc9c3b6) : rgb(0xddd7c9);            // the steps
                } else {
                    c = (dx % 8 < 3) ? WHITE : rgb(0x8f8a80);                     // columns
                }
                if (freeAt(ax + dx, ay + dy)) {
                    set(ax + dx, ay + dy, c);
                }
            }
        }
        outline(ax, ay, 60, 30);
        // the getaway bags of cash by the curb
        set(ax - 3, ay + 27, rgb(0x6fae4a));
        set(ax - 3, ay + 28, rgb(0x4f8a34));
    }

    /** stacks of shipping containers on the quay */
    static void containers(int x0, int y0, int x1, int y1) {
        int[] colors = {RED, rgb(0x3b6fd9), rgb(0xe08a2e), rgb(0x2f8f6a), rgb(0x8a4ae0)};
        for (int y = y0; y < y1; y += 36) {
            int ax = x0 / S, ay = (H - y) / S;
            int c = colors[rnd.nextInt(colors.length)];
            boolean ok = true;
            for (int dy = 0; dy < 10 && ok; dy++) {
                ok = freeAt(ax, ay + dy) && edge(wx(ax), wy(ay + dy)) > WALK;
            }
            if (!ok) {
                continue;
            }
            for (int dy = 0; dy < 10; dy++) {
                for (int dx = 0; dx < 14; dx++) {
                    set(ax + dx, ay + dy, (dx % 2 == 0) ? blend(c, OUTLINE, 0.2) : c);
                }
            }
            outline(ax, ay, 14, 10);
        }
    }

    /** the warehouse at the end: a big corrugated roof with its doors open */
    static void warehouse(int x, int y) {
        int ax = x / S - 40, ay = (H - y) / S - 10;
        for (int dy = 0; dy < 40; dy++) {
            for (int dx = 0; dx < 80; dx++) {
                if (edge(wx(ax + dx), wy(ay + dy)) <= WALK + 2) {
                    continue;
                }
                int c = (dx % 3 == 0) ? rgb(0x7f8a8f) : rgb(0x9aa5aa);
                if (dy == 0) c = rgb(0xc9d0d4);
                set(ax + dx, ay + dy, c);
            }
        }
    }

    // ================================================================== ROOFTOP RUN

    static void rooftop() {
        begin(ROOF, 7200, 1800, 1990, 62, 62);
        for (int b : ROOF_BRIDGES) {
            half[b] = 46;     // the sky bridges are narrow
        }
        // far below: streets with tiny traffic
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                int c = ((ax / 4 + ay / 4) % 2 == 0) ? rgb(0x2c2e35) : rgb(0x30323a);
                if (ax % 60 < 2 || ay % 50 < 2) c = rgb(0x5a5a5e);
                set(ax, ay, c);
            }
        }
        for (int i = 0; i < 700; i++) {
            set(rnd.nextInt(AW), rnd.nextInt(AH), rnd.nextBoolean() ? LIT : RED);
        }
        // other buildings all around, clear of the course's own rooftops
        for (int x = 40; x < W - 300; x += 640) {
            for (int y = 30; y < H - 260; y += 470) {
                int x1 = x + 520 + rnd.nextInt(60), y1 = y + 360 + rnd.nextInt(60);
                if (!overlapsCourseRoof(x - 60, y - 60, x1 + 60, y1 + 60)) {
                    roof(x, y, Math.min(x1, W - 20), Math.min(y1, H - 20), rgb(new int[] {0x7f8a99, 0x8a8478, 0x6f7a88}[rnd.nextInt(3)]));
                }
            }
        }
        for (int[] r : ROOF_TOPS) {
            roof(r[0], r[1], r[2], r[3], rgb(r[4]));
        }
        paintRoad(rgb(0x5a5c62), rgb(0x52545a), WHITE, false, null);
        bridges();
        helipad(7000, 1260);
        helipad(500, 330);
        helipad(3500, 1350);
        billboards();
        startLine();
        finishLine();
    }

    static boolean overlapsCourseRoof(int x0, int y0, int x1, int y1) {
        for (int[] r : ROOF_TOPS) {
            if (x0 < r[2] && x1 > r[0] && y0 < r[3] && y1 > r[1]) {
                return true;
            }
        }
        return false;
    }

    /** a rooftop: gravel with a parapet, water tanks, vents and skylights */
    static void roof(int x0, int y0, int x1, int y1, int color) {
        int ax0 = x0 / S, ax1 = x1 / S, ay0 = (H - y1) / S, ay1 = (H - y0) / S;
        for (int ay = ay0; ay <= ay1 + 4; ay++) {
            for (int ax = ax0; ax <= ax1 + 3; ax++) {
                if (ay > ay1 || ax > ax1) {
                    shade(ax, ay, 0.55);      // the building's shadow on the street below
                }
            }
        }
        for (int ay = ay0; ay <= ay1; ay++) {
            for (int ax = ax0; ax <= ax1; ax++) {
                boolean parapet = ax - ax0 < 2 || ax1 - ax < 2 || ay - ay0 < 2 || ay1 - ay < 2;
                int c = parapet ? (ax - ax0 < 1 || ay - ay0 < 1 ? blend(color, WHITE, 0.3) : blend(color, OUTLINE, 0.35))
                    : ((ax * 7 + ay * 13) % 17 == 0 ? blend(color, OUTLINE, 0.15) : color);
                set(ax, ay, c);
            }
        }
        outline(ax0, ay0, ax1 - ax0 + 1, ay1 - ay0 + 1);
        for (int i = 0; i < (ax1 - ax0) * (ay1 - ay0) / 900; i++) {
            int ux = ax0 + 4 + rnd.nextInt(Math.max(1, ax1 - ax0 - 10)), uy = ay0 + 4 + rnd.nextInt(Math.max(1, ay1 - ay0 - 10));
            if (edge(wx(ux), wy(uy)) < WALK + 10 || edge(wx(ux + 6), wy(uy + 6)) < WALK + 10) {
                continue;
            }
            switch (rnd.nextInt(3)) {
                case 0:   // water tank
                    disc(ux + 3, uy + 3, 3, rgb(0x8a5a36));
                    circleOutline(ux + 3, uy + 3, 3, OUTLINE);
                    set(ux + 2, uy + 2, rgb(0xa8784e));
                    break;
                case 1:   // skylight
                    for (int dy = 0; dy < 4; dy++) {
                        for (int dx = 0; dx < 6; dx++) {
                            set(ux + dx, uy + dy, dy == 0 ? rgb(0xc8ecff) : GLASS);
                        }
                    }
                    outline(ux, uy, 6, 4);
                    break;
                default:  // air-con units
                    for (int dy = 0; dy < 3; dy++) {
                        for (int dx = 0; dx < 4; dx++) {
                            set(ux + dx, uy + dy, dy == 0 ? rgb(0xe3e5e9) : rgb(0xa8acb2));
                        }
                    }
                    set(ux + 1, uy + 1, OUTLINE);
                    outline(ux, uy, 4, 3);
            }
        }
    }

    /** the sky bridges and ramps between buildings: steel railings, glass sides, arrows */
    static void bridges() {
        int[] bridgeStretches = ROOF_BRIDGES;
        for (int s : bridgeStretches) {
            float ax = pts[s][0], ay = pts[s][1], bx = pts[s + 1][0], by = pts[s + 1][1];
            float len = (float) Math.hypot(bx - ax, by - ay), dx = (bx - ax) / len, dy = (by - ay) / len;
            for (float t = 0; t < len; t += 1.5f) {
                for (int side : new int[] {-1, 1}) {
                    for (float w = 0; w < 7; w += 1.5f) {
                        float x = ax + dx * t - dy * side * (half[s] + 2 + w), y = ay + dy * t + dx * side * (half[s] + 2 + w);
                        int c = w < 3 ? rgb(0xc8ccd4) : rgb(0x7fb2e0);
                        set((int) (x / S), (int) ((H - y) / S), c);
                    }
                }
            }
            // chevrons pointing the way
            for (float t = 40; t < len - 30; t += 70) {
                float cx = ax + dx * t, cy = ay + dy * t;
                for (int k = -10; k <= 10; k++) {
                    float px = cx - dy * k - dx * Math.abs(k) * 0.8f, py = cy + dx * k - dy * Math.abs(k) * 0.8f;
                    set((int) (px / S), (int) ((H - py) / S), YELLOW);
                }
            }
        }
    }

    static void helipad(int x, int y) {
        int ax = x / S, ay = (H - y) / S;
        for (int dy = -12; dy <= 12; dy++) {
            for (int dx = -12; dx <= 12; dx++) {
                double d = Math.hypot(dx, dy);
                if (d <= 12) {
                    int c = d > 11 ? YELLOW : d > 9.5 ? rgb(0x3a3c42) : rgb(0x4a4c52);
                    if (edge(wx(ax + dx), wy(ay + dy)) < 0) {
                        c = d > 11 ? YELLOW : blend(c, rgb(0x5a5c62), 0.5);
                    }
                    set(ax + dx, ay + dy, c);
                }
            }
        }
        // the H
        for (int k = -5; k <= 5; k++) {
            set(ax - 4, ay + k, WHITE);
            set(ax + 4, ay + k, WHITE);
        }
        for (int k = -4; k <= 4; k++) {
            set(ax + k, ay, WHITE);
        }
    }

    /** neon billboards standing on the roof edges */
    static void billboards() {
        int[] neon = {rgb(0xff4fa3), rgb(0x3fe0ff), rgb(0xffe23f), rgb(0x8cff5a), rgb(0xff7a3f)};
        // along the course's rooftops, near their edges
        int[][] spots = new int[ROOF_TOPS.length * 2][];
        for (int i = 0; i < ROOF_TOPS.length; i++) {
            int[] r = ROOF_TOPS[i];
            spots[i * 2] = new int[] {r[0] + 60, r[3] - 40};
            spots[i * 2 + 1] = new int[] {r[2] - 140, r[1] + 70};
        }
        for (int[] p : spots) {
            int ax = p[0] / S, ay = (H - p[1]) / S;
            if (edge(p[0], p[1]) < WALK + 10) {
                continue;
            }
            for (int dy = 0; dy < 7; dy++) {
                for (int dx = 0; dx < 22; dx++) {
                    int c = dy == 0 || dy == 6 || dx == 0 || dx == 21 ? OUTLINE : neon[(dx / 4 + dy) % neon.length];
                    set(ax + dx, ay + dy, c);
                }
            }
            set(ax + 4, ay + 7, OUTLINE);
            set(ax + 17, ay + 7, OUTLINE);
        }
    }

    // ================================================================== MOON BASE

    static void moon() {
        begin(MOON, 5400, 3240, 1969, 64, 64);
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                float n = noise(ax, ay);
                int c = n > 0.65f ? rgb(0xa7a7aa) : n > 0.35f ? rgb(0x97979b) : rgb(0x8a8a8f);
                if ((ax * 7 + ay * 3) % 31 == 0) c = rgb(0x7a7a80);
                set(ax, ay, c);
            }
        }
        // craters of every size
        for (int i = 0; i < 260; i++) {
            float x = rnd.nextInt(W), y = rnd.nextInt(H);
            int r = 4 + (int) (Math.pow(rnd.nextFloat(), 3) * 46);
            if (clear(x, y, r * S + 4)) {
                crater((int) (x / S), (int) ((H - y) / S), r);
            }
        }
        // the base: domes, solar panels, a flag, parked rovers
        // domes in clusters (the base), solar farms beside them
        for (int i = 0, placed = 0; i < 400 && placed < 16; i++) {
            float x = 300 + rnd.nextInt(W - 600), y = 300 + rnd.nextInt(H - 600);
            int r = 9 + rnd.nextInt(10);
            if (clear(x, y, r * S + 10)) {
                dome((int) (x / S), (int) ((H - y) / S), r);
                for (int k = 0; k < 5; k++) {
                    float sx = x + 90 + rnd.nextInt(120), sy = y - 60 + rnd.nextInt(120);
                    if (clear(sx, sy, 20)) {
                        solar((int) (sx / S), (int) ((H - sy) / S));
                    }
                }
                placed++;
            }
        }
        flag(500, 1350);
        for (int i = 0; i < 14; i++) {
            float x = 500 + rnd.nextInt(W - 1000), y = 200 + rnd.nextInt(H - 400);
            if (clear(x, y, 20)) {
                parkedRover((int) (x / S), (int) ((H - y) / S));
            }
        }
        earth(5120, 3000);
        paintRoad(rgb(0xb9b9bd), rgb(0xb0b0b4), rgb(0x8fdcff), false, null);
        tireTracks();
        launchPad(4810, 2840);
        startLine();
        finishLine();
    }

    static void crater(int ax, int ay, int r) {
        for (int dy = -r - 1; dy <= r + 1; dy++) {
            for (int dx = -r - 1; dx <= r + 1; dx++) {
                double d = Math.hypot(dx, dy) / r;
                if (d > 1.15) continue;
                int base = get(ax + dx, ay + dy);
                int c;
                if (d > 0.92) {
                    // the raised rim: lit on its outer top-left, shadowed bottom-right
                    c = blend(base, dx + dy < 0 ? WHITE : OUTLINE, 0.22);
                } else {
                    // the bowl: the inside wall facing the sun (bottom right) is lit, the wall
                    // facing away is in shadow; the flat floor in the middle is plain
                    double wall = Math.max(0, (d - 0.45) / 0.47);
                    double facing = (dx + dy) / (Math.hypot(dx, dy) + 1e-6) / Math.sqrt(2);
                    int floor = blend(base, OUTLINE, 0.08);
                    c = facing < 0 ? blend(floor, OUTLINE, 0.35 * wall * -facing) : blend(floor, WHITE, 0.3 * wall * facing);
                }
                set(ax + dx, ay + dy, c);
            }
        }
    }

    static void dome(int ax, int ay, int r) {
        disc(ax + 2, ay + 2, r, rgb(0x55555b));
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                double d = Math.hypot(dx, dy);
                if (d <= r) {
                    int c = d > r - 1 ? OUTLINE : (dx + dy < -r / 2) ? rgb(0xdff3ff) : ((dx * 3 + dy * 5) % 7 == 0) ? rgb(0x7fb2d0) : GLASS;
                    set(ax + dx, ay + dy, c);
                }
            }
        }
        // the frame and a glow from inside
        for (int k = -r + 1; k < r; k++) {
            set(ax + k, ay, rgb(0xc8ccd4));
            set(ax, ay + k, rgb(0xc8ccd4));
        }
        set(ax + 2, ay + 2, LIT);
    }

    static void solar(int ax, int ay) {
        for (int dy = 0; dy < 4; dy++) {
            for (int dx = 0; dx < 9; dx++) {
                set(ax + dx, ay + dy, (dx % 3 == 2) ? rgb(0x8a8e98) : rgb(0x2f4fa8));
            }
        }
        outline(ax, ay, 9, 4);
    }

    static void flag(int x, int y) {
        int ax = x / S, ay = (H - y) / S;
        for (int k = 0; k < 12; k++) {
            set(ax, ay - k, rgb(0xd8d8d8));
        }
        int[] stripes = {RED, WHITE};
        for (int dy = 0; dy < 6; dy++) {
            for (int dx = 1; dx < 10; dx++) {
                set(ax + dx, ay - 11 + dy, dx < 4 && dy < 3 ? rgb(0x2f44d8) : stripes[dy % 2]);
            }
        }
    }

    static void parkedRover(int ax, int ay) {
        for (int dy = 0; dy < 6; dy++) {
            for (int dx = 0; dx < 8; dx++) {
                int c = (dx == 0 || dx == 7) && (dy == 0 || dy == 5) ? BLACK : dy == 2 ? GLASS_DARK : WHITE;
                set(ax + dx, ay + dy, c);
            }
        }
        outline(ax, ay, 8, 6);
    }

    /** the Earth peeking over the horizon in the corner */
    static void earth(int x, int y) {
        int ax = x / S, ay = (H - y) / S, r = 26;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                double d = Math.hypot(dx, dy);
                if (d <= r && freeAt(ax + dx, ay + dy)) {
                    double n = Math.sin(dx / 4.0) * Math.cos(dy / 5.0) + Math.sin((dx + dy) / 7.0);
                    int c = d > r - 1 ? rgb(0x9fd8ff) : n > 0.6 ? rgb(0x4f9a4a) : (n < -1.2 ? WHITE : rgb(0x2f6fc8));
                    if (dx - dy > r * 0.6) c = blend(c, BLACK, 0.55);     // night side
                    set(ax + dx, ay + dy, c);
                }
            }
        }
    }

    /** tyre tracks along the lunar road */
    static void tireTracks() {
        for (int i = 2; i + 1 < pts.length - 1; i++) {
            float ax = pts[i][0], ay = pts[i][1], bx = pts[i + 1][0], by = pts[i + 1][1];
            float len = (float) Math.hypot(bx - ax, by - ay), dx = (bx - ax) / len, dy = (by - ay) / len;
            for (float t = 0; t < len; t += 3) {
                for (int k : new int[] {-24, -16, 16, 24}) {
                    float x = ax + dx * t - dy * k, y = ay + dy * t + dx * k;
                    if (((int) t / 6) % 2 == 0) {
                        int px = (int) (x / S), py = (int) ((H - y) / S);
                        set(px, py, blend(get(px, py), OUTLINE, 0.18));
                    }
                }
            }
        }
    }

    /** the rocket waiting on its launch pad past the finish */
    static void launchPad(int x, int y) {
        int ax = x / S + 30, ay = (H - y) / S;
        for (int dy = -20; dy <= 20; dy++) {
            for (int dx = -20; dx <= 20; dx++) {
                double d = Math.hypot(dx, dy);
                if (d <= 20 && edge(wx(ax + dx), wy(ay + dy)) > WALK) {
                    int c = d > 19 ? YELLOW : ((int) d % 6 == 0) ? rgb(0x5a5a5e) : rgb(0x6f6f74);
                    set(ax + dx, ay + dy, c);
                }
            }
        }
        // the rocket seen from above: a white body, red nose, four fins and a shadow
        disc(ax + 5, ay + 5, 8, rgb(0x55555b));
        disc(ax, ay, 8, OUTLINE);
        disc(ax, ay, 7, WHITE);
        disc(ax - 2, ay - 2, 3, rgb(0xffffff));
        disc(ax, ay, 3, RED);
        for (int k = 8; k < 13; k++) {
            set(ax + k, ay, RED);
            set(ax - k, ay, RED);
            set(ax, ay + k, RED);
            set(ax, ay - k, RED);
        }
    }

    // ================================================================== the road itself

    /**
     * asphalt (or packed dust), a curb, the drivable verge, a fence along its outer edge,
     * and a dashed center line on every stretch
     * @param mountain non-null for white guard-rail posts instead of yellow bollards
     */
    static void paintRoad(int asphalt, int speck, int lane, boolean doubleLine, MountainSide mountain) {
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                float x = wx(ax), y = wy(ay);
                float e = edge(x, y);
                if (e <= 0) {
                    int c = e > -S ? rgb(0x7c786f) : ((ax * 5 + ay * 3) % 17 == 0 ? speck : asphalt);
                    if (e > -3 * S && e <= -2 * S) c = rgb(0xd8d4ca);    // edge line
                    set(ax, ay, c);
                } else if (e <= WALK) {
                    int base = get(ax, ay);
                    set(ax, ay, blend(base, (ax % 4 == 0 || ay % 4 == 0) ? rgb(0xb8b0a2) : rgb(0xcbc3b5), 0.75));
                } else if (e <= WALK + S) {
                    int c = mountain != null ? (((ax + ay) % 5 == 0) ? RED : WHITE) : (((ax + ay) % 4 == 0) ? YELLOW : rgb(0x55514b));
                    set(ax, ay, c);
                }
            }
        }
        // dashed center line along each stretch (not the grid stub or the run-off)
        for (int i = 1; i + 1 < pts.length - 1; i++) {
            float ax = pts[i][0], ay = pts[i][1], bx = pts[i + 1][0], by = pts[i + 1][1];
            float len = (float) Math.hypot(bx - ax, by - ay), dx = (bx - ax) / len, dy = (by - ay) / len;
            for (float t = 50; t < len - 50; t += 1.5f) {
                if (((int) (t / 20)) % 2 != 0) continue;
                for (int k : doubleLine ? new int[] {-3, 3} : new int[] {0}) {
                    float x = ax + dx * t - dy * k, y = ay + dy * t + dx * k;
                    set((int) (x / S), (int) ((H - y) / S), lane);
                }
            }
        }
    }

    static void startLine() {
        checkers(pts[1][0], pts[1][1], pts[2][0] - pts[1][0], pts[2][1] - pts[1][1], half[1]);
    }

    static void finishLine() {
        int f = pts.length - 2;
        checkers(pts[f][0], pts[f][1], pts[f][0] - pts[f - 1][0], pts[f][1] - pts[f - 1][1], half[f - 1]);
    }

    /** a checkered line across the road at (x, y), facing along (dx, dy) */
    static void checkers(float x, float y, float dx, float dy, float halfWidth) {
        float len = (float) Math.hypot(dx, dy);
        dx /= len;
        dy /= len;
        for (float along = 0; along < 24; along += 1.5f) {
            for (float across = -halfWidth + 3; across < halfWidth - 3; across += 1.5f) {
                float px = x + dx * along - dy * across, py = y + dy * along + dx * across;
                int cx = (int) Math.floor((across + halfWidth) / 6), cy = (int) Math.floor(along / 6);
                set((int) (px / S), (int) ((H - py) / S), (cx + cy) % 2 == 0 ? WHITE : BLACK);
            }
        }
    }

    // ================================================================== rover sprite

    static BufferedImage rover() {
        String[] rows = {
            "..............",
            "...kkkkkkkk...",
            "..kyGGGGGGyk..",
            "..kGGGGGGGGk..",
            "wwkGbbbbbbGkww",
            "wwkGbbbbbbGkww",
            "wwkGbbbbbbGkww",
            "..kGGGGGGGGk..",
            "..kGssssssGk..",
            "..kGsGGGGsGk..",
            "..kGsGddGsGk..",
            "..kGsGddGsGk..",
            "..kGsGGGGsGk..",
            "wwkGssssssGkww",
            "wwkGGGGGGGGkww",
            "wwkGGGGGGGGkww",
            "..kGppppppGk..",
            "..kGppppppGk..",
            "..kGppppppGk..",
            "..kGGGGGGGGk..",
            "wwkGGGGGGGGkww",
            "wwkGGGGGGGGkww",
            "wwkGGGGGGGGkww",
            "..kGGGGGGGGk..",
            "..krGGGGGGrk..",
            "...kkkkkkkk...",
            "..............",
            "..............",
        };
        // k outline, G white body, b visor, s silver, d antenna dish, p gold panel, w wheels, y light, r tail
        String letters = "kGbsdpwyr";
        int[] colors = {rgb(0x1c1c22), rgb(0xeef0f3), rgb(0x2a3a5a), rgb(0xa8acb4), rgb(0x5a5e66), rgb(0xd8b84a),
            rgb(0x2a2a2e), rgb(0xbfe6ff), rgb(0xd23a3a)};
        BufferedImage img = new BufferedImage(14, 28, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 28; y++) {
            for (int x = 0; x < 14; x++) {
                int i = letters.indexOf(rows[y].charAt(x));
                img.setRGB(x, y, i < 0 ? 0 : colors[i]);
            }
        }
        return img;
    }

    /** prints the course as a Waypoints points(...) call (start line onwards), to keep them in step */
    static void printWaypoints(float[][] course) {
        StringBuilder sb = new StringBuilder("    points(");
        for (int i = 1; i < course.length; i++) {
            sb.append(i > 1 ? ", " : "").append((int) course[i][0]).append(", ").append((int) course[i][1]);
        }
        System.out.println(sb.append(");"));
    }

    // ================================================================== saving

    /** the picture and the road mask are saved at art resolution (the game scales them up 3x) */
    static void save(String folder) throws Exception {
        new File("assets/maps/" + folder).mkdirs();
        BufferedImage map = new BufferedImage(AW, AH, BufferedImage.TYPE_INT_ARGB);
        BufferedImage mask = new BufferedImage(AW, AH, BufferedImage.TYPE_INT_RGB);
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                map.setRGB(ax, ay, art[ay * AW + ax]);
                mask.setRGB(ax, ay, edge(wx(ax), wy(ay)) <= 0 ? 0xffffff : 0x000000);
            }
        }
        ImageIO.write(map, "png", new File("assets/maps/" + folder + "/background.png"));
        ImageIO.write(mask, "png", new File("assets/maps/" + folder + "/road_mask.png"));
        ImageIO.write(preview(map, 384, 216), "png", new File("assets/maps/" + folder + "/preview.png"));
        System.out.println(folder + " " + W + "x" + H);
    }

    /** the whole map shrunk to fit the picker's card, on a dark background */
    static BufferedImage preview(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, 0x1c1714);
            }
        }
        double scale = Math.min(w / (double) src.getWidth(), h / (double) src.getHeight());
        int pw = (int) (src.getWidth() * scale), ph = (int) (src.getHeight() * scale);
        int ox = (w - pw) / 2, oy = (h - ph) / 2;
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                int sx0 = (int) (x / scale), sy0 = (int) (y / scale), sx1 = (int) ((x + 1) / scale), sy1 = (int) ((y + 1) / scale);
                long r = 0, g = 0, b = 0, n = 0;
                for (int sy = sy0; sy < Math.min(sy1, src.getHeight()); sy += 2) {
                    for (int sx = sx0; sx < Math.min(sx1, src.getWidth()); sx += 2) {
                        int c = src.getRGB(sx, sy);
                        r += (c >> 16) & 255;
                        g += (c >> 8) & 255;
                        b += c & 255;
                        n++;
                    }
                }
                if (n > 0) {
                    out.setRGB(ox + x, oy + y, (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n));
                }
            }
        }
        return out;
    }

    // ================================================================== helpers

    /** smooth value noise in 0..1, for patchy ground */
    static float noise(int ax, int ay) {
        double v = Math.sin(ax * 0.07) * Math.cos(ay * 0.05) + Math.sin((ax + ay) * 0.031) * 0.7
            + Math.sin(ax * 0.19 + ay * 0.13) * 0.3;
        return (float) ((v + 2) / 4);
    }

    static boolean freeAt(int ax, int ay) {
        return ax >= 0 && ay >= 0 && ax < AW && ay < AH && !blocked[ay * AW + ax] && edge(wx(ax), wy(ay)) > WALK + 3;
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
        if (ax < 0 || ay < 0 || ax >= AW || ay >= AH) {
            return 0xff000000;
        }
        return art[ay * AW + ax];
    }

    static void shade(int ax, int ay, double f) {
        if (ax >= 0 && ay >= 0 && ax < AW && ay < AH) {
            set(ax, ay, blend(get(ax, ay), rgb(0x000000), 1 - f));
        }
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

    static void circleOutline(int cx, int cy, int r, int c) {
        for (int a = 0; a < 360; a += 4) {
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
