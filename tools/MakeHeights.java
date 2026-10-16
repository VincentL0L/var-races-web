import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;

import javax.imageio.ImageIO;

/**
 * Works out what stands up off each map, for the first-person view (Scenery3D): trees and
 * buildings, read from the colours of the map picture. Roads, pavements, grass and water
 * stay flat. The result is a small grey picture, one pixel per CELL x CELL patch of the
 * map, whose red channel is the height (map pixels) of whatever stands there.
 *
 * Run from the project folder:  java tools/MakeHeights.java [preview-folder]
 *   -> heights.png next to each map's background.png
 */
public class MakeHeights {
    /** map pixels per height cell */
    static final int CELL = 12;
    /** keep this far (map pixels) from the road clear, so the pavements stay flat */
    static final int ROAD_CLEAR = 26;

    public static void main(String[] args) throws Exception {
        String preview = args.length > 0 ? args[0] : null;
        // folder, scale, what to raise (T trees, B buildings)
        make("assets/ui/", null, 1, "T", preview, "classic");
        for (String city : new String[] {"sf", "tokyo", "nyc", "chicago", "london", "paris", "taipei"}) {
            make("assets/maps/" + city + "/", null, 1, "TB", preview, city);
        }
        make("assets/maps/summit/", null, 3, "T", preview, "summit");
        make("assets/maps/rushhour/", null, 3, "TB", preview, "rushhour");
        make("assets/maps/rooftop/", null, 3, "TB", preview, "rooftop");
        make("assets/maps/moon/", null, 3, "", preview, "moon");
        // the Grand Prix: only the grandstands and pit garages are buildings (world x0, y0, x1, y1)
        onlyBuildIn = new float[][] {{330, 380, 420, 1250}, {440, 450, 560, 1150}, {1200, 150, 2600, 240}};
        make("assets/maps/gp/", null, 3, "TB", preview, "gp");
        onlyBuildIn = null;
        make("assets/maps/junkyard/", null, 3, "B", preview, "junkyard");
        make("assets/maps/stadium/", null, 3, "B", preview, "stadium");
        make("assets/maps/rink/", null, 3, "B", preview, "rink");
    }

