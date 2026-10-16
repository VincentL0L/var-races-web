import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Paints the Grand Prix circuit: a proper curvy racing circuit (not city blocks) with
 * sweeping corners, esses, a chicane, a long back straight and a tight final hairpin.
 * Red and white kerbs on every corner, gravel traps and tyre walls on the outside,
 * grandstands and pit garages along the main straight, a lake and trees in the infield.
 * Also paints the F1 car the CPUs drive (assets/ui/f1.png).
 *
 * Run from the project folder:  java tools/MakeGrandPrix.java
 *   -> assets/maps/gp/{background,road_mask,preview}.png and prints the waypoints for
 *      Waypoints.getGrandPrixWaypoints()
 *
 * Like every circuit it starts at (200, 300) heading up, with the grid on the straight
 * below. Painted at 1/3 size and scaled up 3x; coordinates are world (y up).
 */
public class MakeGrandPrix {
    static final int S = 3, W = 3600, H = 2304, AW = W / S, AH = H / S;
    /** half the track's width, and where the kerbs, gravel and tyre walls end */
    static final float HALF = 64, KERB = 74, GRAVEL = 150, TYRES = 158;
    static final int OUTLINE = rgb(0x221a17), WHITE = rgb(0xf6f3ea), RED = rgb(0xd9433b);

    /** the circuit's control points, in the order you drive them (a closed loop) */
    static final float[][] CONTROL = {
        {200, 150}, {200, 700}, {200, 1300}, {240, 1750}, {420, 2030}, {760, 2160}, {1100, 2080},
        {1300, 1820}, {1550, 1660}, {1850, 1760}, {2150, 1990}, {2550, 2120}, {2950, 2080},
        {3280, 1880}, {3420, 1550}, {3330, 1230}, {3050, 1080}, {2750, 1150}, {2480, 1300},
        {2220, 1200}, {2080, 950}, {2180, 680}, {2480, 560}, {2900, 560}, {3250, 520},
        {3420, 360}, {3330, 180}, {2950, 90}, {2200, 82}, {1300, 82}, {600, 82}, {330, 88},
    };

    static int[] art = new int[AW * AH];
    static Random rnd = new Random(11);
    /** the centre line, densely sampled: x, y, and the turn's curvature (+ left / - right) */
    static float[][] line;
    static float[] nearest = new float[3];

    public static void main(String[] args) throws Exception {
        line = spline(CONTROL, 6f);
        curvature();
        paint();
        save();
        ImageIO.write(f1Car(), "png", new File("assets/ui/f1.png"));
        printWaypoints();
    }

    // ---------------------------------------------------------------- the line

    /** a closed Catmull-Rom spline through the points, sampled every `step` world units */
    static float[][] spline(float[][] p, float step) {
        List<float[]> out = new ArrayList<>();
        int n = p.length;
        for (int i = 0; i < n; i++) {
            float[] p0 = p[(i - 1 + n) % n], p1 = p[i], p2 = p[(i + 1) % n], p3 = p[(i + 2) % n];
            float len = (float) Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
            int k = Math.max(2, (int) (len / step));
            for (int j = 0; j < k; j++) {
                float t = j / (float) k, t2 = t * t, t3 = t2 * t;
                float x = 0.5f * (2 * p1[0] + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2
                    + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3);
                float y = 0.5f * (2 * p1[1] + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2
                    + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3);
                out.add(new float[] {x, y, 0f});
            }
        }
        return out.toArray(new float[0][]);
    }

    /** how sharply the line turns at each sample (smoothed), + for left */
    static void curvature() {
        int n = line.length, span = 6;
        float[] raw = new float[n];
        for (int i = 0; i < n; i++) {
            float[] a = line[(i - span + n) % n], b = line[i], c = line[(i + span) % n];
            float h1 = (float) Math.atan2(b[1] - a[1], b[0] - a[0]), h2 = (float) Math.atan2(c[1] - b[1], c[0] - b[0]);
            float d = h2 - h1;
            while (d > Math.PI) {
                d -= 2 * Math.PI;
            }
            while (d < -Math.PI) {
                d += 2 * Math.PI;
            }
            float len = (float) (Math.hypot(b[0] - a[0], b[1] - a[1]) + Math.hypot(c[0] - b[0], c[1] - b[1]));
            raw[i] = d / Math.max(1f, len);
        }
        for (int i = 0; i < n; i++) {
            float s = 0;
            for (int k = -8; k <= 8; k++) {
                s += raw[(i + k + n) % n];
            }
            line[i][2] = s / 17f;
        }
    }

