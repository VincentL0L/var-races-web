import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Paints the Tokyo and New York maps in a GBA Pokemon-style 3/4 view (buildings show their
 * roof and their front, trees have round canopies and a trunk, grass has tufts and flowers),
 * plus each city's CPU car sprite and the map previews.
 *
 * Run from the project folder:  java tools/MakeCities.java
 *
 * Like the San Francisco map (tools/MakeSanFrancisco.java), each map is 1920 x 1080 painted
 * at 1/3 size (640 x 360 art pixels) and scaled up 3x. Every map starts the same way: the
 * last straight comes in along y = 230, turns up at x = 200 and crosses the checkered line at
 * y = 300. Coordinates are world coordinates (y up) unless they're called ax / ay (art pixels,
 * y down).
 */
public class MakeCities {
    static final int W = 1920, H = 1080, S = 3, AW = W / S, AH = H / S;
    /** half the road's width, and the sidewalk beside it (the drivable run-off, see Barriers) */
    static final int ROAD = 64, WALK = 20;

    // the racing lines, same as Waypoints (the last point is the start line)
    static final int[][] TOKYO = {
        {200, 860}, {700, 860}, {700, 620}, {1200, 620}, {1200, 900}, {1760, 900}, {1760, 200},
        {1000, 200}, {1000, 420}, {520, 420}, {520, 230}, {200, 230}, {200, 300}};
    static final int[][] NEW_YORK = {
        {200, 700}, {560, 700}, {560, 900}, {1760, 900}, {1760, 200}, {1240, 200}, {1240, 520},
        {860, 520}, {860, 230}, {200, 230}, {200, 300}};

    interface Area {
        boolean at(int x, int y);
    }

    static int[][] path;
    static Area water;
    static int[] art;
    static boolean[] reserved;
    static Random rnd;

    // shared palette
    static final int OUTLINE = rgb(0x2a1d1a), ASPHALT = rgb(0x4d4f57), ASPHALT_SPECK = rgb(0x45474e),
        CURB = rgb(0x7c786f), WALK_A = rgb(0xd2cbbd), WALK_B = rgb(0xc1b9a9), LANE = rgb(0xeee9dc),
        PAVE = rgb(0xbdb6a7), PAVE_LINE = rgb(0xaaa395), GRASS = rgb(0x78c058), GRASS_DARK = rgb(0x5fa548),
        GRASS_LIGHT = rgb(0x98d870), TREE = rgb(0x3f8f3c), TREE_LIGHT = rgb(0x66b94f), TREE_DARK = rgb(0x2b6230),
        TRUNK = rgb(0x7a4a2a), WATER = rgb(0x3f8fd8), WATER_DARK = rgb(0x3378c0), SPARKLE = rgb(0xbfe6ff),
        SHORE = rgb(0xa6a29a), SAND = rgb(0xe3d29c), GLASS = rgb(0x9fd3f0), GLASS_DARK = rgb(0x6aa6cf),
        DOOR = rgb(0x5a3a22), WHITE = rgb(0xf6f3ea), LIT = rgb(0xffe58a), BLACK = rgb(0x1d1c20);

    public static void main(String[] args) throws Exception {
        paintTokyo();
        save("tokyo");
        paintNewYork();
        save("nyc");
        ImageIO.write(sprite(KEI, KEI_LETTERS, KEI_COLORS), "png", new File("assets/ui/kei.png"));
        ImageIO.write(sprite(CAB, CAB_LETTERS, CAB_COLORS), "png", new File("assets/ui/cab.png"));
        System.out.println("Tokyo and New York painted");
    }

    static void begin(int[][] p, Area w, long seed) {
        path = p;
        water = w;
        art = new int[AW * AH];
        reserved = new boolean[AW * AH];
        rnd = new Random(seed);
    }