    static void make(String folder, String maskPath, int scale, String kinds, String preview, String name) throws Exception {
        BufferedImage img = ImageIO.read(new File(folder + "background.png"));
        BufferedImage mask = ImageIO.read(new File(maskPath != null ? maskPath : folder + "road_mask.png"));
        int worldW = img.getWidth() * scale, worldH = img.getHeight() * scale;
        int gw = (worldW + CELL - 1) / CELL, gh = (worldH + CELL - 1) / CELL;
        // the mask may be stored at its own size: map world -> mask pixels
        float mx = mask.getWidth() / (float) worldW, my = mask.getHeight() / (float) worldH;

        // the ground's own colours: the commonest colours off the road (grass, pavement, water...)
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        int total = 0;
        for (int x = 0; x < img.getWidth(); x += 2) {
            for (int y = 0; y < img.getHeight(); y += 2) {
                if (isRoad(mask, x * scale * mx, y * scale * my)) {
                    continue;
                }
                counts.merge(quant(img.getRGB(x, y)), 1, Integer::sum);
                total++;
            }
        }
        java.util.List<java.util.Map.Entry<Integer, Integer>> common = new java.util.ArrayList<>(counts.entrySet());
        common.sort((a, b) -> b.getValue() - a.getValue());
        java.util.List<Integer> ground = new java.util.ArrayList<>();
        int covered = 0;
        for (java.util.Map.Entry<Integer, Integer> e : common) {
            if (covered > total * GROUND_SHARE || ground.size() >= 14) {
                break;
            }
            ground.add(e.getKey());
            covered += e.getValue();
        }

        boolean[][] object = new boolean[gw][gh];
        int[][] kind = new int[gw][gh];          // 0 flat, 1 tree, 2 building
        boolean[][] road = new boolean[gw][gh];
        float[][] green = new float[gw][gh];
        for (int gx = 0; gx < gw; gx++) {
            for (int gy = 0; gy < gh; gy++) {
                int odd = 0, n = 0, greenish = 0, leaves = 0;
                boolean anyRoad = false;
                for (int wx = gx * CELL; wx < Math.min(worldW, gx * CELL + CELL); wx += Math.max(1, scale)) {
                    for (int wy = gy * CELL; wy < Math.min(worldH, gy * CELL + CELL); wy += Math.max(1, scale)) {
                        if (isRoad(mask, wx * mx, wy * my)) {
                            anyRoad = true;
                        }
                        int c = img.getRGB(wx / scale, wy / scale);
                        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                        n++;
                        if (g > r + 30 && r < 95 && b > 30 && b < 100) {
                            leaves++;                  // the dark green of tree tops
                        }
                        if (!nearAny(c, ground)) {
                            odd++;
                            if (g > r + 8 && g > b + 8) {
                                greenish++;
                            }
                        }
                    }
                }
                road[gx][gy] = anyRoad;
                object[gx][gy] = !anyRoad && (odd > n * 0.5f || leaves > n * 0.45f);
                green[gx][gy] = leaves > n * 0.45f ? 1f : odd == 0 ? 0f : greenish / (float) odd;
            }
        }
        // close small gaps, then drop specks
        object = erode(dilate(object, gw, gh), gw, gh);
        for (int gx = 0; gx < gw; gx++) {
            for (int gy = 0; gy < gh; gy++) {
                if (object[gx][gy] && !road[gx][gy]) {
                    kind[gx][gy] = green[gx][gy] > 0.5f ? 1 : 2;
                }
            }
        }
        if (!kinds.contains("T") || !kinds.contains("B")) {
            for (int gx = 0; gx < gw; gx++) {
                for (int gy = 0; gy < gh; gy++) {
                    if (kind[gx][gy] == 1 && !kinds.contains("T")) {
                        kind[gx][gy] = kinds.contains("B") ? 2 : 0;
                    } else if (kind[gx][gy] == 2 && !kinds.contains("B")) {
                        kind[gx][gy] = kinds.contains("T") ? 1 : 0;
                    }
                }
            }
        }
        if (onlyBuildIn != null) {
            for (int gx = 0; gx < gw; gx++) {
                for (int gy = 0; gy < gh; gy++) {
                    if (kind[gx][gy] != 2) {
                        continue;
                    }
                    float x = gx * CELL + CELL / 2f, y = worldH - gy * CELL - CELL / 2f;
                    boolean ok = false;
                    for (float[] r : onlyBuildIn) {
                        ok |= x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3];
                    }
                    if (!ok) {
                        kind[gx][gy] = 0;
                    }
                }
            }
        }
        // flatten everything close to the road
        int clear = (ROAD_CLEAR + CELL - 1) / CELL;
        int[][] near = distanceFrom(road, gw, gh);
        for (int gx = 0; gx < gw; gx++) {
            for (int gy = 0; gy < gh; gy++) {
                if (near[gx][gy] <= clear) {
                    kind[gx][gy] = 0;
                }
            }
        }
        // buildings: whole blocks get one height; tiny scraps are dropped
        int[][] height = new int[gw][gh];
        boolean[][] seen = new boolean[gw][gh];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        java.util.List<int[]> part = new java.util.ArrayList<>();
        for (int gx = 0; gx < gw; gx++) {
            for (int gy = 0; gy < gh; gy++) {
                if (seen[gx][gy] || kind[gx][gy] == 0) {
                    continue;
                }
                int k = kind[gx][gy];
                part.clear();
                queue.add(new int[] {gx, gy});
                seen[gx][gy] = true;
                while (!queue.isEmpty()) {
                    int[] p = queue.poll();
                    part.add(p);
                    int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] d : dirs) {
                        int x = p[0] + d[0], y = p[1] + d[1];
                        if (x >= 0 && y >= 0 && x < gw && y < gh && !seen[x][y] && kind[x][y] == k) {
                            seen[x][y] = true;
                            queue.add(new int[] {x, y});
                        }
                    }
                }
                if (part.size() < (k == 2 ? 4 : 2)) {
                    continue;
                }
                int hash = (gx * 73856093) ^ (gy * 19349663);
                for (int[] p : part) {
                    int h;
                    if (k == 1) {
                        // trees: a little uneven, cell by cell
                        int ch = (p[0] * 92821) ^ (p[1] * 68917);
                        h = 16 + Math.floorMod(ch, 9);
                    } else {
                        h = 26 + Math.floorMod(hash, 5) * 9;
                    }
                    height[p[0]][p[1]] = h;
                }
            }
        }
        // the picture is stored top row first; the game's y runs up
        BufferedImage out = new BufferedImage(gw, gh, BufferedImage.TYPE_INT_RGB);
        int raised = 0;
        for (int gx = 0; gx < gw; gx++) {
            for (int gy = 0; gy < gh; gy++) {
                int h = Math.min(255, height[gx][gy]);
                if (h > 0) {
                    raised++;
                }
                out.setRGB(gx, gy, (h << 16) | (kind[gx][gy] << 8));
            }
        }
        ImageIO.write(out, "png", new File(folder + "heights.png"));
        System.out.println(name + ": " + gw + "x" + gh + " cells, " + raised + " raised");
        if (preview != null) {
            BufferedImage pv = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < img.getWidth(); x++) {
                for (int y = 0; y < img.getHeight(); y++) {
                    int c = img.getRGB(x, y);
                    int gx = x * scale / CELL, gy = y * scale / CELL;
                    int h = height[gx][gy];
                    if (h > 0) {
                        c = kind[gx][gy] == 1 ? 0x00ff00 : 0xff00ff;
                    }
                    pv.setRGB(x, y, c);
                }
            }
            new File(preview).mkdirs();
            ImageIO.write(pv, "png", new File(preview, name + ".png"));
        }
    }

    static final float GROUND_SHARE = 0.6f;
    /** if set, buildings only stand inside these world rectangles (x0, y0, x1, y1) */
    static float[][] onlyBuildIn;

    static boolean isRoad(BufferedImage mask, float x, float y) {
        int m = mask.getRGB(Math.min(mask.getWidth() - 1, (int) x), Math.min(mask.getHeight() - 1, (int) y));
        return ((m >> 8) & 0xff) > 128;
    }

    static int quant(int c) {
        return c & 0xf0f0f0;
    }

    static boolean nearAny(int c, java.util.List<Integer> ground) {
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        for (int q : ground) {
            int dr = r - (((q >> 16) & 0xff) + 8), dg = g - (((q >> 8) & 0xff) + 8), db = b - ((q & 0xff) + 8);
            if (dr * dr + dg * dg + db * db < 26 * 26) {
                return true;
            }
        }
        return false;
    }

    static boolean[][] dilate(boolean[][] a, int w, int h) {
        boolean[][] o = new boolean[w][h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int dx = -1; dx <= 1 && !o[x][y]; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx >= 0 && yy >= 0 && xx < w && yy < h && a[xx][yy]) {
                            o[x][y] = true;
                            break;
                        }
                    }
                }
            }
        }
        return o;
    }

    static boolean[][] erode(boolean[][] a, int w, int h) {
        boolean[][] o = new boolean[w][h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                boolean all = true;
                for (int dx = -1; dx <= 1 && all; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx >= 0 && yy >= 0 && xx < w && yy < h && !a[xx][yy]) {
                            all = false;
                            break;
                        }
                    }
                }
                o[x][y] = all;
            }
        }
        return o;
    }

    /** cell distance (4-way steps) from the nearest road cell */
    static int[][] distanceFrom(boolean[][] road, int gw, int gh) {
        int[][] d = new int[gw][gh];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int x = 0; x < gw; x++) {
            for (int y = 0; y < gh; y++) {
                d[x][y] = road[x][y] ? 0 : Integer.MAX_VALUE;
                if (road[x][y]) {
                    q.add(new int[] {x, y});
                }
            }
        }
        while (!q.isEmpty()) {
            int[] p = q.poll();
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] dd : dirs) {
                int x = p[0] + dd[0], y = p[1] + dd[1];
                if (x >= 0 && y >= 0 && x < gw && y < gh && d[x][y] > d[p[0]][p[1]] + 1) {
                    d[x][y] = d[p[0]][p[1]] + 1;
                    q.add(new int[] {x, y});
                }
            }
        }
        return d;
    }
}