    /**
     * distance from (x, y) to the line; nearest = {sample index, which side (+1 left of
     * travel, -1 right), distance along the line}
     */
    /** line segments bucketed on a coarse grid, so each lookup only checks nearby ones */
    static final int BUCKET = 100;
    static List<List<Integer>> buckets;
    static int bw, bh;

    static void bucket() {
        bw = W / BUCKET + 1;
        bh = H / BUCKET + 1;
        buckets = new ArrayList<>();
        for (int i = 0; i < bw * bh; i++) {
            buckets.add(new ArrayList<>());
        }
        int n = line.length;
        for (int i = 0; i < n; i++) {
            float[] a = line[i];
            int cx = (int) (a[0] / BUCKET), cy = (int) (a[1] / BUCKET);
            if (cx >= 0 && cy >= 0 && cx < bw && cy < bh) {
                buckets.get(cy * bw + cx).add(i);
            }
        }
    }

    static float distance(float x, float y) {
        if (buckets == null) {
            bucket();
        }
        float best = Float.MAX_VALUE;
        int bi = 0;
        float side = 1;
        int n = line.length;
        int cx = (int) (x / BUCKET), cy = (int) (y / BUCKET);
        List<Integer> near = new ArrayList<>();
        for (int gy = cy - 2; gy <= cy + 2; gy++) {
            for (int gx = cx - 2; gx <= cx + 2; gx++) {
                if (gx >= 0 && gy >= 0 && gx < bw && gy < bh) {
                    near.addAll(buckets.get(gy * bw + gx));
                }
            }
        }
        if (near.isEmpty()) {
            nearest[0] = 0;
            nearest[1] = 1;
            nearest[2] = 0;
            return 9999f;
        }
        for (int i : near) {
            float[] a = line[i], b = line[(i + 1) % n];
            float dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy;
            float t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / l2));
            float px = a[0] + dx * t - x, py = a[1] + dy * t - y, d = px * px + py * py;
            if (d < best) {
                best = d;
                bi = i;
                side = Math.signum(dx * (y - a[1]) - dy * (x - a[0]));
            }
        }
        nearest[0] = bi;
        nearest[1] = side;
        nearest[2] = bi * 6f;
        return (float) Math.sqrt(best);
    }

    // ---------------------------------------------------------------- painting

    static void paint() {
        // the grass: mown in broad stripes
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                boolean stripe = ((ax + ay) / 22) % 2 == 0;
                int c = stripe ? rgb(0x7cb444) : rgb(0x74ac3e);
                if (rnd.nextInt(30) == 0) {
                    c = rgb(0x86bc50);
                }
                art[ay * AW + ax] = c;
            }
        }
        lake(1650, 1180, 260, 150);
        // the track and everything beside it
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                float x = wx(ax), y = wy(ay);
                float d = distance(x, y);
                if (d > TYRES + 4) {
                    continue;
                }
                int i = (int) nearest[0];
                float side = nearest[1], k = line[i][2];
                boolean corner = Math.abs(k) > 0.0009f;
                // the outside of a corner is the side it turns away from
                boolean outside = corner && Math.signum(k) != side;
                int c = art[ay * AW + ax];
                if (d <= HALF) {
                    int n = rnd.nextInt(12);
                    c = n == 0 ? rgb(0x4a4a50) : n == 1 ? rgb(0x3c3c42) : rgb(0x434349);
                    if (d > HALF - 5 && d <= HALF - 2) {
                        c = WHITE;                                   // the white edge line
                    }
                } else if (d <= KERB) {
                    if (corner) {
                        int block = (int) (nearest[2] / 12f);
                        c = block % 2 == 0 ? RED : WHITE;           // kerbs
                    } else {
                        c = rgb(0x6b6b70);                           // a strip of tarmac run-off
                    }
                } else if (outside && d <= GRAVEL) {
                    c = rnd.nextInt(5) == 0 ? rgb(0xc9b48a) : rgb(0xdccaa0);   // gravel trap
                } else if (outside && d <= TYRES) {
                    // a tyre wall: stacked black tyres with a colourful belt
                    int n = ((int) nearest[2] / 6) % 4;
                    c = d < TYRES - 4 ? (n == 0 ? rgb(0x2a2a2e) : rgb(0x1b1b1f)) : (n % 2 == 0 ? RED : rgb(0x3f6fd0));
                } else if (d <= KERB + 8) {
                    c = rgb(0x6a9a3a);                               // worn grass by the track
                }
                art[ay * AW + ax] = c;
            }
        }
        startFinish();
        grandstand(330, 380, 420, 1250);       // beside the main straight
        pits(440, 450, 560, 1150);
        grandstand(1200, 150, 2600, 240);      // along the back straight
        trees();
        // a big red VAR logo painted on the infield grass by the main straight
        logo(720, 1000);
    }

    /** the checkered line at y = 300 and the white grid boxes below it */
    static void startFinish() {
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                float x = wx(ax), y = wy(ay);
                if (Math.abs(x - 200) < HALF - 4 && y >= 300 && y < 318) {
                    int cx = (int) ((x - 136) / 9), cy = (int) ((y - 300) / 9);
                    art[ay * AW + ax] = (cx + cy) % 2 == 0 ? WHITE : rgb(0x1b1b1f);
                }
            }
        }
        // grid slots: little white brackets (Waypoints.getGridSlot)
        for (int slot = 0; slot < 12; slot++) {
            int row = slot / 3, col = slot % 3;
            float gx = 165f + col * 36f, gy = 276f - row * 30f - (col == 1 ? 12f : 0f);
            for (float x = gx - 2; x <= gx + 12; x += 1) {
                set(x, gy + 22, WHITE);
            }
            for (float y = gy + 14; y <= gy + 22; y += 1) {
                set(gx - 2, y, WHITE);
                set(gx + 12, y, WHITE);
            }
        }
    }

    static void set(float x, float y, int c) {
        int ax = (int) (x / S), ay = (int) ((H - y) / S);
        if (ax >= 0 && ay >= 0 && ax < AW && ay < AH) {
            art[ay * AW + ax] = c;
        }
    }

    /** a grandstand: tiers of a colourful crowd under a white roof edge */
    static void grandstand(float x0, float y0, float x1, float y1) {
        boolean tall = (y1 - y0) > (x1 - x0);
        for (float x = x0; x < x1; x += S) {
            for (float y = y0; y < y1; y += S) {
                if (distance(x, y) < KERB + 10) {
                    continue;
                }
                float t = tall ? (x - x0) / (x1 - x0) : (y - y0) / (y1 - y0);
                int c;
                if (t > 0.86f) {
                    c = rgb(0xe8e8ec);                          // the roof
                } else if (((int) (t * 14)) % 2 == 0) {
                    int[] crowd = {rgb(0xd9433b), rgb(0xf2c84b), rgb(0x3f6fd0), rgb(0xf6f3ea), rgb(0x2a2a2e), rgb(0x6cb84b)};
                    c = crowd[rnd.nextInt(crowd.length)];
                } else {
                    c = rgb(0x8a8f99);                          // the steps
                }
                set(x, y, c);
            }
        }
    }

    /** the pit garages: a long grey building with team-coloured doors */
    static void pits(float x0, float y0, float x1, float y1) {
        int[] teams = {rgb(0xd9433b), rgb(0x3f6fd0), rgb(0x2f9f8f), rgb(0xf08a2e), rgb(0xe8e8ec), rgb(0x1b1b1f)};
        for (float x = x0; x < x1; x += S) {
            for (float y = y0; y < y1; y += S) {
                boolean edge = x < x0 + S || x >= x1 - S || y < y0 + S || y >= y1 - S;
                int c = edge ? OUTLINE : rgb(0xb7bcc6);
                if (!edge && x < x0 + 14) {
                    int bay = (int) ((y - y0) / 40);
                    c = ((int) (y - y0) % 40) < 30 ? teams[bay % teams.length] : rgb(0x8a8f99);
                }
                set(x, y, c);
            }
        }
    }

    static void lake(float cx, float cy, float rx, float ry) {
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                float x = wx(ax), y = wy(ay);
                double e = Math.pow((x - cx) / rx, 2) + Math.pow((y - cy) / ry, 2);
                if (e < 1) {
                    art[ay * AW + ax] = e > 0.9 ? rgb(0xc9b48a) : (rnd.nextInt(40) == 0 ? rgb(0x9fd3f0) : rgb(0x3f8fd0));
                }
            }
        }
    }

    /** round trees with dark leaves (so the 3D view stands them up), kept off the track */
    static void trees() {
        for (int k = 0; k < 1600; k++) {
            float x = 40 + rnd.nextFloat() * (W - 80), y = 40 + rnd.nextFloat() * (H - 80);
            float r = 9 + rnd.nextFloat() * 8;
            if (distance(x, y) < TYRES + r + 20 || (x > 300 && x < 600 && y > 350 && y < 1300)
                    || (x > 1150 && x < 2650 && y < 280) || Math.pow((x - 1650) / 300, 2) + Math.pow((y - 1180) / 190, 2) < 1
                    || (x > 600 && x < 900 && y > 900 && y < 1100)) {
                continue;
            }
            for (float dx = -r - 3; dx <= r + 3; dx += S) {
                for (float dy = -r - 3; dy <= r; dy += S) {
                    if (dx * dx + (dy + 3) * (dy + 3) < (r + 1) * (r + 1)) {
                        set(x + dx + 3, y + dy - 3, rgb(0x3f7f2c));    // shadow
                    }
                }
            }
            for (float dx = -r; dx <= r; dx += S) {
                for (float dy = -r; dy <= r; dy += S) {
                    float d2 = dx * dx + dy * dy;
                    if (d2 < r * r) {
                        boolean lit = dx < 0 && dy > 0;
                        set(x + dx, y + dy, d2 > (r - 3) * (r - 3) ? rgb(0x1f4f22) : lit ? rgb(0x3f8f3a) : rgb(0x2f6f2e));
                    }
                }
            }
        }
    }

    /** VAR in big red letters on the grass, as a TV-camera logo */
    static void logo(float x0, float y0) {
        String[] font = {
            "X...X..XXX..XXXX.",
            "X...X.X...X.X...X",
            "X...X.X...X.X...X",
            ".X.X..XXXXX.XXXX.",
            ".X.X..X...X.X.X..",
            "..X...X...X.X..X.",
            "..X...X...X.X...X",
        };
        for (int r = 0; r < font.length; r++) {
            for (int c = 0; c < font[r].length(); c++) {
                if (font[r].charAt(c) == 'X') {
                    for (int px = 0; px < 12; px += S) {
                        for (int py = 0; py < 12; py += S) {
                            set(x0 + c * 12 + px, y0 + (font.length - r) * 12 + py, RED);
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- output

    static float wx(int ax) {
        return ax * S + S / 2f;
    }

    static float wy(int ay) {
        return H - ay * S - S / 2f;
    }

    static void save() throws Exception {
        new File("assets/maps/gp").mkdirs();
        BufferedImage map = new BufferedImage(AW, AH, BufferedImage.TYPE_INT_ARGB);
        BufferedImage mask = new BufferedImage(AW, AH, BufferedImage.TYPE_INT_RGB);
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                map.setRGB(ax, ay, 0xff000000 | art[ay * AW + ax]);
                mask.setRGB(ax, ay, distance(wx(ax), wy(ay)) <= KERB ? 0xffffff : 0x000000);
            }
        }
        ImageIO.write(map, "png", new File("assets/maps/gp/background.png"));
        ImageIO.write(mask, "png", new File("assets/maps/gp/road_mask.png"));
        ImageIO.write(preview(map, 384, 216), "png", new File("assets/maps/gp/preview.png"));
        System.out.println("gp " + W + "x" + H);
    }

    static BufferedImage preview(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        double scale = Math.min(w / (double) src.getWidth(), h / (double) src.getHeight());
        int pw = (int) (src.getWidth() * scale), ph = (int) (src.getHeight() * scale);
        int ox = (w - pw) / 2, oy = (h - ph) / 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, 0x1c1714);
            }
        }
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                int sx0 = (int) (x / scale), sy0 = (int) (y / scale), sx1 = (int) ((x + 1) / scale), sy1 = (int) ((y + 1) / scale);
                long r = 0, g = 0, b = 0, n = 0;
                for (int sy = sy0; sy < Math.min(sy1, src.getHeight()); sy++) {
                    for (int sx = sx0; sx < Math.min(sx1, src.getWidth()); sx++) {
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

    /**
     * the waypoints the CPUs follow and the race is measured along: the centre line every
     * ~60 units, starting just past the line and ending on it at (200, 300)
     */
    static void printWaypoints() {
        int n = line.length, start = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float d = (float) Math.hypot(line[i][0] - 200, line[i][1] - 300);
            if (d < best) {
                best = d;
                start = i;
            }
        }
        StringBuilder sb = new StringBuilder();
        float acc = 0;
        int count = 0;
        for (int k = 1; k <= n; k++) {
            int i = (start + k) % n, p = (start + k - 1) % n;
            acc += (float) Math.hypot(line[i][0] - line[p][0], line[i][1] - line[p][1]);
            // closer together through the corners so the CPUs follow the curve
            float spacing = Math.abs(line[i][2]) > 0.0009f ? 45f : 90f;
            if (acc >= spacing && k < n - 4) {
                sb.append(Math.round(line[i][0])).append(", ").append(Math.round(line[i][1])).append(", ");
                acc = 0;
                count++;
            }
        }
        sb.append("200, 300");
        System.out.println("waypoints (" + (count + 1) + "):");
        System.out.println(sb);
    }

    /** an open-wheel F1 car seen from above: wings, sidepods, exposed tyres, halo (14 x 28) */
    static BufferedImage f1Car() {
        String[] rows = {
            "..kkkkkkkkkk..",
            "..kwwwwwwwwk..",
            "..kkkkrrkkkk..",
            "kkk...rr...kkk",
            "ttk...rr...ktt",
            "ttk..krrk..ktt",
            "ttk..krrk..ktt",
            "kkk..krrk..kkk",
            ".....krrk.....",
            "....krrrrk....",
            "...krrrrrrk...",
            "..krrwrrwrrk..",
            "..krrrrrrrrk..",
            "..krrkhhkrrk..",
            "..krrkhyhkrk..",
            "..krrkhhkrrk..",
            "..krrrrrrrrk..",
            "..krrrrrrrrk..",
            "...krrrrrrk...",
            "kkk.krrrrk.kkk",
            "ttk.krrrrk.ktt",
            "ttk.krrrrk.ktt",
            "ttk..krrk..ktt",
            "ttk..krrk..ktt",
            "kkk..krrk..kkk",
            ".kkkkkkkkkkkk.",
            ".kwwwwwwwwwwk.",
            ".kkkkkkkkkkkk.",
        };
        BufferedImage img = new BufferedImage(14, 28, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 28; y++) {
            for (int x = 0; x < 14; x++) {
                char ch = rows[y].charAt(x);
                int c = ch == 'k' ? 0xff1b1b1f : ch == 't' ? 0xff2a2a2e : ch == 'r' ? 0xffd9433b
                    : ch == 'w' ? 0xfff6f3ea : ch == 'h' ? 0xff3a3a40 : ch == 'y' ? 0xfff2c84b : 0;
                img.setRGB(x, y, c);
            }
        }
        return img;
    }

    static int rgb(int c) {
        return c;
    }
}
