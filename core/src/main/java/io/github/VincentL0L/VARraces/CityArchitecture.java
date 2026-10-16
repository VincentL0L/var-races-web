package io.github.VincentL0L.VARraces;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * The buildings of each city in its own architecture, for the first-person view, in the
 * game's chunky 16-bit pixel style (every facade is drawn as 8 x 8 pixel window bays).
 *
 * Paris gets cream Haussmann limestone with iron balconies under grey zinc mansards and
 * dormers; London, brown brick with white sash windows under steep slate roofs and
 * chimney stacks; New York, red brick with fire escapes and wooden water towers, and
 * limestone Art Deco towers with stepped crowns and spires; Chicago, brick and dark steel
 * skyscrapers; Tokyo, tiled blocks with ribbon windows, glass towers, rooftop plant and
 * neon signs; Taipei, concrete flats with caged windows, AC units and water tanks; San
 * Francisco, pastel Victorians with bay windows below the downtown glass.
 *
 * The height map says which 12 x 12 patches are buildings; neighbouring patches of the
 * same height are one building, which gets one style, one colour and its roof features.
 * Every building gets a cornice that sticks out along its roofline and stone bands between
 * floors where the style has them, so nothing is a plain box.
 */
public class CityArchitecture {
    private static final int CELL = 12;
    /** one window bay is this wide, one storey this tall; a facade tile is 4 x 4 of them */
    private static final float BAY = 16f, STOREY = 12f;

    /** the facade styles */
    private static final int GENERIC = 0, GLASS = 1, DECO = 2, BRICK = 3, GEORGIAN = 4, HAUSSMANN = 5,
        TOKYO = 6, TAIPEI = 7, VICTORIAN = 8, STEEL = 9, STYLES = 10;
    /** how much each style takes its building's own colour (0 = the colours are drawn in) */
    private static final float[] TINT = {0.65f, 0.3f, 0.12f, 0.15f, 0.1f, 0.08f, 0.4f, 0.25f, 1f, 0f};
    /** the colour of a style's stonework / trim, for its cornices, bands, crowns */
    private static final Color[] STONE = {
        new Color(0.8f, 0.8f, 0.78f, 1f), new Color(0.78f, 0.82f, 0.86f, 1f), new Color(0.93f, 0.89f, 0.8f, 1f),
        new Color(0.88f, 0.84f, 0.76f, 1f), new Color(0.95f, 0.94f, 0.9f, 1f), new Color(0.95f, 0.91f, 0.8f, 1f),
        new Color(0.85f, 0.85f, 0.84f, 1f), new Color(0.7f, 0.7f, 0.68f, 1f), new Color(0.98f, 0.98f, 0.96f, 1f),
        new Color(0.2f, 0.2f, 0.23f, 1f)};

    private final Texture[] facades = new Texture[STYLES];
    private final Texture plain;
    private final Map<Texture, List<Mesh>> meshes = new HashMap<>();
    private final Scenery3D.Builder[] facadeB = new Scenery3D.Builder[STYLES];
    private final Scenery3D.Builder solid;
    private final String city;

    /** a building: its cells, height and look */
    private static final class Building {
        final List<int[]> cells = new ArrayList<>();
        int height, style, seed;
        Color paint;
        boolean tall;
    }

    private int[][] height, owner;
    private int gw, gh;
    private float worldH;
    private final List<Building> buildings = new ArrayList<>();