    static void save(String folder) throws Exception {
        new File("assets/maps/" + folder).mkdirs();
        BufferedImage map = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                map.setRGB(x, y, art[(y / S) * AW + x / S]);
            }
        }
        ImageIO.write(map, "png", new File("assets/maps/" + folder + "/background.png"));
        BufferedImage mask = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                mask.setRGB(x, y, roadDistance(x, H - y) <= ROAD ? 0xffffff : 0x000000);
            }
        }
        ImageIO.write(mask, "png", new File("assets/maps/" + folder + "/road_mask.png"));
        ImageIO.write(shrink(map, 384, 216), "png", new File("assets/maps/" + folder + "/preview.png"));
    }

    // ================================================================== TOKYO

    static void paintTokyo() {
        // the Sumida river runs across the top half; the route crosses it on three bridges
        begin(TOKYO, (x, y) -> y > 720 + 8 * Math.sin(x / 61.0) && y < 800 + 8 * Math.sin(x / 61.0)
            || x > 1850 + 5 * Math.sin(y / 29.0), 1964);
        ground(rgb(0x7cc25a), rgb(0x67ad4b));

        // Shiba park with Tokyo Tower, a shrine in a cherry grove, a garden by the river
        park(1300, 280, 1690, 560, true, false);
        reserve(1300, 280, 1690, 560);
        park(270, 480, 460, 700, true, true);
        reserve(270, 480, 460, 700);
        park(0, 0, 130, 1080, false, true);
        park(0, 0, 450, 150, false, true);

        // neighborhoods
        district(270, 300, 450, 470, "tokyo-houses");
        district(590, 300, 930, 350, "tokyo-houses");
        district(590, 490, 1130, 550, "tokyo-shops");
        district(780, 690, 1130, 710, "tokyo-shops");
        district(270, 810, 630, 1080, "tokyo-houses");
        district(1270, 600, 1690, 830, "tokyo-towers");
        district(1270, 960, 1840, 1080, "tokyo-shops");
        district(1130, 960, 1270, 1080, "tokyo-houses");
        train(600, 235, 925);
        reserve(590, 200, 935, 275);
        district(780, 820, 1130, 1080, "tokyo-towers");
        district(1070, 270, 1270, 550, "tokyo-towers");
        district(1070, 0, 1840, 130, "tokyo-shops");
        district(500, 0, 930, 160, "tokyo-houses");
        district(580, 820, 640, 1080, "tokyo-houses");

        roads(rgb(0xf2f0ea), true);
        // the Shibuya scramble: zebra stripes across the whole crossing, both diagonals too
        scramble(950, 620);
        crosswalks();
        bridges(rgb(0xd8423a), rgb(0xa52f2a), false);

        shrine(365, 590);
        tokyoTower(1495, 300);
        boat(430, 760, rgb(0xb98a5a), true);
        boat(1000, 752, rgb(0xb98a5a), true);
        boat(1500, 765, rgb(0xb98a5a), true);
        people(220);
        startLine();
    }

    // ================================================================== NEW YORK

    static void paintNewYork() {
        // the East River down the right side (two bridges), the harbor in the bottom left
        begin(NEW_YORK, (x, y) -> (x > 1420 + 10 * Math.sin(y / 53.0) && x < 1600 + 10 * Math.sin(y / 53.0))
            || (y < 140 + 12 * Math.sin(x / 37.0) && x < 760) , 1898);
        ground(rgb(0x74b956), rgb(0x60a447));

        // Central Park: lawns, the lake with rowboats, Bethesda Fountain, paths
        centralPark(640, 600, 1330, 830);
        reserve(640, 600, 1330, 830);
        // Times Square
        timesSquare(940, 0, 1170, 450);
        reserve(940, 0, 1170, 450);
        // room for the Empire State Building
        reserve(470, 290, 650, 660);

        district(270, 300, 790, 630, "nyc-towers");
        district(270, 770, 490, 1080, "nyc-brownstones");
        district(640, 970, 1410, 1080, "nyc-brownstones");
        district(1620, 270, 1690, 830, "nyc-brownstones");
        district(1830, 0, 1920, 1080, "nyc-brownstones");
        district(1620, 970, 1830, 1080, "nyc-brownstones");
        district(1310, 270, 1400, 830, "nyc-towers");
        district(940, 590, 1170, 600, "nyc-towers");
        district(1610, 0, 1700, 130, "nyc-brownstones");
        district(0, 0, 130, 1080, "nyc-brownstones");
        district(770, 140, 930, 450, "nyc-towers");
        district(1250, 0, 1400, 130, "nyc-towers");
        district(130, 770, 270, 1080, "nyc-brownstones");

        // a plaza around the Empire State Building
        for (int ay = (H - 660) / S; ay <= (H - 290) / S; ay++) {
            for (int ax = 470 / S; ax <= 650 / S; ax++) {
                if (roadDistance(ax * S + 1, H - 1 - ay * S) > ROAD + WALK + S) {
                    set(ax, ay, (ax % 8 == 0 || ay % 8 == 0) ? PAVE_LINE : PAVE);
                }
            }
        }
        empireState(560, 420);
        flatiron(1355, 320);
        roads(rgb(0xf6d24a), false);
        crosswalks();
        bridges(rgb(0xb9a481), rgb(0x8a7a5e), true);
        liberty(330, 60);
        boat(620, 70, rgb(0xf08a2e), false);       // the Staten Island Ferry
        boat(1470, 600, rgb(0xd9433b), false);
        boat(1500, 330, rgb(0xe9e2d2), false);
        people(260);
        startLine();
    }

    // ================================================================== geometry

    static int roadDistance(int x, int y) {
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < path.length; i++) {
            int[] a = path[i], b = path[(i + 1) % path.length];
            int dx = Math.max(Math.max(Math.min(a[0], b[0]) - x, x - Math.max(a[0], b[0])), 0);
            int dy = Math.max(Math.max(Math.min(a[1], b[1]) - y, y - Math.max(a[1], b[1])), 0);
            best = Math.min(best, Math.max(dx, dy));
        }
        return best;
    }

    static boolean isWater(int x, int y) {
        return water.at(x, y);
    }

    static boolean nearWater(int x, int y, int d) {
        return isWater(x + d, y) || isWater(x - d, y) || isWater(x, y + d) || isWater(x, y - d);
    }

    /** room for scenery: not road, sidewalk, fence, water, shore or a reserved spot */
    static boolean free(int x, int y) {
        if (x < 0 || y < 0 || x >= W || y >= H) {
            return false;
        }
        return roadDistance(x, y) > ROAD + WALK + S && !isWater(x, y) && !nearWater(x, y, 6)
            && !reserved[((H - 1 - y) / S) * AW + x / S];
    }

    static boolean freeArt(int ax, int ay) {
        return ax >= 0 && ay >= 0 && ax < AW && ay < AH && free(ax * S + 1, H - 1 - ay * S - 1);
    }

    static void reserve(int x0, int y0, int x1, int y1) {
        for (int ay = (H - y1) / S; ay <= (H - y0) / S; ay++) {
            for (int ax = x0 / S; ax <= x1 / S; ax++) {
                if (ax >= 0 && ay >= 0 && ax < AW && ay < AH) {
                    reserved[ay * AW + ax] = true;
                }
            }
        }
    }

    // ================================================================== ground, water, parks

    static void ground(int grass, int grassDark) {
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                int x = ax * S + 1, y = H - ay * S - 1;
                if (isWater(x, y)) {
                    // the darker band near the shore, wave sparkles in rows
                    int n = (ax * 5 + (ay / 2) * 11) % 29;
                    int c = nearWater(x, y, 10) && !nearWaterAll(x, y, 10) ? WATER_DARK : WATER;
                    set(ax, ay, n == 0 ? SPARKLE : n == 1 ? rgb(0x7fbcef) : c);
                } else if (nearWater(x, y, 6)) {
                    // stone embankment with a lit top edge
                    set(ax, ay, isWater(x, y - 9) ? rgb(0x8d8a84) : SHORE);
                } else {
                    // Pokemon-style grass: a checker of two greens with little tufts
                    int c = ((ax / 2 + ay / 2) % 2 == 0) ? grass : blend(grass, grassDark, 0.35);
                    if ((ax * 7 + ay * 3) % 23 == 0) {
                        c = grassDark;
                    } else if ((ax * 7 + ay * 3) % 23 == 1) {
                        c = GRASS_LIGHT;
                    }
                    set(ax, ay, c);
                }
            }
        }
    }

    static boolean nearWaterAll(int x, int y, int d) {
        return isWater(x + d, y) && isWater(x - d, y) && isWater(x, y + d) && isWater(x, y - d);
    }

    /** a park: flowers, paths and lots of trees (cherry blossoms in Tokyo) */
    static void park(int x0, int y0, int x1, int y1, boolean flowers, boolean cherry) {
        for (int ay = (H - y1) / S; ay < (H - y0) / S; ay++) {
            for (int ax = x0 / S; ax < x1 / S; ax++) {
                if (freeArt(ax, ay) && flowers && (ax * 13 + ay * 7) % 41 == 0) {
                    flower(ax, ay, (ax + ay) % 3 == 0 ? rgb(0xf05a6e) : (ax + ay) % 3 == 1 ? rgb(0xffe066) : WHITE);
                }
            }
        }
        forest(x0, y0, x1, y1, cherry ? 0.5 : 0.0, 1300);
    }

    static void forest(int x0, int y0, int x1, int y1, double cherryShare, int spacing) {
        int count = (x1 - x0) * (y1 - y0) / spacing;
        for (int i = 0; i < count; i++) {
            int x = x0 + rnd.nextInt(Math.max(1, x1 - x0)), y = y0 + rnd.nextInt(Math.max(1, y1 - y0));
            int ax = x / S, ay = (H - y) / S;
            boolean room = true;
            for (int dy = -6; dy <= 3 && room; dy++) {
                for (int dx = -5; dx <= 5 && room; dx++) {
                    room = freeArt(ax + dx, ay + dy) && !isTreeOrBuilding(ax + dx, ay + dy);
                }
            }
            if (room) {
                tree(ax, ay, rnd.nextDouble() < cherryShare);
            }
        }
    }

    static boolean isTreeOrBuilding(int ax, int ay) {
        int c = get(ax, ay);
        return c == OUTLINE || c == TREE || c == TREE_DARK || c == rgb(0xf4a7c0) || c == rgb(0xd97a9a);
    }

    /** a Pokemon-style tree: round two-tone canopy with an outline, short trunk, shadow */
    static void tree(int ax, int ay, boolean cherry) {
        int main = cherry ? rgb(0xf4a7c0) : TREE, light = cherry ? rgb(0xfdd5e3) : TREE_LIGHT,
            dark = cherry ? rgb(0xd97a9a) : TREE_DARK;
        for (int dx = -4; dx <= 4; dx++) {
            shade(ax + dx + 1, ay + 2, 0.7);   // shadow on the ground
        }
        for (int dy = 0; dy <= 1; dy++) {
            set(ax, ay + dy, TRUNK);
            set(ax + 1, ay + dy, rgb(0x5e3820));
        }
        int cy = ay - 4;
        for (int dy = -4; dy <= 3; dy++) {
            for (int dx = -5; dx <= 5; dx++) {
                double d = (dx * dx) / 25.0 + (dy * dy) / 16.0;
                if (d <= 1.0) {
                    int c = d > 0.72 ? OUTLINE : (dy < -1 && dx < 1) ? light : (dy > 1 || dx > 2) ? dark : main;
                    set(ax + dx, cy + dy, c);
                }
            }
        }
        if (cherry) {
            set(ax - 2, cy - 1, WHITE);
            set(ax + 2, cy + 1, WHITE);
        }
    }

    static void flower(int ax, int ay, int color) {
        set(ax, ay, color);
        set(ax + 1, ay, blend(color, OUTLINE, 0.3));
        set(ax, ay + 1, GRASS_DARK);
    }

    // ================================================================== buildings

    /** fills an area with paving and the buildings of one style */
    static void district(int x0, int y0, int x1, int y1, String style) {
        int ax0 = x0 / S, ax1 = x1 / S, ay0 = (H - y1) / S, ay1 = (H - y0) / S;
        for (int ay = ay0; ay < ay1; ay++) {
            for (int ax = ax0; ax < ax1; ax++) {
                if (freeArt(ax, ay)) {
                    set(ax, ay, (ax % 8 == 0 || ay % 8 == 0) ? PAVE_LINE : PAVE);
                }
            }
        }
        int rowGap = 3;
        int ay = ay0 + 1;
        while (ay < ay1 - 6) {
            int rowHeight = rowHeight(style);
            int ax = ax0 + 1;
            while (ax < ax1 - 6) {
                int w = width(style);
                if (fits(ax, ay, w, rowHeight)) {
                    drawBuilding(ax, ay, w, rowHeight, style);
                    ax += w + 1 + rnd.nextInt(2);
                } else if (fits(ax, ay, 6, 6) && rnd.nextInt(3) == 0) {
                    tree(ax + 3, ay + 7, style.startsWith("tokyo") && rnd.nextInt(3) == 0);
                    ax += 8;
                } else {
                    ax += 2;
                }
            }
            ay += rowHeight + rowGap;
        }
    }

    static int rowHeight(String style) {
        switch (style) {
            case "tokyo-towers": case "nyc-towers": return 22 + rnd.nextInt(10);
            case "nyc-brownstones": return 17;
            case "tokyo-shops": return 13;
            default: return 14;
        }
    }

    static int width(String style) {
        switch (style) {
            case "tokyo-towers": case "nyc-towers": return 13 + rnd.nextInt(10);
            case "nyc-brownstones": return 9 + rnd.nextInt(2);
            case "tokyo-shops": return 12 + rnd.nextInt(6);
            default: return 13 + rnd.nextInt(5);
        }
    }

    static boolean fits(int ax, int ay, int w, int h) {
        for (int y = ay - 1; y <= ay + h + 1; y++) {
            for (int x = ax - 1; x <= ax + w + 1; x++) {
                if (!freeArt(x, y) || isTreeOrBuilding(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    static final int[] TOKYO_ROOFS = {rgb(0x4f6fa8), rgb(0x5d6b7a), rgb(0x3f5f8f), rgb(0x8a4b3c), rgb(0x6b7d5a)};
    static final int[] TOKYO_WALLS = {rgb(0xf3ecdc), rgb(0xe9e2d0), rgb(0xf6f1e6), rgb(0xe2d6bd)};
    static final int[] TOWER_GLASS = {rgb(0x9cc4dc), rgb(0xb8c6d2), rgb(0x8fb0c8), rgb(0xc9d1d8), rgb(0xa7b8c4)};
    static final int[] NEON = {rgb(0xff4fa3), rgb(0x3fe0ff), rgb(0xffe23f), rgb(0x8cff5a), rgb(0xff7a3f), rgb(0xb57cff)};
    static final int[] AWNINGS = {rgb(0xd9433b), rgb(0x2f8f6a), rgb(0x3b6fd9), rgb(0xe08a2e)};

    static void drawBuilding(int ax, int ay, int w, int h, String style) {
        switch (style) {
            case "tokyo-houses":
                house(ax, ay, w, h, TOKYO_ROOFS[rnd.nextInt(TOKYO_ROOFS.length)], TOKYO_WALLS[rnd.nextInt(TOKYO_WALLS.length)], false);
                break;
            case "tokyo-shops":
                house(ax, ay, w, h, TOKYO_ROOFS[rnd.nextInt(TOKYO_ROOFS.length)], TOKYO_WALLS[rnd.nextInt(TOKYO_WALLS.length)], true);
                break;
            case "tokyo-towers":
                tower(ax, ay, w, h, TOWER_GLASS[rnd.nextInt(TOWER_GLASS.length)], true, false);
                break;
            case "nyc-towers":
                tower(ax, ay, w, h, TOWER_GLASS[rnd.nextInt(TOWER_GLASS.length)], false, true);
                break;
            default:
                brownstone(ax, ay, w, h);
        }
    }

    /** a small house or shop: tiled roof on top, front wall with windows and a door below */
    static void house(int ax, int ay, int w, int h, int roof, int wall, boolean shop) {
        int rh = h * 5 / 11;
        dropShadow(ax, ay, w, h);
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < w; x++) {
                int c = roof;
                if (y == 0) {
                    c = blend(roof, WHITE, 0.3);            // ridge catches the light
                } else if (y % 2 == 0) {
                    c = blend(roof, OUTLINE, 0.25);         // rows of tiles
                } else if (x % 3 == 0) {
                    c = blend(roof, OUTLINE, 0.1);
                }
                set(ax + x, ay + y, c);
            }
        }
        // eave shadow, then the wall
        for (int x = 0; x < w; x++) {
            set(ax + x, ay + rh, blend(roof, OUTLINE, 0.55));
        }
        for (int y = rh + 1; y < h; y++) {
            for (int x = 0; x < w; x++) {
                set(ax + x, ay + y, x == w - 1 ? blend(wall, OUTLINE, 0.2) : wall);
            }
        }
        if (shop) {
            // striped awning and a big shop window
            int awning = AWNINGS[rnd.nextInt(AWNINGS.length)];
            for (int x = 0; x < w; x++) {
                set(ax + x, ay + rh + 1, x % 2 == 0 ? awning : WHITE);
                set(ax + x, ay + rh + 2, blend(awning, OUTLINE, 0.3));
            }
            for (int x = 1; x < w - 4; x++) {
                for (int y = rh + 3; y < h - 1; y++) {
                    set(ax + x, ay + y, y == rh + 3 ? GLASS : GLASS_DARK);
                }
            }
            door(ax + w - 3, ay + h - 4);
            // a hanging sign
            set(ax + 1, ay + rh - 1, NEON[rnd.nextInt(NEON.length)]);
            set(ax + 2, ay + rh - 1, NEON[rnd.nextInt(NEON.length)]);
        } else {
            for (int x = 1; x + 2 < w; x += 4) {
                if (Math.abs(x + 1 - w / 2) > 1) {
                    window(ax + x, ay + rh + 2);
                }
            }
            door(ax + w / 2 - 1, ay + h - 4);
        }
        outline(ax, ay, w, h);
    }

    /** a tall building seen from the front: flat roof (with neon or a water tower), window grid */
    static void tower(int ax, int ay, int w, int h, int glass, boolean neon, boolean waterTower) {
        int rh = 4;
        dropShadow(ax, ay, w, h);
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < w; x++) {
                set(ax + x, ay + y, y == 0 ? rgb(0xd7d3cb) : (x + y) % 5 == 0 ? rgb(0x8f8b84) : rgb(0xb3aea5));
            }
        }
        for (int x = 0; x < w; x++) {
            set(ax + x, ay + rh, rgb(0x6f6b65));
        }
        for (int y = rh + 1; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = glass;
                if (x == 0) {
                    c = blend(glass, WHITE, 0.35);                     // lit edge
                } else if (x == w - 1) {
                    c = blend(glass, OUTLINE, 0.35);                   // shaded edge
                } else if ((y - rh) % 3 == 0) {
                    c = blend(glass, OUTLINE, 0.28);                   // floor lines
                } else if (x % 3 == 0) {
                    c = blend(glass, OUTLINE, 0.12);                   // mullions
                } else if (rnd.nextInt(14) == 0) {
                    c = LIT;                                           // a lit window
                }
                set(ax + x, ay + y, c);
            }
        }
        // the lobby doors at street level
        for (int x = w / 2 - 2; x <= w / 2 + 1; x++) {
            set(ax + x, ay + h - 2, GLASS_DARK);
            set(ax + x, ay + h - 3, GLASS_DARK);
        }
        if (neon && w >= 10) {
            // vertical neon signs down the front and a billboard on the roof
            int c = NEON[rnd.nextInt(NEON.length)];
            for (int y = rh + 2; y < h - 4; y++) {
                set(ax + 1, ay + y, (y % 2 == 0) ? c : blend(c, WHITE, 0.4));
            }
            int b = NEON[rnd.nextInt(NEON.length)];
            for (int x = 2; x < w - 2; x++) {
                set(ax + x, ay + 1, b);
                set(ax + x, ay + 2, (x % 2 == 0) ? WHITE : b);
            }
        }
        if (waterTower && w >= 9 && rnd.nextInt(2) == 0) {
            waterTower(ax + 2 + rnd.nextInt(Math.max(1, w - 7)), ay);
        }
        outline(ax, ay, w, h);
    }

    /** a New York water tower on its stilts, poking above the roof */
    static void waterTower(int ax, int ay) {
        int wood = rgb(0x8a5a36);
        for (int y = -5; y <= -2; y++) {
            for (int x = 0; x < 4; x++) {
                set(ax + x, ay + y, x == 0 ? blend(wood, WHITE, 0.25) : y % 2 == 0 ? blend(wood, OUTLINE, 0.3) : wood);
            }
        }
        set(ax + 1, ay - 6, OUTLINE);
        set(ax + 2, ay - 6, OUTLINE);
        set(ax, ay - 1, OUTLINE);
        set(ax + 3, ay - 1, OUTLINE);
        set(ax, ay, OUTLINE);
        set(ax + 3, ay, OUTLINE);
    }

    /** a brownstone: flat roof, brown front with tall windows, a stoop and a fire escape */
    static void brownstone(int ax, int ay, int w, int h) {
        int brick = rnd.nextInt(3) == 0 ? rgb(0xa0583c) : rgb(0x8a5a3c);
        dropShadow(ax, ay, w, h);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < w; x++) {
                set(ax + x, ay + y, y == 0 ? rgb(0xa49c90) : rgb(0x7f786e));
            }
        }
        for (int x = 0; x < w; x++) {
            set(ax + x, ay + 3, rgb(0x5a4a3e));      // cornice
        }
        for (int y = 4; y < h; y++) {
            for (int x = 0; x < w; x++) {
                set(ax + x, ay + y, (y % 4 == 0) ? blend(brick, OUTLINE, 0.18) : x == w - 1 ? blend(brick, OUTLINE, 0.25) : brick);
            }
        }
        for (int y = 5; y < h - 5; y += 4) {
            for (int x = 1; x + 1 < w; x += 3) {
                set(ax + x, ay + y, GLASS);
                set(ax + x, ay + y + 1, GLASS_DARK);
            }
        }
        // the fire escape zig-zag
        for (int y = 5; y < h - 5; y++) {
            set(ax + w - 3, ay + y, ((y / 2) % 2 == 0) ? BLACK : rgb(0x3a3a40));
        }
        // stoop: steps up to the door
        door(ax + 1, ay + h - 4);
        for (int i = 0; i < 3; i++) {
            set(ax + 3 + i, ay + h - 1 - i, rgb(0xb8aa94));
        }
        if (rnd.nextInt(4) == 0) {
            waterTower(ax + 2, ay);
        }
        outline(ax, ay, w, h);
    }

    static void window(int ax, int ay) {
        set(ax, ay, GLASS);
        set(ax + 1, ay, WHITE);
        set(ax, ay + 1, GLASS_DARK);
        set(ax + 1, ay + 1, GLASS);
    }

    static void door(int ax, int ay) {
        for (int y = 0; y < 3; y++) {
            set(ax, ay + y, DOOR);
            set(ax + 1, ay + y, blend(DOOR, OUTLINE, 0.3));
        }
        set(ax + 1, ay + 1, LIT);
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

    static void dropShadow(int ax, int ay, int w, int h) {
        for (int y = 1; y <= h + 1; y++) {
            shade(ax + w + 1, ay + y, 0.65);
        }
        for (int x = 1; x <= w + 1; x++) {
            shade(ax + x, ay + h + 1, 0.65);
        }
    }

    // ================================================================== roads

    /** asphalt, curb, sidewalk, the fence line with street furniture, lane dashes */
    static void roads(int laneColor, boolean tokyo) {
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                int x = ax * S + 1, y = H - ay * S - 1;
                int d = roadDistance(x, y);
                boolean overWater = isWater(x, y) || nearWater(x, y, 6);
                if (d <= ROAD) {
                    set(ax, ay, d > ROAD - S ? CURB : ((ax * 5 + ay * 3) % 17 == 0 ? ASPHALT_SPECK : ASPHALT));
                    // a white edge line just inside the curb
                    if (d > ROAD - 3 * S && d <= ROAD - 2 * S && !overWater) {
                        set(ax, ay, rgb(0xd8d4ca));
                    }
                } else if (d <= ROAD + WALK && !overWater) {
                    set(ax, ay, (ax % 4 == 0 || ay % 4 == 0) ? WALK_B : WALK_A);
                } else if (d <= ROAD + WALK + S && !overWater) {
                    set(ax, ay, ((ax + ay) % 4 == 0) ? rgb(0xe8c547) : rgb(0x55514b));   // curb and bollards
                }
            }
        }
        // street furniture along the fence line: lamps, and vending machines or hydrants
        for (int i = 0; i < path.length - 1; i++) {
            int[] a = path[i], b = path[i + 1];
            int len = Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1]);
            int dirX = Integer.signum(b[0] - a[0]), dirY = Integer.signum(b[1] - a[1]);
            for (int t = ROAD + 40; t < len - ROAD - 40; t += 66) {
                for (int side : new int[] {-1, 1}) {
                    int x = a[0] + dirX * t + side * dirY * (ROAD + WALK + 8);
                    int y = a[1] + dirY * t - side * dirX * (ROAD + WALK + 8);
                    if (!free(x, y)) {
                        continue;
                    }
                    int ax = x / S, ay = (H - y) / S;
                    if ((t / 66) % 3 == 0) {
                        lamp(ax, ay);
                    } else if (tokyo && (t / 66) % 3 == 1) {
                        vending(ax, ay);
                    } else if (!tokyo && (t / 66) % 3 == 1) {
                        hydrant(ax, ay);
                    }
                }
            }
        }
        // dashed lane line down the middle of every straight
        for (int i = 0; i < path.length - 1; i++) {
            int[] a = path[i], b = path[i + 1];
            int len = Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1]);
            for (int t = ROAD + 30; t < len - ROAD - 30; t += S) {
                int x = a[0] + Integer.signum(b[0] - a[0]) * t, y = a[1] + Integer.signum(b[1] - a[1]) * t;
                if ((t / 18) % 2 == 0) {
                    set(x / S, (H - y) / S, laneColor);
                    if (!tokyo) {
                        // New York: a double yellow line
                        boolean vertical = a[0] == b[0];
                        set(x / S + (vertical ? 1 : 0), (H - y) / S + (vertical ? 0 : 1), laneColor);
                    }
                }
            }
            // a manhole now and then
            for (int t = ROAD + 90; t < len - ROAD - 30; t += 240) {
                int x = a[0] + Integer.signum(b[0] - a[0]) * t + 30, y = a[1] + Integer.signum(b[1] - a[1]) * t + 30;
                int ax = x / S, ay = (H - y) / S;
                if (roadDistance(x, y) < ROAD - 12 && !isWater(x, y)) {
                    set(ax, ay, rgb(0x3a3c42));
                    set(ax + 1, ay, rgb(0x5a5c63));
                    set(ax, ay + 1, rgb(0x5a5c63));
                    set(ax + 1, ay + 1, rgb(0x3a3c42));
                }
            }
        }
    }

    static void lamp(int ax, int ay) {
        for (int y = -5; y <= 0; y++) {
            set(ax, ay + y, rgb(0x3d3f46));
        }
        set(ax - 1, ay - 6, OUTLINE);
        set(ax, ay - 6, LIT);
        set(ax + 1, ay - 6, OUTLINE);
        shade(ax + 1, ay + 1, 0.7);
    }

    static void vending(int ax, int ay) {
        int c = NEON[rnd.nextInt(3)] == NEON[0] ? rgb(0xd9433b) : rgb(0x3b6fd9);
        for (int y = -4; y <= 0; y++) {
            for (int x = 0; x < 3; x++) {
                set(ax + x, ay + y, y == -4 ? WHITE : y == -2 ? GLASS : c);
            }
        }
        outline(ax, ay - 4, 3, 5);
    }

    static void hydrant(int ax, int ay) {
        set(ax, ay - 2, rgb(0xd9433b));
        set(ax - 1, ay - 1, rgb(0xa52f2a));
        set(ax, ay - 1, rgb(0xd9433b));
        set(ax + 1, ay - 1, rgb(0xa52f2a));
        set(ax, ay, rgb(0xa52f2a));
    }

    /** zebra crossings across the road just before every corner */
    static void crosswalks() {
        for (int i = 0; i < path.length - 1; i++) {
            int[] a = path[i], b = path[i + 1];
            boolean vertical = a[0] == b[0];
            int len = Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1]);
            if (len < 3 * ROAD + 60) {
                continue;
            }
            for (int end = 0; end < 2; end++) {
                int t = end == 0 ? ROAD + 6 : len - ROAD - 30;
                for (int k = 0; k < 24; k += S) {
                    for (int across = -ROAD + 12; across <= ROAD - 12; across += S) {
                        if (((across + ROAD) / 9) % 2 != 0) {
                            continue;
                        }
                        int x = vertical ? a[0] + across : a[0] + Integer.signum(b[0] - a[0]) * (t + k);
                        int y = vertical ? a[1] + Integer.signum(b[1] - a[1]) * (t + k) : a[1] + across;
                        if (!isWater(x, y) && !(Math.abs(x - 200) < ROAD && y > 250 && y < 340)) {
                            set(x / S, (H - y) / S, WHITE);
                        }
                    }
                }
            }
        }
    }

    /** the Shibuya scramble: stripes across all four sides and both diagonals */
    static void scramble(int cx, int cy) {
        for (int y = cy - ROAD + 6; y <= cy + ROAD - 6; y += S) {
            for (int x = cx - 70; x <= cx + 70; x += S) {
                int dx = x - cx, dy = y - cy;
                boolean diagonal = Math.abs(Math.abs(dx) - Math.abs(dy) * 70 / (ROAD - 6)) < 9;
                boolean side = Math.abs(dx) > 52;
                if ((diagonal || side) && (((side ? y : x + y) / 7) % 2 == 0)) {
                    set(x / S, (H - y) / S, WHITE);
                }
            }
        }
        // the big screens looking down on the crossing
        for (int[] sc : new int[][] {{870, 690}, {1060, 700}, {940, 560}}) {
            int ax = sc[0] / S, ay = (H - sc[1]) / S;
            for (int y = 0; y < 6; y++) {
                for (int x = 0; x < 10; x++) {
                    int c = NEON[(x / 3 + y / 2) % NEON.length];
                    set(ax + x, ay + y, (x == 0 || y == 0 || x == 9 || y == 5) ? OUTLINE : c);
                }
            }
        }
    }

    /** where the road crosses water: a deck with railings (and towers for New York's bridges) */
    static void bridges(int rail, int railDark, boolean suspension) {
        for (int ay = 0; ay < AH; ay++) {
            for (int ax = 0; ax < AW; ax++) {
                int x = ax * S + 1, y = H - ay * S - 1;
                if (!(isWater(x, y) || nearWater(x, y, 6))) {
                    continue;
                }
                int d = roadDistance(x, y);
                if (d > ROAD && d <= ROAD + WALK - S) {
                    set(ax, ay, rgb(0x9b958c));
                } else if (d > ROAD + WALK - S && d <= ROAD + WALK + S) {
                    set(ax, ay, ((ax + ay) % 3 == 0) ? railDark : rail);
                } else if (d > ROAD + WALK + S && d <= ROAD + WALK + 3 * S) {
                    shade(ax, ay, 0.7);
                }
            }
        }
        if (!suspension) {
            return;
        }
        // stone towers with arches where each bridge meets the river's middle
        for (int i = 0; i < path.length - 1; i++) {
            int[] a = path[i], b = path[i + 1];
            if (a[1] != b[1]) {
                continue;
            }
            for (int x = Math.min(a[0], b[0]); x <= Math.max(a[0], b[0]); x += S) {
                if (isWater(x, a[1] + ROAD + WALK + 20) && isWater(x - 60, a[1] + ROAD + WALK + 20)
                    && !isWater(x - 120, a[1] + ROAD + WALK + 20)) {
                    stoneTower(x / S, (H - a[1]) / S);
                    break;
                }
            }
        }
    }

    static void stoneTower(int ax, int cy) {
        int top = cy - (ROAD + WALK) / S - 10, bottom = cy + (ROAD + WALK) / S + 4;
        for (int y = top; y <= bottom; y++) {
            for (int x = ax - 4; x <= ax + 4; x++) {
                boolean arch = Math.abs(x - ax) <= 1 && (y > top + 3 && y < cy - 4 || y > cy + 4 && y < bottom - 2);
                int c = Math.abs(x - ax) == 4 || y == top || y == bottom ? OUTLINE
                    : arch ? rgb(0x4a4136) : x < ax ? rgb(0xd6c6a3) : rgb(0xb9a481);
                if (Math.abs(y - cy) > (ROAD - 4) / S || Math.abs(x - ax) == 4) {
                    set(x, y, c);
                }
            }
        }
        // cables fanning out from the tower tops
        for (int k = 4; k < 30; k += 2) {
            set(ax - k, top + 1 + k / 6, rgb(0x3d3a34));
            set(ax + k, top + 1 + k / 6, rgb(0x3d3a34));
            set(ax - k, bottom - 1 - k / 6, rgb(0x3d3a34));
            set(ax + k, bottom - 1 - k / 6, rgb(0x3d3a34));
        }
    }

    static void startLine() {
        for (int y = 300; y < 324; y += S) {
            for (int x = 200 - ROAD + S; x < 200 + ROAD - S; x += S) {
                int cx = (x - (200 - ROAD)) / 6, cy = (y - 300) / 6;
                set(x / S, (H - 1 - y) / S, (cx + cy) % 2 == 0 ? WHITE : rgb(0x1b1b1f));
            }
        }
    }

    // ================================================================== Tokyo landmarks

    /** a shrine: a path of red torii gates with lanterns up to a shrine with a big roof */
    static void shrine(int x, int y) {
        int ax = x / S, ay = (H - y) / S;
        // gravel path
        for (int py = ay - 22; py <= ay + 30; py++) {
            for (int px = ax - 3; px <= ax + 3; px++) {
                set(px, py, ((px + py) % 3 == 0) ? rgb(0xd9d0bd) : rgb(0xe7dfcd));
            }
        }
        // torii gates along the path
        for (int gy = ay - 4; gy <= ay + 26; gy += 8) {
            for (int px = ax - 6; px <= ax + 6; px++) {
                set(px, gy, rgb(0xd8342c));
                set(px, gy - 1, BLACK);
            }
            for (int py = gy + 1; py <= gy + 4; py++) {
                set(ax - 5, py, rgb(0xb22a24));
                set(ax + 5, py, rgb(0xb22a24));
            }
            set(ax - 7, gy + 2, rgb(0xff5a4a));   // lanterns
            set(ax + 7, gy + 2, rgb(0xff5a4a));
        }
        // the shrine hall: wide dark roof with upturned corners, red pillars
        int hx = ax - 12, hy = ay - 40;
        for (int ry = 0; ry < 10; ry++) {
            int inset = Math.max(0, 3 - ry);
            for (int rx = inset; rx < 24 - inset; rx++) {
                set(hx + rx, hy + ry, ry == 0 ? rgb(0x7f8f86) : ry % 2 == 0 ? rgb(0x3f4f48) : rgb(0x52645b));
            }
        }
        set(hx, hy + 9, rgb(0x3f4f48));
        set(hx + 23, hy + 9, rgb(0x3f4f48));
        for (int ry = 10; ry < 18; ry++) {
            for (int rx = 2; rx < 22; rx++) {
                set(hx + rx, hy + ry, (rx % 5 == 2) ? rgb(0xc8322a) : rgb(0xf1e7d0));
            }
        }
        for (int rx = 9; rx < 15; rx++) {
            for (int ry = 13; ry < 18; ry++) {
                set(hx + rx, hy + ry, rgb(0x6a3a22));
            }
        }
        outline(hx + 2, hy + 10, 20, 8);
        // the big rope bell and a gold crest
        set(hx + 12, hy + 4, rgb(0xf2c84b));
    }

    /** Tokyo Tower: an orange and white lattice tower rising up the picture */
    static void tokyoTower(int x, int y) {
        int ax = x / S, base = (H - y) / S, height = 78;
        for (int dy = 0; dy < height; dy++) {
            double t = dy / (double) height;
            int half = (int) Math.round(16 * Math.pow(1 - t, 1.6)) + 1;
            int band = (dy / 9) % 2;
            for (int dx = -half; dx <= half; dx++) {
                boolean edge = Math.abs(dx) == half;
                boolean lattice = ((dx + dy) % 4 == 0) || ((dx - dy) % 4 == 0);
                int c = edge ? OUTLINE : band == 0 ? (lattice ? rgb(0xb83a1e) : rgb(0xf05a28)) : (lattice ? rgb(0xc9c4ba) : WHITE);
                if (edge || lattice || half < 6 || dy > height - 30) {
                    set(ax + dx, base - dy, c);
                }
            }
            // the two observation decks
            if (dy == 26 || dy == 46) {
                for (int dx = -half - 3; dx <= half + 3; dx++) {
                    set(ax + dx, base - dy, OUTLINE);
                    set(ax + dx, base - dy - 1, rgb(0xd8d3c8));
                    set(ax + dx, base - dy - 2, GLASS);
                    set(ax + dx, base - dy - 3, OUTLINE);
                }
            }
        }
        for (int dy = height; dy < height + 10; dy++) {
            set(ax, base - dy, dy % 2 == 0 ? rgb(0xf05a28) : WHITE);   // the antenna
        }
        for (int dx = -18; dx <= 18; dx++) {
            shade(ax + dx + 3, base + 1, 0.6);
        }
    }

    /** a boat on the water: a yakatabune with lanterns (Tokyo) or a ferry with a wake */
    static void boat(int x, int y, int color, boolean lanterns) {
        if (!isWater(x, y)) {
            return;
        }
        int ax = x / S, ay = (H - y) / S;
        for (int k = 1; k <= 6; k++) {
            set(ax - k, ay + 2 + (k % 2), SPARKLE);          // the wake
        }
        for (int yy = 0; yy < 5; yy++) {
            for (int xx = 0; xx < 16; xx++) {
                int c = yy == 0 ? blend(color, OUTLINE, 0.4) : yy == 4 ? blend(color, OUTLINE, 0.55)
                    : (xx % 3 == 1 && yy == 2) ? (lanterns ? rgb(0xffd36b) : GLASS) : color;
                set(ax + xx, ay + yy, c);
            }
        }
        outline(ax, ay, 16, 5);
        if (lanterns) {
            for (int xx = 2; xx < 16; xx += 4) {
                set(ax + xx, ay - 2, rgb(0xff5a4a));
            }
        } else {
            for (int xx = 5; xx < 11; xx++) {
                set(ax + xx, ay - 2, WHITE);                   // the cabin on deck
                set(ax + xx, ay - 1, GLASS_DARK);
            }
        }
    }

    /** a Yamanote line train on its tracks: silver cars with the green stripe */
    static void train(int x0, int y, int x1) {
        int ay = (H - y) / S;
        for (int ax = x0 / S; ax <= x1 / S; ax++) {
            if (ax % 3 == 0) {
                for (int k = -3; k <= 3; k++) {
                    set(ax, ay + k, rgb(0x6b4a32));             // sleepers
                }
            }
            set(ax, ay - 2, rgb(0x8d8f96));
            set(ax, ay + 2, rgb(0x8d8f96));
        }
        int cars = 4, carW = 22;
        int start = (x0 + x1) / 2 / S - cars * (carW + 1) / 2;
        for (int c = 0; c < cars; c++) {
            int cx = start + c * (carW + 1);
            for (int yy = -4; yy <= 3; yy++) {
                for (int xx = 0; xx < carW; xx++) {
                    int col = yy == -4 ? rgb(0xc9ccd2) : yy == 0 ? rgb(0x3fae49) : (yy == -2 && xx % 4 != 0) ? GLASS_DARK
                        : yy == 3 ? rgb(0x7a7d84) : rgb(0xe3e5e9);
                    set(cx + xx, ay + yy, col);
                }
            }
            outline(cx, ay - 4, carW, 8);
            for (int xx = 0; xx < carW; xx++) {
                shade(cx + xx + 1, ay + 5, 0.7);
            }
        }
    }

    /** little people walking around the plazas */
    static void people(int count) {
        int[] clothes = {rgb(0xd9433b), rgb(0x3b6fd9), rgb(0xf2c84b), rgb(0x3fae49), rgb(0xffffff), rgb(0x2a2a30), rgb(0xff8ac0)};
        int placed = 0;
        for (int tries = 0; tries < count * 40 && placed < count; tries++) {
            int ax = rnd.nextInt(AW), ay = 1 + rnd.nextInt(AH - 2);
            if (get(ax, ay) == PAVE && get(ax, ay - 1) == PAVE && get(ax, ay + 1) == PAVE && freeArt(ax, ay)) {
                set(ax, ay - 1, rgb(0xf0c8a0));                // head
                set(ax, ay, clothes[rnd.nextInt(clothes.length)]);
                shade(ax + 1, ay + 1, 0.7);
                placed++;
            }
        }
    }

    // ================================================================== New York landmarks

    static void centralPark(int x0, int y0, int x1, int y1) {
        // a stone wall around it
        for (int ay = (H - y1) / S; ay <= (H - y0) / S; ay++) {
            for (int ax = x0 / S; ax <= x1 / S; ax++) {
                if (!freeArt(ax, ay)) {
                    continue;
                }
                boolean edge = ay == (H - y1) / S || ay == (H - y0) / S || ax == x0 / S || ax == x1 / S;
                if (edge) {
                    set(ax, ay, rgb(0x8f877a));
                }
            }
        }
        // the lake, with the Bow Bridge and rowboats
        int lx = (x0 + x1) / 2 / S + 20, ly = (H - (y0 + y1) / 2) / S;
        for (int ay = ly - 14; ay <= ly + 14; ay++) {
            for (int ax = lx - 40; ax <= lx + 40; ax++) {
                double d = Math.pow((ax - lx) / 40.0, 2) + Math.pow((ay - ly) / 14.0, 2)
                    + 0.15 * Math.sin(ax / 4.0) * Math.cos(ay / 3.0);
                if (d < 1) {
                    set(ax, ay, d > 0.86 ? rgb(0x8d8a84) : ((ax * 5 + ay * 7) % 23 == 0) ? SPARKLE : WATER);
                }
            }
        }
        for (int ax = lx - 6; ax <= lx + 6; ax++) {
            set(ax, ly - 1, OUTLINE);
            set(ax, ly, rgb(0xe9e2d2));
            set(ax, ly + 1, OUTLINE);
        }
        for (int[] b : new int[][] {{-26, -6}, {20, 5}, {-14, 7}}) {
            set(lx + b[0], ly + b[1], rgb(0xd9433b));
            set(lx + b[0] + 1, ly + b[1], rgb(0xa52f2a));
        }
        // Bethesda Fountain on its terrace, west of the lake
        int fx = lx - 62, fy = ly + 2;
        for (int ay = fy - 8; ay <= fy + 8; ay++) {
            for (int ax = fx - 12; ax <= fx + 12; ax++) {
                set(ax, ay, ((ax + ay) % 4 == 0) ? rgb(0xc9b99a) : rgb(0xddd0b4));
            }
        }
        disc(fx, fy, 5, OUTLINE);
        disc(fx, fy, 4, WATER);
        disc(fx, fy, 1, rgb(0x5f8f7a));
        set(fx, fy - 2, rgb(0x6faa8c));
        // winding paths
        for (int t = 0; t < 700; t++) {
            int px = x0 / S + 4 + t * ((x1 - x0) / S - 8) / 700;
            int py = (H - y0) / S - 10 - (int) (8 * Math.sin(t / 40.0)) - 6;
            for (int k = 0; k < 2; k++) {
                if (freeArt(px, py + k) && get(px, py + k) != WATER) {
                    set(px, py + k, rgb(0xe5d8b8));
                }
            }
            int qy = (H - y1) / S + 8 + (int) (5 * Math.cos(t / 33.0));
            if (freeArt(px, qy) && get(px, qy) != WATER) {
                set(px, qy, rgb(0xe5d8b8));
            }
        }
        // a ball field in the east
        int bx = x1 / S - 30, by = (H - y0) / S - 24;
        for (int ay = by - 10; ay <= by + 10; ay++) {
            for (int ax = bx - 10; ax <= bx + 10; ax++) {
                if (Math.abs(ax - bx) + Math.abs(ay - by) <= 10) {
                    set(ax, ay, Math.abs(ax - bx) + Math.abs(ay - by) >= 8 ? rgb(0xc98f5a) : GRASS_LIGHT);
                }
            }
        }
        reserveArt(lx - 42, ly - 16, lx + 42, ly + 16);
        reserveArt(fx - 13, fy - 9, fx + 13, fy + 9);
        reserveArt(bx - 11, by - 11, bx + 11, by + 11);
        forest(x0, y0, x1, y1, 0, 900);
    }

    static void reserveArt(int ax0, int ay0, int ax1, int ay1) {
        for (int ay = ay0; ay <= ay1; ay++) {
            for (int ax = ax0; ax <= ax1; ax++) {
                if (ax >= 0 && ay >= 0 && ax < AW && ay < AH) {
                    reserved[ay * AW + ax] = true;
                }
            }
        }
    }

    /** Times Square: billboards everywhere, the red TKTS steps, a crowd */
    static void timesSquare(int x0, int y0, int x1, int y1) {
        int ax0 = x0 / S, ax1 = x1 / S, ay0 = (H - y1) / S, ay1 = (H - y0) / S;
        for (int ay = ay0; ay <= ay1; ay++) {
            for (int ax = ax0; ax <= ax1; ax++) {
                if (freeArt(ax, ay)) {
                    set(ax, ay, ((ax + ay) % 7 == 0) ? rgb(0xa79f92) : rgb(0xc4bcae));
                }
            }
        }
        // buildings around the square, every one covered in screens
        for (int ay = ay0 + 2; ay < ay1 - 20; ay += 28) {
            for (int side = 0; side < 2; side++) {
                int ax = side == 0 ? ax0 + 4 : ax1 - 25;
                if (!fits(ax, ay, 20, 24)) {
                    continue;
                }
                tower(ax, ay, 20, 24, rgb(0x8f98a6), false, false);
                for (int b = 0; b < 3; b++) {
                    int c = NEON[rnd.nextInt(NEON.length)];
                    int by = ay + 6 + b * 6;
                    for (int y = 0; y < 5; y++) {
                        for (int x = 2; x < 18; x++) {
                            set(ax + x, by + y, (y == 0 || y == 4) ? OUTLINE : ((x + y) % 5 == 0 ? WHITE : c));
                        }
                    }
                }
            }
        }
        // the red steps in the middle and the crowd
        int sx = (ax0 + ax1) / 2, sy = (ay0 + ay1) / 2;
        for (int y = 0; y < 12; y++) {
            for (int x = -7; x <= 7; x++) {
                set(sx + x, sy + y, y % 2 == 0 ? rgb(0xd8342c) : rgb(0xa8241f));
            }
        }
        outline(sx - 7, sy, 15, 12);
        for (int i = 0; i < 140; i++) {
            int px = ax0 + 4 + rnd.nextInt(Math.max(1, ax1 - ax0 - 8)), py = ay0 + rnd.nextInt(Math.max(1, ay1 - ay0));
            if (freeArt(px, py) && !isTreeOrBuilding(px, py) && get(px, py) != OUTLINE) {
                int c = NEON[rnd.nextInt(NEON.length)];
                set(px, py, c);
                set(px, py + 1, rgb(0x3a3a48));
            }
        }
        // a hot dog cart
        set(sx + 11, sy + 4, rgb(0xd9d9d9));
        set(sx + 12, sy + 4, rgb(0xf2c84b));
        set(sx + 11, sy + 3, rgb(0xd9433b));
        set(sx + 12, sy + 3, rgb(0x3b6fd9));
    }

    /** the Empire State Building: stepped tower and spire rising up the picture */
    static void empireState(int x, int y) {
        int ax = x / S, base = (H - y) / S;
        int[][] tiers = {{26, 34}, {18, 22}, {12, 16}, {6, 8}};
        int top = base;
        for (int[] tier : tiers) {
            int half = tier[0] / 2, h = tier[1];
            for (int dy = 0; dy < h; dy++) {
                for (int dx = -half; dx <= half; dx++) {
                    boolean edge = Math.abs(dx) == half || dy == h - 1;
                    int c = edge ? OUTLINE : (dx == -half + 1) ? rgb(0xe8e2d4)
                        : ((dx + 64) % 3 == 0) ? rgb(0x8f8a80) : ((dy % 3 == 0) ? rgb(0xb7b0a2) : rgb(0xcfc8b8));
                    if (!edge && rnd.nextInt(18) == 0) {
                        c = LIT;
                    }
                    set(ax + dx, top - dy, c);
                }
            }
            top -= h;
        }
        for (int dy = 0; dy < 14; dy++) {
            set(ax, top - dy, dy < 4 ? rgb(0xcfc8b8) : rgb(0x9aa0a8));   // the spire
        }
        for (int dx = -14; dx <= 14; dx++) {
            shade(ax + dx + 3, base + 1, 0.6);
        }
    }

    /** the Flatiron: a wedge-shaped building */
    static void flatiron(int x, int y) {
        int ax = x / S, ay = (H - y) / S;
        for (int dy = 0; dy < 22; dy++) {
            int w = 2 + dy * 12 / 22;
            for (int dx = 0; dx <= w; dx++) {
                int c = dx == 0 || dx == w || dy == 21 ? OUTLINE : (dy % 3 == 0 ? rgb(0xbfae8f) : rgb(0xdccaa6));
                set(ax + dx, ay + dy, c);
            }
        }
    }

    /** the Statue of Liberty on her island in the harbor */
    static void liberty(int x, int y) {
        int ax = x / S, ay = (H - y) / S;
        for (int dy = -9; dy <= 9; dy++) {
            for (int dx = -16; dx <= 16; dx++) {
                double d = dx * dx / 256.0 + dy * dy / 81.0;
                if (d < 1) {
                    set(ax + dx, ay + dy, d > 0.8 ? SHORE : ((dx + dy) % 5 == 0 ? GRASS_DARK : GRASS));
                }
            }
        }
        // the star-shaped fort and the pedestal
        for (int dy = -5; dy <= 5; dy++) {
            for (int dx = -5; dx <= 5; dx++) {
                if (Math.abs(dx) + Math.abs(dy) <= 6) {
                    set(ax + dx, ay + dy, Math.abs(dx) + Math.abs(dy) == 6 ? OUTLINE : rgb(0xc9bfa9));
                }
            }
        }
        // the pedestal, then the statue in green copper raising her torch
        for (int dy = -9; dy <= 0; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                set(ax + dx, ay + dy, Math.abs(dx) == 3 || dy == -9 ? OUTLINE : dx < 0 ? rgb(0xd8ceb6) : rgb(0xb9ae96));
            }
        }
        int green = rgb(0x6fb59a), dark = rgb(0x4f8f78);
        for (int dy = -21; dy <= -10; dy++) {
            int half = dy < -18 ? 1 : 2;
            for (int dx = -half; dx <= half; dx++) {
                set(ax + dx, ay + dy, dx > 0 ? dark : green);
            }
        }
        for (int dy = -26; dy <= -19; dy++) {
            set(ax + 2, ay + dy, green);                 // her raised arm
        }
        set(ax + 2, ay - 27, rgb(0xffd23f));             // the torch flame
        set(ax + 2, ay - 28, rgb(0xff8a3f));
        set(ax + 1, ay - 27, OUTLINE);
        set(ax + 3, ay - 27, OUTLINE);
        set(ax - 2, ay - 15, rgb(0x5f9f88));             // the tablet
        set(ax - 3, ay - 15, rgb(0x5f9f88));
        for (int dx = -2; dx <= 2; dx++) {
            set(ax + dx, ay - 23, green);                 // crown spikes
        }
        set(ax - 1, ay - 22, green);
        set(ax, ay - 22, green);
        set(ax + 1, ay - 22, green);
        for (int dx = -4; dx <= 4; dx++) {
            shade(ax + dx + 3, ay + 1, 0.6);
        }
    }

    // ================================================================== car sprites

    // a boxy little kei car, mint with a white roof (k outline, m body, w roof, b glass, y lights, r tail)
    static final String[] KEI = {
        "...kkkkkkkk...",
        "..kymmmmmmyk..",
        ".kmmmmmmmmmmk.",
        ".kmmmmmmmmmmk.",
        ".kmbbbbbbbbmk.",
        ".kmbbbbbbbbmk.",
        "kkmbbbbbbbbmkk",
        ".kmwwwwwwwwmk.",
        ".kmwwwwwwwwmk.",
        ".kmwwwwwwwwmk.",
        ".kmwwwwwwwwmk.",
        ".kmwwwwwwwwmk.",
        ".kmwwwwwwwwmk.",
        ".kmwwwwwwwwmk.",
        ".kmwwwwwwwwmk.",
        ".kmwwwwwwwwmk.",
        ".kmwwwwwwwwmk.",
        ".kmwwwwwwwwmk.",
        ".kmbbbbbbbbmk.",
        ".kmbbbbbbbbmk.",
        ".kmmmmmmmmmmk.",
        ".kmmmmmmmmmmk.",
        ".krmmmmmmmmrk.",
        "..kkkkkkkkkk..",
        "..............",
        "..............",
        "..............",
        "..............",
    };
    static final String KEI_LETTERS = "kmwbyr";
    static final int[] KEI_COLORS = {rgb(0x1c1c22), rgb(0x8fdcc2), rgb(0xf6f6f3), rgb(0x23262d), rgb(0xfff0b0), rgb(0xd23a3a)};

    static final String[] CAB = {
        "...kkkkkkkk...",
        "..kybbbbbbyk..",
        ".kbbbbbbbbbbk.",
        ".kbbbbbbbbbbk.",
        ".kbbbbbbbbbbk.",
        ".kggggggggggk.",
        "kkgwwwwwwwwgkk",
        ".kgwwwwwwwwgk.",
        ".kgwwwwwwwwgk.",
        ".kbbbbbbbbbbk.",
        ".kbbbbbbbbbbk.",
        ".kbbbllllbbbk.",
        ".kbbbllllbbbk.",
        ".kbbbbbbbbbbk.",
        ".kbbbbbbbbbbk.",
        ".kcbcbcbcbcbk.",
        ".kbcbcbcbcbck.",
        ".kbbbbbbbbbbk.",
        ".kgwwwwwwwwgk.",
        ".kgwwwwwwwwgk.",
        ".kggggggggggk.",
        ".kbbbbbbbbbbk.",
        ".kbbbbbbbbbbk.",
        ".kbbbbbbbbbbk.",
        ".kbbbbbbbbbbk.",
        "..krbbbbbbrk..",
        "...kkkkkkkk...",
        "..............",
    };
    // k outline, b body, w glass, g trim, l roof light, c checker, y headlight, r tail light
    static final String CAB_LETTERS = "kbwglcyr";
    static final int[] CAB_COLORS = {rgb(0x1c1c22), rgb(0xf7c51e), rgb(0x2a2d35), rgb(0xc99a12), rgb(0xfdfbf0), rgb(0x1c1c22), rgb(0xfff0b0), rgb(0xd23a3a)};

    /**
     * @param rows 28 rows of 14 letters, front of the car at the top ('.' = see-through)
     * @param letters which letter is which color, in the same order as colors
     */
    static BufferedImage sprite(String[] rows, String letters, int[] colors) {
        BufferedImage img = new BufferedImage(14, 28, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 28; y++) {
            String row = rows[y];
            if (row.length() != 14) {
                throw new IllegalStateException("sprite row " + y + " is " + row.length() + " wide: " + row);
            }
            for (int x = 0; x < 14; x++) {
                char ch = row.charAt(x);
                int idx = letters.indexOf(ch);
                img.setRGB(x, y, idx < 0 ? 0 : colors[idx]);
            }
        }
        return img;
    }

    // ================================================================== helpers

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
        if (ax < 0 || ay < 0 || ax >= AW || ay >= AH) {
            return 0;
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

    static int blend(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }
}
