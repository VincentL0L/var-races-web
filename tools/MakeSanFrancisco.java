import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Paints the San Francisco map in the game's 16-bit pixel style, plus the Waymo car sprite
 * and the small previews used by the map picker.
 *
 * Run from the project folder:  java tools/MakeSanFrancisco.java
 *
 * Writes assets/maps/sf/background.png, road_mask.png and preview.png, assets/ui/preview.png
 * (the classic map's preview) and assets/ui/waymo.png.
 *
 * The map is 1920 x 1080 like the classic one, painted at 1/3 size (640 x 360 "art pixels")
 * and scaled up 3x so every art pixel is a crisp 3 x 3 block. Coordinates below are world
 * coordinates (y up, like the game), converted when painting.
 */
public class MakeSanFrancisco {
    static final int W = 1920, H = 1080, S = 3, AW = W / S, AH = H / S;
    /** half the road's width, and the sidewalk beside it (the drivable run-off, see Barriers) */
    static final int ROAD = 64, WALK = 20;

    /** the racing line, same as Waypoints.getSanFranciscoWaypoints() */
    static final int[][] PATH = {
        {200, 880}, {1740, 880}, {1740, 180}, {1100, 180}, {1100, 520},
        {620, 520}, {620, 230}, {200, 230}, {200, 300}};

    static int[] art = new int[AW * AH];
    /** art pixels already taken by parks and landmarks, so houses go around them */
    static boolean[] reserved = new boolean[AW * AH];
    static Random rnd = new Random(1906);

    // palette
    static final int ASPHALT = rgb(0x4b4d55), ASPHALT_SPECK = rgb(0x44464d), LANE = rgb(0xe9e4d6),
        CURB = rgb(0x7a766e), WALK_A = rgb(0xc9c2b4), WALK_B = rgb(0xbab2a3),
        WATER = rgb(0x2f6ea6), WAVE = rgb(0x4d8fc6), WAVE_DARK = rgb(0x275d8e), SAND = rgb(0xcdb98d),
        GRASS = rgb(0x6aa84f), GRASS_LIGHT = rgb(0x82bd5e), TREE = rgb(0x3b7537), TREE_LIGHT = rgb(0x58994a),
        TREE_DARK = rgb(0x2a5528), OUTLINE = rgb(0x2a1d17), ORANGE = rgb(0xc4402f), ORANGE_LIGHT = rgb(0xe0634c),
        ORANGE_DARK = rgb(0x8c2a20), DECK = rgb(0x8f8a83), ALLEY = rgb(0x6f6a62), BRICK = rgb(0xb5523b),
        WHITE = rgb(0xf2efe6), ROCK = rgb(0x8c7d6a), ROCK_DARK = rgb(0x6c5f50);
    static final int[] ROOFS = {rgb(0xe8a0a8), rgb(0x9fd3c7), rgb(0xc3a6e0), rgb(0xf2d27a), rgb(0x8fb8e8),
        rgb(0xf0b07a), rgb(0xe6dccb), rgb(0xd98c7a)};
    static final int[] TOWERS = {rgb(0x9aa5b1), rgb(0x8796a6), rgb(0xb3b8bd), rgb(0x7f8a99), rgb(0xa9b4c4)};

    public static void main(String[] args) throws Exception {
        new File("assets/maps/sf").mkdirs();
        paintGround();
        paintCity();
        paintLandmarks();
        paintRoads();
        paintBridge();

        BufferedImage map = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                map.setRGB(x, y, art[(y / S) * AW + x / S]);
            }
        }
        ImageIO.write(map, "png", new File("assets/maps/sf/background.png"));

        BufferedImage mask = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                mask.setRGB(x, y, onRoad(x, H - y) ? 0xffffff : 0x000000);
            }
        }
        ImageIO.write(mask, "png", new File("assets/maps/sf/road_mask.png"));

        ImageIO.write(shrink(map, 384, 216), "png", new File("assets/maps/sf/preview.png"));
        ImageIO.write(shrink(ImageIO.read(new File("assets/ui/background.png")), 384, 216), "png",
            new File("assets/ui/preview.png"));
        ImageIO.write(waymo(), "png", new File("assets/ui/waymo.png"));
        System.out.println("San Francisco painted");
    }

    // ------------------------------------------------------------------ geometry (world coords)

    /** road = every straight's rectangle grown by the road's half width (square corners) */
    static boolean onRoad(int x, int y) {
        return roadDistance(x, y) <= ROAD;
    }

    /** distance outside the straights' center lines, measured as a box (square corners) */
    static int roadDistance(int x, int y) {
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < PATH.length; i++) {
            int[] a = PATH[i], b = PATH[(i + 1) % PATH.length];
            int dx = Math.max(Math.max(Math.min(a[0], b[0]) - x, x - Math.max(a[0], b[0])), 0);
            int dy = Math.max(Math.max(Math.min(a[1], b[1]) - y, y - Math.max(a[1], b[1])), 0);
            best = Math.min(best, Math.max(dx, dy));
        }
        return best;
    }

    /** the bay: across the top (under the bridge) and down the right edge */
    static boolean water(int x, int y) {
        boolean bay = y > 668 + 10 * Math.sin(x / 47.0) + 6 * Math.sin(x / 13.0)
            && x > 380 + 14 * Math.sin(y / 31.0) && x < 1420 + 12 * Math.sin(y / 23.0);
        boolean east = x > 1846 + 6 * Math.sin(y / 37.0);
        return bay || east;
    }

    static boolean nearWater(int x, int y, int d) {
        return water(x + d, y) || water(x - d, y) || water(x, y + d) || water(x, y - d);
    }

    // ------------------------------------------------------------------ painting

    static void paintGround() {
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                int x = ax * S + 1, y = H - ay * S - 1;
                int c;
                if (water(x, y)) {
                    // rows of little waves
                    int n = (ax * 7 + ay * 13 + (ay / 3) * 5) % 23;
                    c = n == 0 ? WAVE : n == 11 ? WAVE_DARK : WATER;
                } else if (nearWater(x, y, 6)) {
                    c = SAND;
                } else {
                    c = ((ax + ay) % 9 == 0) ? GRASS_LIGHT : GRASS;
                }
                set(ax, ay, c);
            }
        }
        // the fog rolling in over the bay (dithered, the 16-bit way)
        fog(560, 1000, 160, 50);
        fog(820, 940, 110, 34);
        fog(1300, 1030, 140, 40);
    }

    static void fog(int cx, int cy, int rx, int ry) {
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                int x = ax * S, y = H - ay * S;
                double d = Math.pow((x - cx) / (double) rx, 2) + Math.pow((y - cy) / (double) ry, 2);
                if (d < 1 && water(x, y) && ((ax + ay) % 2 == 0 || d < 0.35)) {
                    set(ax, ay, blend(get(ax, ay), WHITE, d < 0.35 ? 0.55 : 0.35));
                }
            }
        }
    }

    /** blocks of houses and towers everywhere that isn't road, sidewalk, water or a park */
    static void paintCity() {
        // parks and landmark spots first so houses don't go there
        park(684, 0, 1036, 300);      // Alamo Square
        park(0, 0, 136, 1080);        // the Presidio along the left
        park(0, 0, 560, 160);
        park(1500, 470, 1676, 640);   // Telegraph Hill (Coit Tower)
        reserve(1500, 470, 1676, 640);
        reserve(684, 0, 1036, 300);
        reserve(300, 590, 470, 810);  // Lombard Street
        reserve(1380, 300, 1500, 440);   // Transamerica

        // neighborhoods: small painted houses; downtown: big gray towers
        houses(264, 294, 556, 816, false);
        houses(556, 584, 1676, 816, false);
        houses(1164, 244, 1676, 600, true);
        houses(1036, 0, 1810, 116, false);
        houses(1420, 944, 1846, 1080, false);
        houses(684, 300, 1036, 456, false);
        houses(264, 944, 400, 1080, false);
    }

    static void park(int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y += S) {
            for (int x = x0; x < x1; x += S) {
                if (free(x, y)) {
                    int ax = x / S, ay = (H - y) / S;
                    set(ax, ay, ((ax * 3 + ay * 5) % 11 == 0) ? GRASS_LIGHT : GRASS);
                }
            }
        }
        // trees, round with a lit top-left, scattered but not on top of each other
        for (int i = 0; i < (x1 - x0) * (y1 - y0) / 2600; i++) {
            int x = x0 + rnd.nextInt(Math.max(1, x1 - x0)), y = y0 + rnd.nextInt(Math.max(1, y1 - y0));
            if (free(x, y) && free(x + 24, y) && free(x - 24, y) && free(x, y + 24) && free(x, y - 24)) {
                tree(x / S, (H - y) / S, 3 + rnd.nextInt(3));
            }
        }
    }

    /** true where nothing else should be painted: not road, sidewalk or water */
    static boolean free(int x, int y) {
        return x >= 0 && y >= 0 && x < W && y < H && roadDistance(x, y) > ROAD + WALK && !water(x, y)
            && !nearWater(x, y, 6) && (ignoreReserved || !reserved[((H - 1 - y) / S) * AW + Math.min(AW - 1, x / S)]);
    }

    static boolean ignoreReserved = false;

    static void reserve(int x0, int y0, int x1, int y1) {
        for (int ay = (H - y1) / S; ay <= (H - y0) / S; ay++) {
            for (int ax = x0 / S; ax <= x1 / S; ax++) {
                if (ax >= 0 && ay >= 0 && ax < AW && ay < AH) {
                    reserved[ay * AW + ax] = true;
                }
            }
        }
    }

    static void tree(int cx, int cy, int r) {
        disc(cx + 1, cy + 1, r, TREE_DARK);          // shadow
        disc(cx, cy, r, TREE);
        disc(cx - r / 3, cy - r / 3, Math.max(1, r / 2), TREE_LIGHT);
        set(cx + r / 3, cy - r / 2, TREE_LIGHT);
    }

    static void houses(int x0, int y0, int x1, int y1, boolean downtown) {
        // the ground between buildings is paved, not grass
        for (int y = y0; y < y1; y += S) {
            for (int x = x0; x < x1; x += S) {
                if (free(x, y)) {
                    int ax = x / S, ay = (H - y) / S;
                    set(ax, ay, downtown ? ((ax % 6 == 0 || ay % 6 == 0) ? rgb(0xa9a296) : rgb(0xbdb6a8)) : ALLEY);
                }
            }
        }
        if (downtown) {
            // towers of different sizes on a loose grid
            for (int by = y0; by < y1; by += 66) {
                int bx = x0;
                while (bx < x1 - 30) {
                    int bw = 54 + 12 * rnd.nextInt(4);
                    building(bx, by, Math.min(bw, x1 - bx), Math.min(60, y1 - by), TOWERS[rnd.nextInt(TOWERS.length)], true);
                    bx += bw + 6;
                }
            }
            return;
        }
        // city blocks: two rows of narrow, deep row houses back to back, little gardens between
        int depth = 33, garden = 12, gap = 6;
        for (int by = y0; by < y1 - depth; by += depth * 2 + garden + gap) {
            for (int row = 0; row < 2; row++) {
                int ry = by + row * (depth + garden);
                if (ry + depth > y1) {
                    break;
                }
                if (row == 1) {
                    // back gardens behind the first row
                    for (int y = ry - garden; y < ry; y += S) {
                        for (int x = x0; x < x1; x += S) {
                            if (free(x, y)) {
                                set(x / S, (H - y) / S, ((x / S + y / S) % 4 == 0) ? GRASS_LIGHT : GRASS);
                            }
                        }
                    }
                }
                int x = x0;
                while (x < x1 - 15) {
                    int w = 18 + 3 * rnd.nextInt(4);
                    building(x, ry, Math.min(w, x1 - x), depth, ROOFS[rnd.nextInt(ROOFS.length)], false);
                    x += w;
                }
            }
        }
    }

    /** one roof seen from above: outline, lit and shaded halves, ridge, details; a shadow beside */
    static void building(int x, int y, int w, int h, int color, boolean tower) {
        int ax0 = x / S, ax1 = (x + w) / S - 1, ay0 = (H - (y + h)) / S, ay1 = (H - y) / S - 1;
        if (ax1 - ax0 < 2 || ay1 - ay0 < 2) {
            return;
        }
        // only where the whole building has room (never over sidewalks, road or water)
        for (int ay = ay0; ay <= ay1; ay++) {
            for (int ax = ax0; ax <= ax1; ax++) {
                if (!free(ax * S + 1, H - ay * S - 1)) {
                    return;
                }
            }
        }
        for (int ay = ay0; ay <= ay1 + 1; ay++) {
            for (int ax = ax0 + 1; ax <= ax1 + 1; ax++) {
                if (ay > ay1 || ax > ax1) {
                    shade(ax, ay, 0.7);            // shadow on the bottom right
                }
            }
        }
        int mid = (ax0 + ax1) / 2;
        for (int ay = ay0; ay <= ay1; ay++) {
            for (int ax = ax0; ax <= ax1; ax++) {
                int c = color;
                if (ax == ax0 || ax == ax1 || ay == ay0 || ay == ay1) {
                    c = OUTLINE;
                } else if (!tower && ax == mid) {
                    c = blend(color, OUTLINE, 0.4);      // the ridge, running front to back
                } else if (!tower && ay == ay0 + 1) {
                    c = blend(color, WHITE, 0.35);       // the front edge (bay windows) catches the light
                } else if (!tower) {
                    c = ax < mid ? blend(color, WHITE, 0.16) : blend(color, OUTLINE, 0.14);
                } else if (ax == ax0 + 1 || ay == ay0 + 1) {
                    c = blend(color, WHITE, 0.25);       // lit edge of a flat roof
                }
                set(ax, ay, c);
            }
        }
        if (tower) {
            // rooftop units and a helipad now and then
            for (int i = 0; i < 2; i++) {
                int ux = ax0 + 2 + rnd.nextInt(Math.max(1, ax1 - ax0 - 4)), uy = ay0 + 2 + rnd.nextInt(Math.max(1, ay1 - ay0 - 4));
                set(ux, uy, rgb(0x5d636b));
                set(ux + 1, uy, rgb(0x5d636b));
            }
        } else if (rnd.nextInt(3) == 0) {
            set(mid + 1, ay1 - 2, OUTLINE);   // chimney
        }
    }

    static void paintLandmarks() {
        // Lombard Street: the crooked brick switchbacks between flower beds
        int lx = 380 / S, ltop = (H - 790) / S, lbottom = (H - 610) / S;
        for (int ay = ltop; ay <= lbottom; ay++) {
            for (int ax = lx - 13; ax <= lx + 13; ax++) {
                int n = (ax * 7 + ay * 11) % 19;
                set(ax, ay, n == 0 ? rgb(0xe0607e) : n == 9 ? rgb(0xf2c84b) : TREE_LIGHT);
            }
        }
        // the switchbacks: brick zig-zag with a darker edge
        for (int ay = ltop; ay <= lbottom; ay++) {
            int t = (ay - ltop) % 20;
            int off = t < 10 ? -9 + t * 2 : 11 - (t - 10) * 2;
            for (int k = -3; k <= 3; k++) {
                set(lx + off + k, ay, Math.abs(k) == 3 ? rgb(0x7d3426) : BRICK);
            }
        }
        frameRect(lx - 14, ltop - 1, lx + 14, lbottom + 1, OUTLINE);

        // Painted Ladies facing Alamo Square
        int[] ladies = {rgb(0xf3b6c4), rgb(0x9fd3c7), rgb(0xf2d27a), rgb(0xc3a6e0), rgb(0x8fb8e8), rgb(0xf0b07a), rgb(0xe8a0a8)};
        ignoreReserved = true;
        for (int i = 0; i < ladies.length; i++) {
            building(700 + i * 45, 252, 42, 45, ladies[i], false);
        }
        ignoreReserved = false;

        // Transamerica Pyramid: a white pyramid seen from straight above
        pyramid(1440 / S, (H - 370) / S, 12);
        // Coit Tower on Telegraph Hill
        for (int i = 1; i <= 2; i++) {
            shade(1588 / S + 6 + i, (H - 555) / S + i, 0.7);
        }
        disc(1588 / S, (H - 555) / S, 6, OUTLINE);
        disc(1588 / S, (H - 555) / S, 5, WHITE);
        disc(1588 / S, (H - 555) / S, 2, rgb(0xcfc8b8));

        // the Ferry Building and its clock tower, out on the water at the Embarcadero
        int fx0 = 1830 / S, fx1 = 1890 / S, fy0 = (H - 640) / S, fy1 = (H - 380) / S;
        for (int ay = fy0 - 1; ay <= fy1 + 1; ay++) {
            for (int ax = fx0 - 1; ax <= fx1 + 1; ax++) {
                boolean edge = ax < fx0 || ax > fx1 || ay < fy0 || ay > fy1;
                set(ax, ay, edge ? OUTLINE : (ax == fx0 ? rgb(0xf1e6cf) : rgb(0xdccdb0)));
            }
        }
        int tx = (fx0 + fx1) / 2, ty = (fy0 + fy1) / 2;
        for (int ay = ty - 4; ay <= ty + 4; ay++) {
            for (int ax = tx - 4; ax <= tx + 4; ax++) {
                boolean edge = Math.abs(ax - tx) == 4 || Math.abs(ay - ty) == 4;
                set(ax, ay, edge ? OUTLINE : rgb(0xc7b08a));
            }
        }
        set(tx, ty, rgb(0x2f6f62));
        // piers along the Embarcadero
        for (int py : new int[] {120, 230, 760, 860, 980}) {
            for (int ay = (H - py - 18) / S; ay <= (H - py + 18) / S; ay++) {
                for (int ax = 1852 / S; ax < AW; ax++) {
                    if (water(ax * S, H - ay * S)) {
                        set(ax, ay, (ay == (H - py - 18) / S || ay == (H - py + 18) / S) ? OUTLINE : rgb(0x8a6a4a));
                    }
                }
            }
        }

        // Alcatraz, out in the bay
        int ix = 1000 / S, iy = (H - 1015) / S;
        for (int ay = iy - 9; ay <= iy + 9; ay++) {
            for (int ax = ix - 22; ax <= ix + 22; ax++) {
                double d = Math.pow((ax - ix) / 22.0, 2) + Math.pow((ay - iy) / 9.0, 2);
                if (d < 1) {
                    set(ax, ay, d > 0.75 ? ROCK_DARK : ((ax + ay) % 6 == 0 ? GRASS : ROCK));
                }
            }
        }
        for (int ay = iy - 3; ay <= iy + 2; ay++) {
            for (int ax = ix - 9; ax <= ix + 7; ax++) {
                boolean edge = ay == iy - 3 || ay == iy + 2 || ax == ix - 9 || ax == ix + 7;
                set(ax, ay, edge ? OUTLINE : WHITE);
            }
        }
        set(ix + 12, iy - 4, OUTLINE);
        set(ix + 12, iy - 5, rgb(0xffe08a));   // the lighthouse
    }

    static void pyramid(int cx, int cy, int r) {
        for (int ay = cy - r; ay <= cy + r; ay++) {
            for (int ax = cx - r; ax <= cx + r; ax++) {
                int dx = ax - cx, dy = ay - cy;
                int c;
                if (Math.abs(dx) == r || Math.abs(dy) == r) {
                    c = OUTLINE;
                } else if (Math.abs(dx) == Math.abs(dy)) {
                    c = rgb(0xb9b4aa);              // the four ridges
                } else if (Math.abs(dy) > Math.abs(dx)) {
                    c = dy < 0 ? rgb(0xfbf8f0) : rgb(0xc9c3b6);
                } else {
                    c = dx < 0 ? rgb(0xeee9df) : rgb(0xd8d2c5);
                }
                set(ax, ay, c);
            }
        }
        for (int i = 1; i <= 3; i++) {
            shade(cx + r + i, cy + i, 0.7);
        }
    }

    /** roads: asphalt with a few specks, a curb, the sidewalk, dashed center lines, the start line */
    static void paintRoads() {
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                int x = ax * S + 1, y = H - ay * S - 1;
                int d = roadDistance(x, y);
                boolean overWater = water(x, y) || nearWater(x, y, 6);
                if (d <= ROAD) {
                    set(ax, ay, d > ROAD - S ? CURB : ((ax * 5 + ay * 3) % 17 == 0 ? ASPHALT_SPECK : ASPHALT));
                } else if (d <= ROAD + WALK && !overWater) {
                    // pavement squares
                    set(ax, ay, (ax % 4 == 0 || ay % 4 == 0) ? WALK_B : WALK_A);
                } else if (d <= ROAD + WALK + S && !overWater) {
                    // a low curb with bollards along the back of the sidewalk: the edge of the track
                    set(ax, ay, ((ax + ay) % 4 == 0) ? rgb(0xe8c547) : rgb(0x55514b));
                }
            }
        }
        // dashed lane line down the middle of every straight, stopping short of the corners
        for (int i = 0; i < PATH.length - 1; i++) {
            int[] a = PATH[i], b = PATH[i + 1];
            int len = Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1]);
            for (int t = ROAD + 12; t < len - ROAD - 12; t += S) {
                int x = a[0] + Integer.signum(b[0] - a[0]) * t, y = a[1] + Integer.signum(b[1] - a[1]) * t;
                if ((t / 18) % 2 == 0) {
                    set(x / S, (H - y) / S, LANE);
                }
            }
        }
        // cable car tracks up California Street (the x = 1100 straight)
        for (int y = 180 + ROAD; y < 520 - ROAD; y += S) {
            for (int x : new int[] {1100 - 34, 1100 - 22, 1100 + 22, 1100 + 34}) {
                set(x / S, (H - y) / S, rgb(0x34353a));
            }
        }
        // the checkered start line: y 300-324 across the road, like the classic map
        for (int y = 300; y < 324; y += S) {
            for (int x = 200 - ROAD + S; x < 200 + ROAD - S; x += S) {
                int cx = (x - (200 - ROAD)) / 6, cy = (y - 300) / 6;
                set(x / S, (H - 1 - y) / S, (cx + cy) % 2 == 0 ? WHITE : rgb(0x1b1b1f));
            }
        }
    }

    /** the Golden Gate: orange railings and towers where the road crosses the water */
    static void paintBridge() {
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                int x = ax * S + 1, y = H - ay * S - 1;
                if (!(water(x, y) || nearWater(x, y, 6)) || y < 700) {
                    continue;
                }
                int d = Math.abs(y - 880);
                if (d > ROAD && d <= ROAD + WALK - S) {
                    set(ax, ay, DECK);                       // walkway
                } else if (d > ROAD + WALK - S && d <= ROAD + WALK + S) {
                    set(ax, ay, ORANGE);                     // railing and main cable
                } else if (d > ROAD + WALK + S && d <= ROAD + WALK + 3 * S && y < 880) {
                    set(ax, ay, WAVE_DARK);                  // the deck's shadow on the water
                }
            }
        }
        // suspender ropes as little dots along the railings
        for (int x = 400; x < 1420; x += 18) {
            set(x / S, (H - (880 + ROAD + WALK)) / S, ORANGE_LIGHT);
            set(x / S, (H - (880 - ROAD - WALK)) / S, ORANGE_LIGHT);
        }
        // two towers: a leg each side of the deck and a beam across
        for (int tx : new int[] {640, 1160}) {
            for (int side : new int[] {-1, 1}) {
                int legY = 880 + side * (ROAD + WALK + 14);
                for (int y = legY - 14; y <= legY + 14; y += S) {
                    for (int x = tx - 18; x <= tx + 18; x += S) {
                        boolean edge = Math.abs(x - tx) >= 15 || Math.abs(y - legY) >= 12;
                        set(x / S, (H - y) / S, edge ? ORANGE_DARK : (x < tx ? ORANGE_LIGHT : ORANGE));
                    }
                }
                for (int i = 1; i <= 2; i++) {
                    shade((tx + 18) / S + i, (H - (legY - 14)) / S + i, 0.65);
                }
            }
            for (int y = 880 - ROAD - WALK; y <= 880 + ROAD + WALK; y += S) {
                set((tx - 3) / S, (H - y) / S, ORANGE_DARK);
                set((tx + 3) / S, (H - y) / S, ORANGE);
            }
        }
    }

    // ------------------------------------------------------------------ Waymo sprite

    static BufferedImage waymo() {
        String[] rows = {
            "...kkkkkkkk...",
            "..kywwwwwwyk..",
            ".kwwwwwwwwwwk.",
            "skwwwwwwwwwwks",
            ".kwwwwwwwwwwk.",
            ".kgwwwwwwwwgk.",
            "kkgbbbbbbbbgkk",
            ".kgbbbbbbbbgk.",
            ".kwgbbbbbbgwk.",
            ".kwbbbbbbbbwk.",
            ".kwbbllllbbwk.",
            ".kwbllccllbwk.",
            ".kwbllccllbwk.",
            ".kwbbllllbbwk.",
            ".kwbbbbbbbbwk.",
            ".kwbbbbbbbbwk.",
            ".kwbbbbbbbbwk.",
            ".kwgbbbbbbgwk.",
            ".kwwwwwwwwwwk.",
            ".kgwwwwwwwwgk.",
            ".kgbbbbbbbbgk.",
            ".kgwbbbbbbwgk.",
            ".kwwwwwwwwwwk.",
            ".kwwwwccwwwwk.",
            ".kwwwwwwwwwwk.",
            "skwwwwwwwwwwks",
            "..krwwwwwwrk..",
            "...kkkkkkkk..."};
        BufferedImage img = new BufferedImage(14, 28, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < rows.length; y++) {
            if (rows[y].length() != 14) {
                throw new IllegalStateException("waymo row " + y + " is " + rows[y].length() + " wide");
            }
            for (int x = 0; x < 14; x++) {
                int c;
                switch (rows[y].charAt(x)) {
                    case 'k': c = rgb(0x1c1c22); break;
                    case 'w': c = rgb(0xf6f6f3); break;
                    case 'g': c = rgb(0xc8cbd1); break;
                    case 'b': c = rgb(0x23262d); break;
                    case 'l': c = rgb(0x3d434d); break;
                    case 'c': c = rgb(0x5fd3c6); break;
                    case 's': c = rgb(0x111114); break;
                    case 'r': c = rgb(0xd23a3a); break;
                    case 'y': c = rgb(0xfff0b0); break;
                    default: c = 0;
                }
                img.setRGB(x, y, c);
            }
        }
        return img;
    }

    // ------------------------------------------------------------------ helpers

    static BufferedImage shrink(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int fx = src.getWidth() / w, fy = src.getHeight() / h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long r = 0, g = 0, b = 0;
                for (int j = 0; j < fy; j++) {
                    for (int i = 0; i < fx; i++) {
                        int c = src.getRGB(x * fx + i, y * fy + j);
                        r += (c >> 16) & 255;
                        g += (c >> 8) & 255;
                        b += c & 255;
                    }
                }
                int n = fx * fy;
                out.setRGB(x, y, (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n));
            }
        }
        return out;
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

    static void frameRect(int x0, int y0, int x1, int y1, int c) {
        for (int x = x0; x <= x1; x++) {
            set(x, y0, c);
            set(x, y1, c);
        }
        for (int y = y0; y <= y1; y++) {
            set(x0, y, c);
            set(x1, y, c);
        }
    }

    static int blend(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }
}