    /**
     * @param height patch heights, kind 2 = building   @param kind 0 flat 1 tree 2 building
     * @param roofColour each building patch's colour, seen from above
     * @param skip patches to leave alone (a landmark is built there)
     */
    public CityArchitecture(TrackMap map, int[][] height, int[][] kind, Color[][] roofColour, boolean[][] skip, float worldH) {
        this.city = map.id;
        this.height = height;
        this.worldH = worldH;
        gw = height.length;
        gh = height[0].length;
        Pixmap p = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        p.setColor(Color.WHITE);
        p.fill();
        plain = new Texture(p);
        p.dispose();
        solid = new Scenery3D.Builder(list(plain));
        for (int s = 0; s < STYLES; s++) {
            facades[s] = s == GENERIC ? Scenery3D.makeFacade() : style(s);
            facadeB[s] = new Scenery3D.Builder(list(facades[s]));
        }
        // group neighbouring building patches of the same height into buildings
        owner = new int[gw][gh];
        for (int[] row : owner) {
            java.util.Arrays.fill(row, -1);
        }
        for (int x = 0; x < gw; x++) {
            for (int y = 0; y < gh; y++) {
                if (kind[x][y] != 2 || height[x][y] == 0 || owner[x][y] >= 0 || skip[x][y]) {
                    continue;
                }
                Building b = new Building();
                b.height = height[x][y];
                java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
                q.add(new int[] {x, y});
                owner[x][y] = buildings.size();
                while (!q.isEmpty()) {
                    int[] c = q.poll();
                    b.cells.add(c);
                    int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] d : dirs) {
                        int nx = c[0] + d[0], ny = c[1] + d[1];
                        if (nx >= 0 && ny >= 0 && nx < gw && ny < gh && owner[nx][ny] < 0 && kind[nx][ny] == 2
                                && height[nx][ny] == b.height && !skip[nx][ny]) {
                            owner[nx][ny] = buildings.size();
                            q.add(new int[] {nx, ny});
                        }
                    }
                }
                b.seed = x * 73856093 ^ y * 19349663;
                b.tall = b.height >= 52;
                b.style = styleFor(b);
                Color roof = roofColour[x][y];
                b.paint = b.style == VICTORIAN ? pastel(b.seed) : new Color(Color.WHITE).lerp(roof, TINT[b.style]);
                buildings.add(b);
            }
        }
        for (int i = 0; i < buildings.size(); i++) {
            build(i, buildings.get(i));
        }
        solid.finish();
        for (Scenery3D.Builder fb : facadeB) {
            fb.finish();
        }
    }

    private List<Mesh> list(Texture t) {
        List<Mesh> l = new ArrayList<>();
        meshes.put(t, l);
        return l;
    }

    /** which architecture a building gets in this city */
    private int styleFor(Building b) {
        boolean tall = b.tall, big = b.cells.size() >= 10;
        int r = Math.floorMod(b.seed >> 5, 10);
        switch (city) {
            case TrackMap.PARIS:
                return HAUSSMANN;
            case TrackMap.LONDON:
                return tall && big ? GLASS : GEORGIAN;
            case TrackMap.NEW_YORK:
                return tall ? (r < 6 ? DECO : GLASS) : BRICK;
            case TrackMap.CHICAGO:
                return tall ? (r < 6 ? STEEL : DECO) : BRICK;
            case TrackMap.TOKYO:
                return tall ? GLASS : TOKYO;
            case TrackMap.TAIPEI:
                return tall && r < 5 ? GLASS : TAIPEI;
            case TrackMap.SAN_FRANCISCO:
                return tall ? GLASS : VICTORIAN;
            case TrackMap.RUSH_HOUR:
            case TrackMap.ROOFTOP:
                return tall ? (r < 5 ? GLASS : STEEL) : (r < 5 ? BRICK : GENERIC);
            default:
                return GENERIC;
        }
    }

    private static Color pastel(int seed) {
        Color[] p = {new Color(0.98f, 0.72f, 0.78f, 1f), new Color(0.7f, 0.9f, 0.8f, 1f), new Color(0.82f, 0.74f, 0.95f, 1f),
            new Color(0.98f, 0.9f, 0.6f, 1f), new Color(0.65f, 0.82f, 0.98f, 1f), new Color(0.98f, 0.8f, 0.62f, 1f)};
        return p[Math.floorMod(seed >> 7, p.length)];
    }

    // ---------------------------------------------------------------- one building

    private void build(int id, Building b) {
        int h = b.height;
        boolean mansard = b.style == HAUSSMANN || b.style == GEORGIAN;
        float slopeH = b.style == HAUSSMANN ? 9f : 11f, inset = b.style == HAUSSMANN ? 4f : 6f;
        Color stone = new Color(STONE[b.style]);
        if (b.style == GENERIC) {
            stone.set(b.paint).mul(0.85f, 0.85f, 0.85f, 1f);
        }
        Scenery3D.Builder fb = facadeB[b.style];
        for (int[] c : b.cells) {
            int gx = c[0], gy = c[1];
            float x0 = gx * CELL, x1 = x0 + CELL, y1 = worldH - gy * CELL, y0 = y1 - CELL;
            // the four sides: (neighbour, the wall's two ends, which way it faces, its shade)
            int[][] n = {{gx + 1, gy}, {gx - 1, gy}, {gx, gy - 1}, {gx, gy + 1}};
            float[][] ends = {{x1, y0, x1, y1}, {x0, y1, x0, y0}, {x1, y1, x0, y1}, {x0, y0, x1, y0}};
            float[][] normal = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            float[] shade = {0.92f, 0.7f, 1f, 0.62f};
            for (int s = 0; s < 4; s++) {
                int low = lowAt(n[s][0], n[s][1]);
                if (low >= h) {
                    continue;
                }
                float[] e = ends[s];
                float ox = normal[s][0], oy = normal[s][1];
                Color wallColour = new Color(b.paint).mul(shade[s], shade[s], shade[s], 1f);
                facadeWall(fb, e[0], e[1], e[2], e[3], low, h, wallColour);
                // the roofline: a projecting cornice (a mansard roof rises behind it)
                cornice(e, ox, oy, h, b.style == GLASS || b.style == STEEL ? 0.8f : 1.8f,
                    b.style == GLASS || b.style == STEEL ? 1.2f : 2.2f, stone, shade[s]);
                // stone bands between floors on the masonry styles
                if (b.style == BRICK || b.style == HAUSSMANN || b.style == DECO || b.style == GEORGIAN) {
                    for (float z = Math.max(low, 0) + STOREY * 3; z < h - 6; z += STOREY * 3) {
                        band(e, ox, oy, z, 0.7f, 1.1f, stone, shade[s]);
                    }
                }
                if (low == 0) {
                    band(e, ox, oy, 0f, 0.5f, 2.2f, new Color(stone).mul(0.6f, 0.6f, 0.6f, 1f), shade[s]);   // the plinth
                }
                if (mansard) {
                    slope(e, ox, oy, h, slopeH, inset, b.style == HAUSSMANN ? new Color(0.5f, 0.53f, 0.58f, 1f)
                        : new Color(0.36f, 0.38f, 0.43f, 1f), shade[s]);
                    // dormer windows poking out of the slope
                    if (b.style == HAUSSMANN && Math.floorMod(gx + gy, 2) == 0) {
                        dormer(e, ox, oy, h, inset);
                    }
                }
                int seed = gx * 31 + gy * 17 + s;
                if (b.style == VICTORIAN && Math.floorMod(seed, 2) == 0 && h > 20) {
                    bayWindow(fb, e, ox, oy, low, h, wallColour);
                }
                if ((b.style == TOKYO || b.style == TAIPEI) && low == 0 && Math.floorMod(seed * 7, 9) == 0) {
                    neonSign(e, ox, oy, h, seed);
                }
            }
        }
        roofFeatures(id, b, mansard ? h + slopeH : h);
    }

    /** the height next door (0 for open ground or off the map) */
    private int lowAt(int x, int y) {
        return x >= 0 && y >= 0 && x < gw && y < gh ? height[x][y] : 0;
    }

    /** a wall of the style's windows; windows line up along the street and floor by floor */
    private void facadeWall(Scenery3D.Builder fb, float ax, float ay, float bx, float by, float z0, float z1, Color c) {
        float ua = (ax == bx ? ay : ax) / BAY / 4f, ub = (ax == bx ? by : bx) / BAY / 4f;
        float va = -z0 / STOREY / 4f, vb = -z1 / STOREY / 4f;
        fb.quad(ax, ay, z0, bx, by, z0, bx, by, z1, ax, ay, z1, ua, va, ub, va, ub, vb, ua, vb, c);
    }

    /**
     * a moulding along a wall: a box sticking out `out` from it, `t` tall, its top at z
     * (the cornice) — its top face lit, front in the wall's light, underside in shadow
     */
    private void cornice(float[] e, float ox, float oy, float z, float out, float t, Color stone, float shade) {
        moulding(e, ox, oy, z - t, z, out, stone, shade, true);
        // a dark line under it, the 16-bit outline
        moulding(e, ox, oy, z - t - 0.6f, z - t, out * 0.6f, new Color(0.14f, 0.11f, 0.1f, 1f), 1f, false);
    }

    private void band(float[] e, float ox, float oy, float z, float out, float t, Color stone, float shade) {
        moulding(e, ox, oy, z, z + t, out, stone, shade, true);
    }

    private void moulding(float[] e, float ox, float oy, float z0, float z1, float out, Color c, float shade, boolean top) {
        // run a little past each end so corners close up
        float dx = e[2] - e[0], dy = e[3] - e[1], len = (float) Math.sqrt(dx * dx + dy * dy);
        float tx = dx / len * out, ty = dy / len * out;
        float ax = e[0] - tx, ay = e[1] - ty, bx = e[2] + tx, by = e[3] + ty;
        float px = ox * out, py = oy * out;
        Color front = new Color(c).mul(shade, shade, shade, 1f), under = new Color(c).mul(0.45f, 0.45f, 0.45f, 1f);
        quad(ax + px, ay + py, z0, bx + px, by + py, z0, bx + px, by + py, z1, ax + px, ay + py, z1, front);
        if (top) {
            quad(ax, ay, z1, bx, by, z1, bx + px, by + py, z1, ax + px, ay + py, z1, c);
        }
        quad(ax, ay, z0, ax + px, ay + py, z0, bx + px, by + py, z0, bx, by, z0, under);
        // the two end caps
        quad(ax, ay, z0, ax + px, ay + py, z0, ax + px, ay + py, z1, ax, ay, z1, under);
        quad(bx, by, z0, bx + px, by + py, z0, bx + px, by + py, z1, bx, by, z1, under);
    }

    /** a mansard / pitched roof face: from the wall top up and in to the flat roof */
    private void slope(float[] e, float ox, float oy, float h, float rise, float inset, Color c, float shade) {
        float ix = -ox * inset, iy = -oy * inset;
        Color col = new Color(c).mul(0.75f + 0.25f * shade, 0.75f + 0.25f * shade, 0.75f + 0.25f * shade, 1f);
        quad(e[0], e[1], h, e[2], e[3], h, e[2] + ix, e[3] + iy, h + rise, e[0] + ix, e[1] + iy, h + rise, col);
    }

    /** a dormer window on a mansard: a little box with a window, out of the slope */
    private void dormer(float[] e, float ox, float oy, float h, float inset) {
        float mx = (e[0] + e[2]) / 2f, my = (e[1] + e[3]) / 2f;
        float tx = (e[2] - e[0]) / 12f, ty = (e[3] - e[1]) / 12f;
        float bx = mx - ox * inset * 0.45f, by = my - oy * inset * 0.45f;
        Color stone = new Color(0.95f, 0.91f, 0.8f, 1f);
        float w = 2.6f;
        // front face, a dark window in it, and a little peaked cap
        quad(bx - tx * w, by - ty * w, h + 2, bx + tx * w, by + ty * w, h + 2, bx + tx * w, by + ty * w, h + 7.5f,
            bx - tx * w, by - ty * w, h + 7.5f, stone);
        quad(bx - tx * w * 0.6f + ox * 0.05f, by - ty * w * 0.6f + oy * 0.05f, h + 3, bx + tx * w * 0.6f + ox * 0.05f,
            by + ty * w * 0.6f + oy * 0.05f, h + 3, bx + tx * w * 0.6f + ox * 0.05f, by + ty * w * 0.6f + oy * 0.05f, h + 6.5f,
            bx - tx * w * 0.6f + ox * 0.05f, by - ty * w * 0.6f + oy * 0.05f, h + 6.5f, new Color(0.22f, 0.27f, 0.36f, 1f));
        quad(bx - tx * w * 1.2f, by - ty * w * 1.2f, h + 7.5f, bx + tx * w * 1.2f, by + ty * w * 1.2f, h + 7.5f,
            bx - ox * 3f, by - oy * 3f, h + 9.5f, bx - ox * 3f, by - oy * 3f, h + 9.5f, new Color(0.45f, 0.48f, 0.53f, 1f));
    }

    /** a Victorian bay window: a box of windows sticking out of the wall */
    private void bayWindow(Scenery3D.Builder fb, float[] e, float ox, float oy, float low, float h, Color c) {
        float mx = (e[0] + e[2]) / 2f, my = (e[1] + e[3]) / 2f;
        float tx = (e[2] - e[0]) / 12f * 3.6f, ty = (e[3] - e[1]) / 12f * 3.6f, out = 3f;
        float z0 = Math.max(low, 0) + 4, z1 = h - 5;
        float ax = mx - tx + ox * out, ay = my - ty + oy * out, bx = mx + tx + ox * out, by = my + ty + oy * out;
        facadeWall(fb, ax, ay, bx, by, z0, z1, c);
        facadeWall(fb, mx - tx, my - ty, ax, ay, z0, z1, new Color(c).mul(0.8f, 0.8f, 0.8f, 1f));
        facadeWall(fb, bx, by, mx + tx, my + ty, z0, z1, new Color(c).mul(0.8f, 0.8f, 0.8f, 1f));
        Color trim = new Color(0.98f, 0.98f, 0.96f, 1f);
        quad(mx - tx, my - ty, z1, mx + tx, my + ty, z1, bx, by, z1, ax, ay, z1, trim);
        quad(mx - tx, my - ty, z0, ax, ay, z0, bx, by, z0, mx + tx, my + ty, z0, new Color(trim).mul(0.6f, 0.6f, 0.6f, 1f));
    }

    /** a tall glowing sign sticking out from a shopfront (Tokyo, Taipei) */
    private void neonSign(float[] e, float ox, float oy, float h, int seed) {
        Color[] neon = {new Color(1f, 0.25f, 0.55f, 1f), new Color(0.2f, 0.95f, 1f, 1f), new Color(1f, 0.85f, 0.2f, 1f),
            new Color(0.5f, 1f, 0.35f, 1f), new Color(1f, 0.45f, 0.15f, 1f)};
        Color c = neon[Math.floorMod(seed, neon.length)];
        float mx = (e[0] + e[2]) / 2f, my = (e[1] + e[3]) / 2f;
        float z0 = 7, z1 = Math.min(h - 3, 7 + 18 + Math.floorMod(seed, 3) * 6);
        boxFree(mx + ox * 1f, my + oy * 1f, mx + ox * 6f, my + oy * 6f, 0.9f, z0, z1, c);
        // the dark frame round it
        boxFree(mx + ox * 0.5f, my + oy * 0.5f, mx + ox * 6.4f, my + oy * 6.4f, 1.1f, z1, z1 + 0.8f, new Color(0.12f, 0.1f, 0.1f, 1f));
    }

    // ---------------------------------------------------------------- roofs

    private void roofFeatures(int id, Building b, float roofZ) {
        // the patch deepest inside the building, and how many patches it is from the edge
        int[] centre = b.cells.get(0);
        int best = -1;
        for (int[] c : b.cells) {
            int d = depth(id, c[0], c[1]);
            if (d > best) {
                best = d;
                centre = c;
            }
        }
        float cx = centre[0] * CELL + CELL / 2f, cy = worldH - centre[1] * CELL - CELL / 2f;
        int r = Math.floorMod(b.seed >> 9, 10);
        switch (b.style) {
            case DECO:
            case STEEL:
            case GLASS:
                if (b.tall && best >= 1) {
                    crown(b, cx, cy, roofZ, best);
                } else if (b.style == GLASS) {
                    plant(cx, cy, roofZ, r);
                }
                break;
            case BRICK:
                if (b.cells.size() >= 4 && r < 7) {
                    waterTower(cx, cy, roofZ);
                }
                break;
            case GEORGIAN:
                for (int k = 0; k < Math.min(3, b.cells.size() / 3 + 1); k++) {
                    int[] c = b.cells.get(Math.floorMod(b.seed + k * 977, b.cells.size()));
                    chimney(c[0] * CELL + CELL / 2f, worldH - c[1] * CELL - CELL / 2f, roofZ);
                }
                break;
            case TOKYO:
            case GENERIC:
                plant(cx, cy, roofZ, r);
                break;
            case TAIPEI:
                waterTank(cx - 3, cy, roofZ);
                if (b.cells.size() > 6) {
                    waterTank(cx + 4, cy + 3, roofZ);
                }
                break;
            default:
                break;
        }
    }

    /** how many patches from (x, y) to the building's edge */
    private int depth(int id, int x, int y) {
        int d = 0;
        while (d < 6) {
            int k = d + 1;
            if (own(id, x + k, y) && own(id, x - k, y) && own(id, x, y + k) && own(id, x, y - k)
                    && own(id, x + k, y + k) && own(id, x - k, y - k) && own(id, x + k, y - k) && own(id, x - k, y + k)) {
                d = k;
            } else {
                break;
            }
        }
        return d;
    }

    private boolean own(int id, int x, int y) {
        return x >= 0 && y >= 0 && x < gw && y < gh && owner[x][y] == id;
    }

    /** a skyscraper's stepped crown: tiers set back one above the other, and maybe a spire */
    private void crown(Building b, float cx, float cy, float z, int depth) {
        float half = (depth + 0.5f) * CELL * 0.85f;
        Color stone = new Color(STONE[b.style]);
        Color body = b.style == DECO ? new Color(0.88f, 0.84f, 0.74f, 1f) : b.style == STEEL ? new Color(0.22f, 0.22f, 0.25f, 1f)
            : new Color(0.55f, 0.68f, 0.8f, 1f);
        float tier = z;
        for (int k = 0; k < 3; k++) {
            float hh = 14f - k * 2f;
            box(cx - half, cy - half, cx + half, cy + half, tier, tier + hh, k % 2 == 0 ? body : new Color(body).mul(0.9f, 0.9f, 0.9f, 1f));
            // a dark band of windows round each tier, and a ledge on top
            box(cx - half - 0.15f, cy - half - 0.15f, cx + half + 0.15f, cy + half + 0.15f, tier + hh * 0.35f, tier + hh * 0.6f,
                b.style == STEEL ? new Color(0.5f, 0.4f, 0.25f, 1f) : new Color(0.25f, 0.3f, 0.4f, 1f));
            box(cx - half - 0.8f, cy - half - 0.8f, cx + half + 0.8f, cy + half + 0.8f, tier + hh, tier + hh + 1.2f, stone);
            tier += hh + 1.2f;
            half *= 0.66f;
            if (half < 3f) {
                break;
            }
        }
        if (b.style != STEEL || Math.floorMod(b.seed, 3) == 0) {
            pyramid(cx, cy, tier, tier + 26f, Math.max(2.5f, half), 0.3f, b.style == DECO ? new Color(0.85f, 0.85f, 0.88f, 1f) : stone);
        } else {
            // twin antennas
            box(cx - 3f, cy - 0.6f, cx - 1.8f, cy + 0.6f, tier, tier + 30f, new Color(0.9f, 0.9f, 0.9f, 1f));
            box(cx + 1.8f, cy - 0.6f, cx + 3f, cy + 0.6f, tier, tier + 24f, new Color(0.9f, 0.9f, 0.9f, 1f));
        }
    }

    /** a New York wooden water tower on four legs, with a conical cap */
    private void waterTower(float cx, float cy, float z) {
        Color wood = new Color(0.55f, 0.37f, 0.24f, 1f), iron = new Color(0.16f, 0.14f, 0.13f, 1f);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                box(cx + sx * 3.5f - 0.5f, cy + sy * 3.5f - 0.5f, cx + sx * 3.5f + 0.5f, cy + sy * 3.5f + 0.5f, z, z + 6f, iron);
            }
        }
        prism(cx, cy, z + 6f, z + 16f, 5f, 5f, 8, wood);
        prism(cx, cy, z + 8.5f, z + 9.3f, 5.15f, 5.15f, 8, iron);
        prism(cx, cy, z + 13f, z + 13.8f, 5.15f, 5.15f, 8, iron);
        prism(cx, cy, z + 16f, z + 20f, 5.6f, 0.6f, 8, new Color(0.3f, 0.26f, 0.24f, 1f));
    }

    /** a Taipei rooftop water tank: a steel drum on a stand */
    private void waterTank(float cx, float cy, float z) {
        box(cx - 3f, cy - 3f, cx + 3f, cy + 3f, z, z + 2f, new Color(0.3f, 0.3f, 0.32f, 1f));
        prism(cx, cy, z + 2f, z + 9f, 3f, 3f, 8, new Color(0.8f, 0.82f, 0.85f, 1f));
        prism(cx, cy, z + 9f, z + 10f, 3f, 1.5f, 8, new Color(0.65f, 0.67f, 0.7f, 1f));
    }

    /** a London chimney stack with its pots */
    private void chimney(float cx, float cy, float z) {
        Color brick = new Color(0.62f, 0.38f, 0.28f, 1f);
        box(cx - 1.5f, cy - 3f, cx + 1.5f, cy + 3f, z - 2f, z + 7f, brick);
        box(cx - 1.9f, cy - 3.4f, cx + 1.9f, cy + 3.4f, z + 7f, z + 8f, new Color(0.85f, 0.82f, 0.75f, 1f));
        for (int k = -1; k <= 1; k++) {
            box(cx - 0.6f, cy + k * 1.9f - 0.6f, cx + 0.6f, cy + k * 1.9f + 0.6f, z + 8f, z + 10f, new Color(0.75f, 0.42f, 0.3f, 1f));
        }
    }

    /** rooftop plant: a lift housing, air-conditioning boxes and an aerial */
    private void plant(float cx, float cy, float z, int r) {
        Color grey = new Color(0.62f, 0.63f, 0.66f, 1f);
        box(cx - 4f, cy - 3f, cx + 2f, cy + 3f, z, z + 6f, grey);
        box(cx + 3f, cy - 2f, cx + 6f, cy + 1f, z, z + 2.5f, new Color(0.82f, 0.83f, 0.85f, 1f));
        if (r < 6) {
            box(cx - 0.4f, cy - 0.4f, cx + 0.4f, cy + 0.4f, z + 6f, z + 18f, new Color(0.3f, 0.3f, 0.33f, 1f));
            box(cx - 2.5f, cy - 0.2f, cx + 2.5f, cy + 0.2f, z + 15f, z + 15.5f, new Color(0.3f, 0.3f, 0.33f, 1f));
        }
    }

    // ---------------------------------------------------------------- shapes

    private void quad(float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3,
            float x4, float y4, float z4, Color c) {
        float u = 0.5f;
        solid.quad(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, u, u, u, u, u, u, u, u, c);
    }

    /** an upright box, its faces shaded by which way they point */
    private void box(float x0, float y0, float x1, float y1, float z0, float z1, Color c) {
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, c);
        quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, new Color(c).mul(0.9f, 0.9f, 0.9f, 1f));
        quad(x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1, new Color(c).mul(0.7f, 0.7f, 0.7f, 1f));
        quad(x1, y1, z0, x0, y1, z0, x0, y1, z1, x1, y1, z1, new Color(c).mul(0.82f, 0.82f, 0.82f, 1f));
        quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, new Color(c).mul(0.6f, 0.6f, 0.6f, 1f));
    }

    /** a box from (ax, ay) to (bx, by) along the ground, `half` thick either side */
    private void boxFree(float ax, float ay, float bx, float by, float half, float z0, float z1, Color c) {
        float dx = bx - ax, dy = by - ay, len = (float) Math.sqrt(dx * dx + dy * dy);
        float nx = -dy / len * half, ny = dx / len * half;
        float[][] p = {{ax + nx, ay + ny}, {bx + nx, by + ny}, {bx - nx, by - ny}, {ax - nx, ay - ny}};
        for (int i = 0; i < 4; i++) {
            float[] a = p[i], b2 = p[(i + 1) % 4];
            quad(a[0], a[1], z0, b2[0], b2[1], z0, b2[0], b2[1], z1, a[0], a[1], z1, new Color(c).mul(0.75f + 0.08f * i, 0.75f + 0.08f * i, 0.75f + 0.08f * i, 1f));
        }
        quad(p[0][0], p[0][1], z1, p[1][0], p[1][1], z1, p[2][0], p[2][1], z1, p[3][0], p[3][1], z1, c);
    }

    /** an n-sided upright prism / cone: radius r0 at z0 to r1 at z1 */
    private void prism(float cx, float cy, float z0, float z1, float r0, float r1, int n, Color c) {
        for (int i = 0; i < n; i++) {
            float a = i * 360f / n, b2 = (i + 1) * 360f / n;
            float f = 0.65f + 0.35f * (0.5f + 0.5f * MathUtils.cosDeg((a + b2) / 2f - 40f));
            quad(cx + MathUtils.cosDeg(a) * r0, cy + MathUtils.sinDeg(a) * r0, z0, cx + MathUtils.cosDeg(b2) * r0, cy + MathUtils.sinDeg(b2) * r0, z0,
                cx + MathUtils.cosDeg(b2) * r1, cy + MathUtils.sinDeg(b2) * r1, z1, cx + MathUtils.cosDeg(a) * r1, cy + MathUtils.sinDeg(a) * r1, z1,
                new Color(c).mul(f, f, f, 1f));
        }
        if (r1 > 0.8f) {
            for (int i = 0; i < n; i += 2) {
                float a = i * 360f / n, b2 = (i + 1) * 360f / n, c2 = (i + 2) * 360f / n;
                quad(cx, cy, z1, cx + MathUtils.cosDeg(a) * r1, cy + MathUtils.sinDeg(a) * r1, z1,
                    cx + MathUtils.cosDeg(b2) * r1, cy + MathUtils.sinDeg(b2) * r1, z1, cx + MathUtils.cosDeg(c2) * r1, cy + MathUtils.sinDeg(c2) * r1, z1, c);
            }
        }
    }

    /** a square spire tapering from half-width r0 to r1 */
    private void pyramid(float cx, float cy, float z0, float z1, float r0, float r1, Color c) {
        float[][] k = {{1, -1, 1, 1, 0.9f}, {-1, 1, -1, -1, 0.7f}, {1, 1, -1, 1, 0.82f}, {-1, -1, 1, -1, 0.6f}};
        for (float[] s : k) {
            quad(cx + s[0] * r0, cy + s[1] * r0, z0, cx + s[2] * r0, cy + s[3] * r0, z0, cx + s[2] * r1, cy + s[3] * r1, z1,
                cx + s[0] * r1, cy + s[1] * r1, z1, new Color(c).mul(s[4], s[4], s[4], 1f));
        }
    }

    // ---------------------------------------------------------------- the facades

    /**
     * a facade style, drawn pixel by pixel: one 8 x 8 window bay repeated 4 x 4 (some
     * styles vary their bays: lit windows, neon), the same chunky pixels as the car sprites
     */
    private static Texture style(int style) {
        String[] bay;
        String keys;
        Color[] cols;
        Color k = new Color(0.14f, 0.12f, 0.12f, 1f);
        switch (style) {
            case GLASS:
                bay = new String[] {"mmmmmmmm", "mGGGggGg", "mGgggggg", "mgggggbg", "mggggbbg", "mgggbbbg", "mggbbbbg", "dddddddd"};
                keys = "mGgbd";
                cols = new Color[] {c(0.86f, 0.89f, 0.92f), c(0.72f, 0.86f, 0.96f), c(0.36f, 0.5f, 0.63f), c(0.26f, 0.37f, 0.5f), c(0.3f, 0.33f, 0.37f)};
                break;
            case DECO:
                bay = new String[] {"PLLLLLLP", "PkwwwwkP", "PkwGwwkP", "PkwwwwkP", "PkwwwwkP", "PkddddkP", "PLLLLLLP", "PSSSSSSP"};
                keys = "PLkwGdS";
                cols = new Color[] {c(0.97f, 0.94f, 0.86f), c(0.86f, 0.82f, 0.72f), k, c(0.3f, 0.38f, 0.5f), c(0.62f, 0.72f, 0.86f),
                    c(0.45f, 0.4f, 0.34f), c(0.68f, 0.64f, 0.56f)};
                break;
            case BRICK:
                bay = new String[] {"rRrrrRrr", "rLLLLLLr", "rkwGwwkr", "rkwwwwkr", "rkwwwwkr", "rSSSSSSr", "FFFFFFFF", "RrrrRrrr"};
                keys = "rRLkwGSF";
                cols = new Color[] {c(0.66f, 0.3f, 0.22f), c(0.52f, 0.22f, 0.17f), c(0.88f, 0.84f, 0.76f), k, c(0.24f, 0.3f, 0.38f),
                    c(0.6f, 0.7f, 0.85f), c(0.86f, 0.82f, 0.74f), c(0.12f, 0.11f, 0.11f)};
                break;
            case GEORGIAN:
                bay = new String[] {"bbbBbbbb", "bWWWWWWb", "bWddddWb", "bWWWWWWb", "bWddddWb", "bWWWWWWb", "bSSSSSSb", "BBbBBBbB"};
                keys = "bBWdS";
                cols = new Color[] {c(0.73f, 0.6f, 0.42f), c(0.6f, 0.48f, 0.33f), c(0.97f, 0.96f, 0.92f), c(0.17f, 0.19f, 0.25f), c(0.88f, 0.86f, 0.8f)};
                break;
            case HAUSSMANN:
                bay = new String[] {"cCCCCCCc", "ckwwwwkc", "ckwGwwkc", "ckwwwwkc", "ckwwwwkc", "cIIIIIIc", "cIiIiIic", "llllllll"};
                keys = "cCkwGIil";
                cols = new Color[] {c(0.93f, 0.89f, 0.77f), c(0.8f, 0.75f, 0.62f), k, c(0.3f, 0.36f, 0.46f), c(0.64f, 0.74f, 0.88f),
                    c(0.12f, 0.12f, 0.13f), c(0.93f, 0.89f, 0.77f), c(0.74f, 0.7f, 0.59f)};
                break;
            case TOKYO:
                bay = new String[] {"tttttttt", "kkkkkkkk", "wGwwwwww", "wwwwwwww", "kkkkkkkk", "tttttttt", "tttttttt", "TTTTTTTT"};
                keys = "tkwGT";
                cols = new Color[] {c(0.9f, 0.9f, 0.88f), k, c(0.32f, 0.42f, 0.53f), c(0.65f, 0.78f, 0.9f), c(0.7f, 0.7f, 0.7f)};
                break;
            case TAIPEI:
                bay = new String[] {"cccccccc", "ckkkkkkc", "ckwmwmkc", "ckmmmmkc", "ckwmwmkc", "ckkkkkkc", "cAAAcccc", "CCCCCCCC"};
                keys = "ckwmAC";
                cols = new Color[] {c(0.78f, 0.78f, 0.76f), k, c(0.28f, 0.34f, 0.4f), c(0.62f, 0.64f, 0.67f), c(0.9f, 0.9f, 0.92f), c(0.58f, 0.58f, 0.57f)};
                break;
            case VICTORIAN:
                bay = new String[] {"sTTTTTTs", "STwwwwTS", "sTwGwwTs", "STwwwwTS", "sTwwwwTs", "STTTTTTS", "ssssssss", "SSSSSSSS"};
                keys = "sSTwG";
                cols = new Color[] {c(0.96f, 0.96f, 0.96f), c(0.84f, 0.84f, 0.84f), c(1f, 1f, 1f), c(0.3f, 0.35f, 0.45f), c(0.66f, 0.76f, 0.9f)};
                break;
            default:       // STEEL
                bay = new String[] {"kkkkkkkk", "kbbbbbbk", "kbBbbbbk", "kbbbbbbk", "kbbbbbbk", "kbbbbbbk", "kbbbbbbk", "kkkkkkkk"};
                keys = "kbB";
                cols = new Color[] {c(0.14f, 0.14f, 0.16f), c(0.45f, 0.35f, 0.23f), c(0.7f, 0.56f, 0.38f)};
                break;
        }
        Pixmap p = new Pixmap(32, 32, Pixmap.Format.RGBA8888);
        java.util.Random rnd = new java.util.Random(style * 11 + 3);
        Color lit = c(1f, 0.85f, 0.42f);
        for (int by = 0; by < 4; by++) {
            for (int bx = 0; bx < 4; bx++) {
                boolean on = rnd.nextInt(6) == 0;
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        char ch = bay[y].charAt(x);
                        int i = keys.indexOf(ch);
                        Color col = i < 0 ? Color.MAGENTA : cols[i];
                        // a few windows lit warm (glass pixels: 'w' / 'g' / 'b' / 'd' in the window)
                        if (on && (ch == 'w' || (style == GLASS && (ch == 'g' || ch == 'b')) || (style == STEEL && ch == 'b'))) {
                            col = lit;
                        }
                        p.setColor(col);
                        p.drawPixel(bx * 8 + x, by * 8 + y);
                    }
                }
            }
        }
        Texture t = new Texture(p);
        p.dispose();
        t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return t;
    }

    private static Color c(float r, float g, float b) {
        return new Color(r, g, b, 1f);
    }

    // ---------------------------------------------------------------- drawing

    /** the roof's own picture is drawn this much higher on a mansard building (0 = flat) */
    public float roofLift(int gx, int gy) {
        if (gx < 0 || gy < 0 || gx >= gw || gy >= gh || owner[gx][gy] < 0) {
            return 0f;
        }
        int s = buildings.get(owner[gx][gy]).style;
        return s == HAUSSMANN ? 9f : s == GEORGIAN ? 11f : 0f;
    }

    /** how far the roof picture is pulled in from a side that drops (for the mansard slope) */
    public float roofInset(int gx, int gy) {
        if (gx < 0 || gy < 0 || gx >= gw || gy >= gh || owner[gx][gy] < 0) {
            return 0f;
        }
        int s = buildings.get(owner[gx][gy]).style;
        return s == HAUSSMANN ? 4f : s == GEORGIAN ? 6f : 0f;
    }

    /** true if this patch is part of a building this class has built */
    public boolean builds(int gx, int gy) {
        return gx >= 0 && gy >= 0 && gx < gw && gy < gh && owner[gx][gy] >= 0;
    }

    public void render(ShaderProgram shader) {
        for (Map.Entry<Texture, List<Mesh>> e : meshes.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            e.getKey().bind(0);
            for (Mesh m : e.getValue()) {
                m.render(shader, GL20.GL_TRIANGLES);
            }
        }
    }

    public boolean isEmpty() {
        for (List<Mesh> l : meshes.values()) {
            if (!l.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public void dispose() {
        for (Map.Entry<Texture, List<Mesh>> e : meshes.entrySet()) {
            for (Mesh m : e.getValue()) {
                m.dispose();
            }
            e.getKey().dispose();
        }
    }
}
