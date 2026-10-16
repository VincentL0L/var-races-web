package io.github.VincentL0L.VARraces;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * The buildings of each city for the first-person view, every one of them different, in
 * the game's chunky 16-bit pixel style.
 *
 * The height map says which 12 x 12 patches are buildings; neighbouring patches of the same
 * height make one building. Each building is then designed from its own random seed and its
 * city's rules: its facade is drawn pixel by pixel (the material, its colour, the windows'
 * size, shape, frames and panes, balconies, shutters, fire escapes, AC units, neon, and a
 * shopfront with an awning on the ground floor), and its shape gets its own height, cornice
 * or parapet, awnings, and roof: mansards, slate roofs and chimneys, water towers, tanks,
 * plant, billboards, gardens, solar panels, domes, spires and crowns. So a map of a few
 * hundred buildings has a few hundred different ones.
 *
 * The cities: Paris (Haussmann stone and stucco, iron balconies, zinc mansards), London
 * (stock brick, red brick and white stucco terraces, sash windows, slate and chimneys),
 * New York (brick and brownstone, fire escapes, water towers, Art Deco and glass towers),
 * Chicago (brick, greystone, steel), Tokyo (tile and concrete, ribbon windows, neon,
 * billboards), Taipei (concrete flats, caged windows, laundry, tanks), San Francisco
 * (pastel Victorians with bay windows and turrets, downtown towers).
 */
public class CityArchitecture {
    private static final int CELL = 12;
    /** a facade picture is 32 x 64 pixels: 4 bays of 16 units across, 8 storeys of 12 up */
    private static final float TILE_W = 64f, TILE_H = 96f, STOREY = 12f;

    private static final int PARIS = 0, LONDON = 1, NYC = 2, CHICAGO = 3, TOKYO = 4, TAIPEI = 5, SF = 6, GENERIC = 7;

    private final String city;
    private final int family;
    private final Texture plain;
    private final Map<Texture, List<Mesh>> meshes = new HashMap<>();
    private final Scenery3D.Builder solid;

    private static final class Building {
        final List<int[]> cells = new ArrayList<>();
        int height, seed;
        boolean tall, mansard, shop, deco;
        float slopeRise, slopeInset;
        Random rnd;
        Scenery3D.Builder fb;
        Color stone, awning, roofTone;
        int cornice;     // 0 deep, 1 thin, 2 parapet
    }

    private final int[][] height;
    private int[][] owner;
    private final int gw, gh;
    private final float worldH;
    private final List<Building> buildings = new ArrayList<>();

    public CityArchitecture(TrackMap map, int[][] height, int[][] kind, Color[][] roofColour, boolean[][] skip, float worldH) {
        this.city = map.id;
        this.height = height;
        this.worldH = worldH;
        gw = height.length;
        gh = height[0].length;
        family = familyOf(city);
        Pixmap p = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        p.setColor(Color.WHITE);
        p.fill();
        plain = new Texture(p);
        p.dispose();
        solid = new Scenery3D.Builder(list(plain));

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
                b.seed = (x * 73856093) ^ (y * 19349663) ^ city.hashCode();
                buildings.add(b);
            }
        }
        // design every building: its height (rounded to whole storeys), then its look
        for (Building b : buildings) {
            b.rnd = new Random(b.seed);
            b.tall = b.height >= 52;
            float f = b.tall ? 0.95f + b.rnd.nextFloat() * 0.45f : 0.75f + b.rnd.nextFloat() * 0.55f;
            int storeys = Math.max(2, Math.round(b.height * f / STOREY));
            storeys = Math.min(storeys, 7);
            b.height = storeys * (int) STOREY + 2;
            for (int[] c : b.cells) {
                height[c[0]][c[1]] = b.height;
            }
        }
        for (Building b : buildings) {
            design(b);
        }
        for (int i = 0; i < buildings.size(); i++) {
            build(i, buildings.get(i));
        }
        solid.finish();
        for (Building b : buildings) {
            b.fb.finish();
        }
    }

    private static int familyOf(String id) {
        switch (id) {
            case TrackMap.PARIS:
                return PARIS;
            case TrackMap.LONDON:
                return LONDON;
            case TrackMap.NEW_YORK:
            case TrackMap.RUSH_HOUR:
                return NYC;
            case TrackMap.CHICAGO:
            case TrackMap.ROOFTOP:
                return CHICAGO;
            case TrackMap.TOKYO:
                return TOKYO;
            case TrackMap.TAIPEI:
                return TAIPEI;
            case TrackMap.SAN_FRANCISCO:
                return SF;
            default:
                return GENERIC;
        }
    }

    private List<Mesh> list(Texture t) {
        List<Mesh> l = new ArrayList<>();
        meshes.put(t, l);
        return l;
    }

    // ---------------------------------------------------------------- designing a building

    private void design(Building b) {
        Random r = b.rnd;
        boolean towerCity = family == NYC || family == CHICAGO || family == TOKYO || family == TAIPEI || family == SF
            || (family == LONDON && b.cells.size() >= 10) || family == GENERIC;
        boolean tower = b.tall && towerCity && (family != PARIS);
        b.deco = tower && (family == NYC || family == CHICAGO) && r.nextFloat() < 0.45f;
        b.shop = !tower && r.nextFloat() < (family == TOKYO || family == TAIPEI || family == PARIS ? 0.8f : 0.55f);
        b.mansard = !tower && (family == PARIS ? r.nextFloat() < 0.85f : family == LONDON ? r.nextFloat() < 0.55f : r.nextFloat() < 0.06f);
        b.slopeRise = family == PARIS ? 8f + r.nextInt(4) : 9f + r.nextInt(5);
        b.slopeInset = family == PARIS ? 3.5f + r.nextFloat() * 1.5f : 5f + r.nextFloat() * 2f;
        b.cornice = tower ? (r.nextBoolean() ? 1 : 2) : r.nextFloat() < 0.55f ? 0 : r.nextFloat() < 0.5f ? 1 : 2;
        b.awning = pick(r, c(0x8c2b2b), c(0x2b5c3a), c(0x1f3b6b), c(0x6b2b4e), c(0xc9792b), c(0x2b2b2b), c(0xd9433b), c(0x3f8f8f));
        Texture t = tower ? towerFacade(b, r) : facade(b, r);
        b.fb = new Scenery3D.Builder(list(t));
        if (b.stone == null) {
            b.stone = c(0xe8e2d4);
        }
        b.roofTone = family == PARIS ? c(0x7f8794) : family == LONDON ? pick(r, c(0x5a606b), c(0x6b5a52), c(0x4f5560)) : c(0x6a6a70);
    }

    @SafeVarargs
    private static <T> T pick(Random r, T... options) {
        return options[r.nextInt(options.length)];
    }

    // ---------------------------------------------------------------- facades, pixel by pixel

    /** a facade picture being drawn: 32 x 64, storey rows of 8 pixels, the ground floor at the bottom */
    private static final class Art {
        final Pixmap p = new Pixmap(32, 64, Pixmap.Format.RGBA8888);

        void px(int x, int y, Color c) {
            if (x >= 0 && y >= 0 && x < 32 && y < 64) {
                p.setColor(c);
                p.drawPixel(x, y);
            }
        }

        void rect(int x, int y, int w, int h, Color c) {
            for (int yy = y; yy < y + h; yy++) {
                for (int xx = x; xx < x + w; xx++) {
                    px(xx, yy, c);
                }
            }
        }

        Texture done() {
            Texture t = new Texture(p);
            p.dispose();
            t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.ClampToEdge);
            t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            return t;
        }
    }

    private static final int BRICK = 0, STONE = 1, STUCCO = 2, SIDING = 3, TILE = 4, CONCRETE = 5;

    /** an ordinary building's front: material, windows and all their trimmings, and a ground floor */
    private Texture facade(Building b, Random r) {
        Art a = new Art();
        // the material and its colour, by city
        int material;
        Color wall;
        switch (family) {
            case PARIS:
                material = r.nextFloat() < 0.6f ? STONE : STUCCO;
                wall = pick(r, c(0xeee5cf), c(0xe6dcc3), c(0xf2ead8), c(0xdcd3c2), c(0xe9dccb), c(0xd9cdb4), c(0xefe2d0), c(0xe4d6c0));
                break;
            case LONDON:
                material = r.nextFloat() < 0.65f ? BRICK : STUCCO;
                wall = material == BRICK ? pick(r, c(0xb89a6e), c(0xa8835a), c(0x9c5a42), c(0x8e4a38), c(0xc2a578), c(0x7f5f48))
                    : pick(r, c(0xf2efe6), c(0xefe7d4), c(0xe9e4da), c(0xdfe3e6));
                break;
            case NYC:
                material = r.nextFloat() < 0.6f ? BRICK : r.nextFloat() < 0.5f ? STONE : STUCCO;
                wall = material == BRICK ? pick(r, c(0xa8452f), c(0x8a3a2a), c(0xb5684a), c(0x7a3326), c(0xc08a5e), c(0x9a5a3e), c(0x6e4a3a))
                    : material == STONE ? pick(r, c(0x8a6a50), c(0x7a5a42), c(0xc9bba0), c(0xb7a88d)) : pick(r, c(0xd8d0c0), c(0xc9c2b5));
                break;
            case CHICAGO:
                material = r.nextFloat() < 0.55f ? BRICK : STONE;
                wall = material == BRICK ? pick(r, c(0x9a4a35), c(0xb3794f), c(0x7d3e2e), c(0xa88760), c(0x6f5544))
                    : pick(r, c(0xa9a59b), c(0x8f8b82), c(0xc2bdb1));
                break;
            case TOKYO:
                material = r.nextFloat() < 0.5f ? TILE : CONCRETE;
                wall = pick(r, c(0xf0eee8), c(0xe2ded6), c(0xc9c6c0), c(0xdcd4c4), c(0xd2dde3), c(0xe8d8d6), c(0xb8b4ae), c(0xece4cf),
                    c(0xa8aeb4));
                break;
            case TAIPEI:
                material = r.nextFloat() < 0.4f ? TILE : CONCRETE;
                wall = pick(r, c(0xc9c4b8), c(0xb5b1a8), c(0xd8d2c4), c(0xa9a8a2), c(0xc4b9a4), c(0xd6cfc8), c(0xb9c0c2), c(0x9ea39b));
                break;
            case SF:
                material = r.nextFloat() < 0.75f ? SIDING : STUCCO;
                wall = pick(r, c(0xf7b7c4), c(0xb4e3cc), c(0xd3c0f2), c(0xfbe39a), c(0xa9d1f7), c(0xf9cba0), c(0xe9f2b0), c(0xf2f2f2),
                    c(0xc7e8f0), c(0xf0a8a0), c(0x9fd8c0));
                break;
            default:
                material = r.nextInt(6);
                wall = pick(r, c(0xd8d0c0), c(0xb8a890), c(0xa8452f), c(0xc9c6c0), c(0xe8e2d4), c(0x9a9a9a));
                break;
        }
        wall = jitter(wall, r, 0.05f);
        Color dark = mul(wall, 0.82f), light = mul(wall, 1.07f);
        b.stone = family == SF ? c(0xfafafa) : material == BRICK ? pick(r, c(0xe6dfd0), c(0xd9cfbd), c(0xcfc6b4))
            : mul(wall, r.nextBoolean() ? 1.1f : 0.86f);
        // fill with the material's pattern
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 32; x++) {
                Color px = wall;
                switch (material) {
                    case BRICK:
                        if (y % 3 == 2) {
                            px = light;                                     // mortar course
                        } else if ((x + (y / 3 % 2) * 3) % 6 == 0) {
                            px = light;                                     // header joints, offset each course
                        } else if (r.nextInt(9) == 0) {
                            px = dark;
                        }
                        break;
                    case STONE:
                        px = y % 6 == 5 ? dark : ((x + (y / 6 % 2) * 6) % 12 == 0 ? dark : wall);
                        break;
                    case SIDING:
                        px = y % 2 == 1 ? dark : wall;
                        break;
                    case TILE:
                        px = (x % 4 == 0 || y % 4 == 0) ? light : wall;
                        break;
                    case CONCRETE:
                        px = r.nextInt(10) == 0 ? dark : (x % 16 == 15 ? mul(wall, 0.92f) : wall);
                        break;
                    default:
                        px = r.nextInt(20) == 0 ? dark : wall;
                        break;
                }
                a.px(x, y, px);
            }
        }
        // the windows
        int bays = family == TOKYO && r.nextFloat() < 0.35f ? 2 : r.nextFloat() < 0.15f ? 2 : 4;
        int bw = 32 / bays;
        int ww = bays == 2 ? 6 + r.nextInt(7) : 2 + r.nextInt(4);
        boolean ribbon = family == TOKYO && r.nextFloat() < 0.4f;
        if (ribbon) {
            bays = 1;
            bw = 32;
            ww = 32;
        }
        int wh = 3 + r.nextInt(3);
        int top = 1 + r.nextInt(Math.max(1, 7 - wh - 1));
        boolean arch = (family == PARIS || family == LONDON || family == NYC || family == CHICAGO) && ww >= 3 && r.nextFloat() < 0.35f;
        boolean mullion = ww >= 4 && r.nextFloat() < 0.6f, transom = wh >= 4 && r.nextFloat() < 0.5f;
        boolean lintel = r.nextFloat() < 0.7f, sill = r.nextFloat() < 0.75f;
        boolean shutters = (family == PARIS || family == SF || family == GENERIC) && ww <= 4 && r.nextFloat() < 0.25f;
        boolean caged = family == TAIPEI && r.nextFloat() < 0.6f;
        boolean fireEscape = family == NYC && material != STUCCO && r.nextFloat() < 0.55f;
        boolean balconyAll = (family == TAIPEI || family == TOKYO) && r.nextFloat() < 0.45f;
        int balconyA = family == PARIS ? (r.nextBoolean() ? 5 : 4) : -1, balconyB = family == PARIS && r.nextBoolean() ? 1 : -1;
        boolean flowers = (family == PARIS || family == LONDON) && r.nextFloat() < 0.3f;
        boolean band = r.nextFloat() < 0.5f;
        Color frame = family == LONDON || family == SF ? c(0xf6f4ee) : pick(r, c(0x1e1a18), c(0xf6f4ee), mul(wall, 0.55f), c(0x3a3330), c(0x4a5a4a));
        Color glass = pick(r, c(0x2f3e55), c(0x26313f), c(0x354a5e), c(0x2b3a4a), c(0x3a4a58));
        Color glint = c(0x8fb3d8), lit = c(0xffd36b);
        Color shutter = pick(r, c(0x3f6f5a), c(0x47607a), c(0x7a4a3a), c(0x5a6b7a), c(0xd9d0b8));
        Color rail = family == TAIPEI ? c(0xb8bcc0) : c(0x18181a);
        int neonBay = (family == TOKYO || family == TAIPEI) && r.nextFloat() < 0.45f ? r.nextInt(Math.max(1, bays)) : -1;
        Color neon = pick(r, c(0xff3d7f), c(0x33e6ff), c(0xffd23f), c(0x7dff4a), c(0xff7a26), c(0xc46bff));
        for (int s = 0; s < 7; s++) {
            int y0 = s * 8;
            if (band) {
                a.rect(0, y0, 32, 1, b.stone);
            }
            for (int k = 0; k < bays; k++) {
                int x0 = k * bw + (bw - ww) / 2;
                int wy = y0 + top, hh = wh - (s == 0 && r.nextBoolean() ? 1 : 0);
                boolean on = r.nextInt(7) == 0;
                if (lintel) {
                    a.rect(x0 - 1, wy - 1, ww + 2, 1, b.stone);
                }
                a.rect(x0, wy, ww, hh, frame);
                if (ww > 2 && hh > 2) {
                    a.rect(x0 + 1, wy + 1, ww - 2, hh - 2, on ? lit : glass);
                    if (!on) {
                        a.px(x0 + 1, wy + 1, glint);
                    }
                    if (mullion && !ribbon) {
                        a.rect(x0 + ww / 2, wy + 1, 1, hh - 2, frame);
                    }
                    if (ribbon) {
                        for (int m = 3; m < 31; m += 4) {
                            a.rect(m, wy + 1, 1, hh - 2, frame);
                        }
                    }
                    if (transom) {
                        a.rect(x0 + 1, wy + 2, ww - 2, 1, frame);
                    }
                    if (caged) {
                        for (int cx = x0; cx < x0 + ww; cx += 2) {
                            a.rect(cx, wy, 1, hh, c(0x8a8f96));
                        }
                    }
                } else {
                    a.rect(x0, wy, ww, hh, on ? lit : glass);
                }
                if (arch) {
                    a.px(x0, wy, b.stone);
                    a.px(x0 + ww - 1, wy, b.stone);
                }
                if (sill) {
                    a.rect(x0 - 1, wy + hh, ww + 2, 1, b.stone);
                }
                if (shutters) {
                    a.rect(x0 - 2, wy, 1, hh, shutter);
                    a.rect(x0 + ww + 1, wy, 1, hh, shutter);
                }
                if (flowers && r.nextBoolean()) {
                    for (int fx = x0; fx < x0 + ww; fx++) {
                        a.px(fx, wy + hh, fx % 2 == 0 ? c(0x3f8f3a) : pick(r, c(0xd9433b), c(0xf2c84b), c(0xf08ab0)));
                    }
                }
                // AC boxes and laundry hanging out (Tokyo, Taipei)
                if ((family == TAIPEI || family == TOKYO) && r.nextInt(3) == 0) {
                    a.rect(x0 + ww - 2, wy + hh, 2, 2, c(0xe4e6e8));
                    a.px(x0 + ww - 2, wy + hh + 1, c(0x8a8f96));
                }
                if (family == TAIPEI && r.nextInt(4) == 0) {
                    for (int lx = x0; lx < x0 + ww; lx++) {
                        if (r.nextBoolean()) {
                            a.px(lx, wy + hh + 1, pick(r, c(0xf2f2f2), c(0x3f6fd0), c(0xd9433b), c(0xf2c84b), c(0x6cb84b)));
                        }
                    }
                }
            }
            // balconies across the whole storey
            if (balconyAll || s == balconyA || s == balconyB) {
                int ry = y0 + 7;
                for (int x = 0; x < 32; x++) {
                    a.px(x, ry, x % 2 == 0 ? rail : (family == TAIPEI ? mul(wall, 0.9f) : rail));
                    if (x % 2 == 0) {
                        a.px(x, ry - 1, rail);
                    }
                }
            }
            // a fire escape zigzagging down one bay
            if (fireEscape) {
                int fx = (bays > 1 ? bw : 0) + 1;
                a.rect(fx - 1, y0 + 6, 9, 1, c(0x141414));
                for (int d = 0; d < 6; d++) {
                    a.px(fx + d + (s % 2 == 0 ? 0 : 1), y0 + 6 - d, c(0x141414));
                }
            }
            if (neonBay >= 0 && s >= 1) {
                int nx = neonBay * bw + (bw > 8 ? bw - 4 : 0);
                a.rect(nx, y0, 3, 8, neon);
                a.px(nx + 1, y0 + 2 + r.nextInt(4), c(0x1a1a1a));
                a.px(nx + 1, y0 + 1 + r.nextInt(5), mul(neon, 0.6f));
            }
        }
        // the ground floor (bottom 8 rows)
        int gy = 56;
        if (b.shop) {
            Color stripe = family == PARIS || family == LONDON ? b.awning : pick(r, b.awning, c(0xf2f2f2));
            for (int x = 0; x < 32; x++) {
                a.px(x, gy, x % 4 < 2 ? b.awning : stripe);                    // awning edge
            }
            Color sign = pick(r, c(0x1e1a18), b.awning, c(0xf2f0e8), c(0x2b3a6b));
            a.rect(0, gy + 1, 32, 1, sign);
            for (int x = 2; x < 30; x += 2) {
                if (r.nextInt(3) > 0) {
                    a.px(x, gy + 1, family == TOKYO || family == TAIPEI ? neon : c(0xf2c84b));   // the shop's name
                }
            }
            Color shopGlass = r.nextBoolean() ? c(0x3a4a58) : lit;
            a.rect(0, gy + 2, 32, 5, frame);
            for (int k = 0; k < 4; k++) {
                if (k == 1) {
                    a.rect(k * 8 + 2, gy + 2, 4, 6, mul(b.awning, 0.6f));      // the door
                } else {
                    a.rect(k * 8 + 1, gy + 3, 6, 3, shopGlass);
                    a.px(k * 8 + 1, gy + 3, glint);
                }
            }
            a.rect(0, gy + 7, 32, 1, mul(wall, 0.55f));
        } else if (family == SF && r.nextBoolean()) {
            a.rect(2, gy + 1, 12, 7, c(0xf2f0ea));                               // a garage door
            for (int y = gy + 2; y < gy + 8; y += 2) {
                a.rect(2, y, 12, 1, c(0xcfccc4));
            }
            a.rect(20, gy + 2, 4, 6, mul(wall, 0.5f));
        } else if (family == TAIPEI) {
            a.rect(0, gy, 32, 8, c(0x2a2a2e));                                  // the shaded arcade
            for (int x = 0; x < 32; x += 8) {
                a.rect(x, gy, 2, 8, wall);
            }
        } else {
            a.rect(0, gy + 7, 32, 1, mul(wall, 0.6f));
            a.rect(13, gy + 2, 5, 6, pick(r, c(0x1e1a18), c(0x8c2b2b), c(0x1f3b6b), c(0x2b5c3a), c(0x5a3a2a)));   // the front door
            a.rect(12, gy + 1, 7, 1, b.stone);
        }
        return a.done();
    }

    /** a skyscraper's skin: a glass curtain wall, or Art Deco stone piers with recessed windows */
    private Texture towerFacade(Building b, Random r) {
        Art a = new Art();
        if (b.deco) {
            Color stone = pick(r, c(0xe8e0cc), c(0xd8cfb8), c(0xcfc6b0), c(0xbfb6a0));
            Color glass = pick(r, c(0x2b3a4a), c(0x33302a), c(0x26313f));
            Color spandrel = pick(r, c(0x5a4a3a), c(0x3a3a3e), c(0x6b5a40));
            b.stone = mul(stone, 1.05f);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 32; x++) {
                    boolean pier = x % 4 < 2;
                    Color px = pier ? (x % 4 == 0 ? mul(stone, 1.06f) : stone)
                        : (y % 8 >= 6 ? spandrel : (r.nextInt(8) == 0 ? c(0xffd36b) : glass));
                    a.px(x, y, px);
                }
            }
        } else {
            Color glass = pick(r, c(0x3f6f9a), c(0x2f7f7f), c(0x4a7a5a), c(0x8a6a40), c(0x9aa4ae), c(0x2a3038), c(0x5a8ab8), c(0x6a5a8a));
            Color mull = pick(r, c(0xd8dde2), c(0xf2f2f2), c(0x1e1e22), c(0xb08a50), c(0x8a9096));
            Color spandrel = r.nextBoolean() ? mul(glass, 0.7f) : mull;
            int every = pick(r, 2, 4, 4, 8);
            boolean bands = r.nextBoolean();
            b.stone = mull;
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 32; x++) {
                    int row = y % 8;
                    Color px;
                    if (x % every == 0) {
                        px = mull;
                    } else if (bands && row >= 6) {
                        px = spandrel;
                    } else {
                        float t = row / 8f;
                        px = mul(glass, 1.15f - t * 0.3f);
                        if (row == 1 && x % every == 1) {
                            px = mul(glass, 1.6f);
                        }
                        if (r.nextInt(20) == 0) {
                            px = c(0xffd36b);
                        }
                    }
                    a.px(x, y, px);
                }
            }
        }
        // the lobby
        a.rect(0, 56, 32, 8, c(0x1e2228));
        for (int x = 0; x < 32; x += 6) {
            a.rect(x, 57, 1, 7, b.stone);
        }
        a.rect(0, 56, 32, 1, b.stone);
        return a.done();
    }

    private static Color jitter(Color c, Random r, float amount) {
        float f = 1f + (r.nextFloat() - 0.5f) * 2f * amount;
        return new Color(Math.min(1, c.r * f), Math.min(1, c.g * f), Math.min(1, c.b * f), 1f);
    }

    private static Color mul(Color c, float f) {
        return new Color(Math.min(1, c.r * f), Math.min(1, c.g * f), Math.min(1, c.b * f), 1f);
    }

    private static Color c(int rgb) {
        return new Color(((rgb >> 16) & 0xff) / 255f, ((rgb >> 8) & 0xff) / 255f, (rgb & 0xff) / 255f, 1f);
    }

    // ---------------------------------------------------------------- building the shape

    private void build(int id, Building b) {
        int h = b.height;
        Random r = new Random(b.seed * 31L + 7);
        boolean turret = family == SF && !b.tall && r.nextFloat() < 0.4f;
        boolean turretDone = false;
        for (int[] cell : b.cells) {
            int gx = cell[0], gy = cell[1];
            float x0 = gx * CELL, x1 = x0 + CELL, y1 = worldH - gy * CELL, y0 = y1 - CELL;
            int[][] n = {{gx + 1, gy}, {gx - 1, gy}, {gx, gy - 1}, {gx, gy + 1}};
            float[][] ends = {{x1, y0, x1, y1}, {x0, y1, x0, y0}, {x1, y1, x0, y1}, {x0, y0, x1, y0}};
            float[][] normal = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            float[] shade = {0.93f, 0.72f, 1f, 0.64f};
            for (int s = 0; s < 4; s++) {
                int low = lowAt(n[s][0], n[s][1]);
                if (low >= h) {
                    continue;
                }
                float[] e = ends[s];
                float ox = normal[s][0], oy = normal[s][1], sh = shade[s];
                Color wallColour = new Color(sh, sh, sh, 1f);
                facadeWall(b.fb, e[0], e[1], e[2], e[3], low, h, wallColour);
                if (b.cornice == 0) {
                    moulding(e, ox, oy, h - 2.4f, h, 1.6f + (b.seed & 3) * 0.3f, b.stone, sh, true);
                    moulding(e, ox, oy, h - 3f, h - 2.4f, 0.9f, c(0x231c18), 1f, false);
                } else if (b.cornice == 1) {
                    moulding(e, ox, oy, h - 1f, h, 0.6f, b.stone, sh, true);
                } else {
                    moulding(e, ox, oy, h, h + 2.6f, 0.5f, b.stone, sh, true);       // a parapet above the roofline
                }
                if (low == 0) {
                    moulding(e, ox, oy, 0f, 1.6f, 0.4f, mul(b.stone, 0.6f), sh, true);
                    if (b.shop && !b.tall) {
                        awning(e, ox, oy, b.awning, sh);
                    }
                }
                // iron balconies as real ledges on Paris facades (matching the drawn ones)
                if (family == PARIS && !b.tall) {
                    for (int row = 4; row <= 5; row++) {
                        float z = TILE_H - (row + 1) * STOREY + 0.4f;
                        if (z > low + 2 && z < h - 4) {
                            moulding(e, ox, oy, z, z + 0.5f, 1.2f, c(0x1c1c1e), sh, true);
                        }
                    }
                }
                if (b.mansard) {
                    slope(e, ox, oy, h, b.slopeRise, b.slopeInset, b.roofTone, sh);
                    if (family == PARIS && Math.floorMod(gx * 3 + gy * 5 + s, 2) == 0) {
                        dormer(e, ox, oy, h, b.slopeInset);
                    }
                }
                int seed = gx * 31 + gy * 17 + s;
                if (family == SF && !b.tall && Math.floorMod(seed, 2) == 0 && h > 26) {
                    bayWindow(b.fb, e, ox, oy, low, h);
                }
                if ((family == TOKYO || family == TAIPEI) && low == 0 && Math.floorMod(seed * 7 + b.seed, 7) == 0) {
                    neonSign(e, ox, oy, h, seed + b.seed);
                }
            }
            // a corner turret with a pointed cap (San Francisco)
            if (turret && !turretDone && lowAt(gx + 1, gy) < h && lowAt(gx, gy + 1) < h) {
                turretDone = true;
                float tx = x1 - 1f, ty = y0 + 1f;
                prism(tx, ty, 10f, h + 4f, 5f, 5f, 8, mul(b.stone, 0.95f));
                prism(tx, ty, h + 4f, h + 14f, 5.8f, 0.4f, 8, b.roofTone);
            }
        }
        roofFeatures(id, b, b.mansard ? h + b.slopeRise : h, r);
    }

    private int lowAt(int x, int y) {
        return x >= 0 && y >= 0 && x < gw && y < gh ? height[x][y] : 0;
    }

    /** a wall of the building's own facade; storeys counted up from the street, windows aligned along it */
    private void facadeWall(Scenery3D.Builder fb, float ax, float ay, float bx, float by, float z0, float z1, Color c) {
        float ua = (ax == bx ? ay : ax) / TILE_W, ub = (ax == bx ? by : bx) / TILE_W;
        float va = 1f - z0 / TILE_H, vb = 1f - Math.min(z1, TILE_H - 0.01f) / TILE_H;
        fb.quad(ax, ay, z0, bx, by, z0, bx, by, z1, ax, ay, z1, ua, va, ub, va, ub, vb, ua, vb, c);
    }

    private void moulding(float[] e, float ox, float oy, float z0, float z1, float out, Color c, float shade, boolean top) {
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
        quad(ax, ay, z0, ax + px, ay + py, z0, ax + px, ay + py, z1, ax, ay, z1, under);
        quad(bx, by, z0, bx + px, by + py, z0, bx + px, by + py, z1, bx, by, z1, under);
    }

    /** a shop awning: a sloping canopy over the ground floor */
    private void awning(float[] e, float ox, float oy, Color c, float shade) {
        float out = 3.2f;
        Color col = new Color(c).mul(0.8f + 0.2f * shade, 0.8f + 0.2f * shade, 0.8f + 0.2f * shade, 1f);
        quad(e[0], e[1], 10.5f, e[2], e[3], 10.5f, e[2] + ox * out, e[3] + oy * out, 8.6f, e[0] + ox * out, e[1] + oy * out, 8.6f, col);
        quad(e[0] + ox * out, e[1] + oy * out, 7.6f, e[2] + ox * out, e[3] + oy * out, 7.6f, e[2] + ox * out, e[3] + oy * out, 8.6f,
            e[0] + ox * out, e[1] + oy * out, 8.6f, new Color(c).mul(0.6f, 0.6f, 0.6f, 1f));
    }

    private void slope(float[] e, float ox, float oy, float h, float rise, float inset, Color c, float shade) {
        float ix = -ox * inset, iy = -oy * inset;
        Color col = new Color(c).mul(0.75f + 0.25f * shade, 0.75f + 0.25f * shade, 0.75f + 0.25f * shade, 1f);
        quad(e[0], e[1], h, e[2], e[3], h, e[2] + ix, e[3] + iy, h + rise, e[0] + ix, e[1] + iy, h + rise, col);
    }

    private void dormer(float[] e, float ox, float oy, float h, float inset) {
        float mx = (e[0] + e[2]) / 2f, my = (e[1] + e[3]) / 2f;
        float tx = (e[2] - e[0]) / 12f, ty = (e[3] - e[1]) / 12f;
        float bx = mx - ox * inset * 0.45f, by = my - oy * inset * 0.45f;
        float w = 2.6f;
        quad(bx - tx * w, by - ty * w, h + 2, bx + tx * w, by + ty * w, h + 2, bx + tx * w, by + ty * w, h + 7.5f,
            bx - tx * w, by - ty * w, h + 7.5f, c(0xf0e6d0));
        float g = 0.05f;
        quad(bx - tx * w * 0.6f + ox * g, by - ty * w * 0.6f + oy * g, h + 3, bx + tx * w * 0.6f + ox * g, by + ty * w * 0.6f + oy * g, h + 3,
            bx + tx * w * 0.6f + ox * g, by + ty * w * 0.6f + oy * g, h + 6.5f, bx - tx * w * 0.6f + ox * g, by - ty * w * 0.6f + oy * g, h + 6.5f,
            c(0x2f3e55));
        quad(bx - tx * w * 1.2f, by - ty * w * 1.2f, h + 7.5f, bx + tx * w * 1.2f, by + ty * w * 1.2f, h + 7.5f,
            bx - ox * 3f, by - oy * 3f, h + 9.5f, bx - ox * 3f, by - oy * 3f, h + 9.5f, c(0x6f7784));
    }

    private void bayWindow(Scenery3D.Builder fb, float[] e, float ox, float oy, float low, float h) {
        float mx = (e[0] + e[2]) / 2f, my = (e[1] + e[3]) / 2f;
        float tx = (e[2] - e[0]) / 12f * 3.6f, ty = (e[3] - e[1]) / 12f * 3.6f, out = 3f;
        float z0 = Math.max(low, 0) + STOREY, z1 = h - 3;
        float ax = mx - tx + ox * out, ay = my - ty + oy * out, bx = mx + tx + ox * out, by = my + ty + oy * out;
        facadeWall(fb, ax, ay, bx, by, z0, z1, Color.WHITE);
        facadeWall(fb, mx - tx, my - ty, ax, ay, z0, z1, new Color(0.8f, 0.8f, 0.8f, 1f));
        facadeWall(fb, bx, by, mx + tx, my + ty, z0, z1, new Color(0.8f, 0.8f, 0.8f, 1f));
        Color trim = c(0xfafafa);
        quad(mx - tx, my - ty, z1, mx + tx, my + ty, z1, bx, by, z1, ax, ay, z1, trim);
        quad(mx - tx, my - ty, z0, ax, ay, z0, bx, by, z0, mx + tx, my + ty, z0, mul(trim, 0.6f));
    }

    private void neonSign(float[] e, float ox, float oy, float h, int seed) {
        Color[] neon = {c(0xff3d7f), c(0x33e6ff), c(0xffd23f), c(0x7dff4a), c(0xff7a26), c(0xc46bff)};
        Color col = neon[Math.floorMod(seed, neon.length)];
        float mx = (e[0] + e[2]) / 2f, my = (e[1] + e[3]) / 2f;
        float z0 = 13, z1 = Math.min(h - 3, 13 + 14 + Math.floorMod(seed, 4) * 6);
        boxFree(mx + ox * 1f, my + oy * 1f, mx + ox * 6f, my + oy * 6f, 0.9f, z0, z1, col);
        boxFree(mx + ox * 0.5f, my + oy * 0.5f, mx + ox * 6.4f, my + oy * 6.4f, 1.1f, z1, z1 + 0.8f, c(0x1e1a1a));
    }

    // ---------------------------------------------------------------- roofs

    private void roofFeatures(int id, Building b, float z, Random r) {
        int[] centre = b.cells.get(0);
        int best = -1;
        for (int[] cell : b.cells) {
            int d = depth(id, cell[0], cell[1]);
            if (d > best) {
                best = d;
                centre = cell;
            }
        }
        float cx = centre[0] * CELL + CELL / 2f, cy = worldH - centre[1] * CELL - CELL / 2f;
        if (b.tall && !b.mansard && best >= 1 && family != PARIS) {
            crown(b, cx, cy, z, best, r);
            return;
        }
        int[] other = b.cells.get(r.nextInt(b.cells.size()));
        float ox = other[0] * CELL + CELL / 2f, oy = worldH - other[1] * CELL - CELL / 2f;
        float roll = r.nextFloat();
        switch (family) {
            case PARIS:
                for (int k = 0; k < 1 + b.cells.size() / 4; k++) {
                    int[] cell = b.cells.get(r.nextInt(b.cells.size()));
                    chimney(cell[0] * CELL + CELL / 2f, worldH - cell[1] * CELL - CELL / 2f, z, c(0xd9cdb4), r);
                }
                break;
            case LONDON:
                for (int k = 0; k < 1 + b.cells.size() / 3; k++) {
                    int[] cell = b.cells.get(r.nextInt(b.cells.size()));
                    chimney(cell[0] * CELL + CELL / 2f, worldH - cell[1] * CELL - CELL / 2f, z,
                        pick(r, c(0x9c5a42), c(0xb89a6e), c(0x8e4a38)), r);
                }
                if (!b.mansard && roll < 0.3f) {
                    plant(cx, cy, z, r);
                }
                break;
            case NYC:
            case CHICAGO:
                if (b.cells.size() >= 3 && roll < 0.6f) {
                    waterTower(cx, cy, z, r);
                } else if (roll < 0.75f) {
                    billboard(ox, oy, z, r);
                } else if (roll < 0.9f) {
                    garden(cx, cy, z, r);
                } else {
                    plant(cx, cy, z, r);
                }
                if (r.nextBoolean()) {
                    plant(ox, oy, z, r);
                }
                break;
            case TOKYO:
                plant(cx, cy, z, r);
                if (roll < 0.4f) {
                    billboard(ox, oy, z, r);
                } else if (roll < 0.6f) {
                    waterTank(ox, oy, z);
                } else if (roll < 0.75f) {
                    solar(cx, cy, z, r);
                }
                break;
            case TAIPEI:
                waterTank(cx - 3, cy, z);
                if (r.nextBoolean()) {
                    waterTank(ox + 3, oy + 2, z);
                }
                if (roll < 0.4f) {
                    shack(ox, oy, z, r);
                } else if (roll < 0.6f) {
                    garden(cx, cy, z, r);
                }
                break;
            case SF:
                if (roll < 0.4f) {
                    chimney(ox, oy, z, c(0x9c5a42), r);
                } else if (roll < 0.6f) {
                    solar(cx, cy, z, r);
                } else if (roll < 0.75f) {
                    garden(cx, cy, z, r);
                }
                break;
            default:
                if (roll < 0.5f) {
                    plant(cx, cy, z, r);
                } else if (roll < 0.7f) {
                    solar(cx, cy, z, r);
                }
                break;
        }
    }

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

    /** a tower's top: stepped tiers and a spire, a dome, antennas, a glass lantern or a helipad */
    private void crown(Building b, float cx, float cy, float z, int depth, Random r) {
        float half = (depth + 0.5f) * CELL * 0.85f;
        Color body = b.deco ? c(0xe0d8c4) : jitter(b.stone, r, 0.08f);
        int kind = r.nextInt(5);
        float tier = z;
        int tiers = kind == 4 ? 0 : 1 + r.nextInt(3);
        for (int k = 0; k < tiers; k++) {
            float hh = 10f + r.nextInt(8);
            box(cx - half, cy - half, cx + half, cy + half, tier, tier + hh, k % 2 == 0 ? body : mul(body, 0.9f));
            box(cx - half - 0.15f, cy - half - 0.15f, cx + half + 0.15f, cy + half + 0.15f, tier + hh * 0.3f, tier + hh * 0.6f,
                b.deco ? c(0x2b3a4a) : c(0x3a4a5e));
            box(cx - half - 0.8f, cy - half - 0.8f, cx + half + 0.8f, cy + half + 0.8f, tier + hh, tier + hh + 1.2f, b.stone);
            tier += hh + 1.2f;
            half *= 0.62f + r.nextFloat() * 0.12f;
            if (half < 3f) {
                break;
            }
        }
        switch (kind) {
            case 0:
                pyramid(cx, cy, tier, tier + 20f + r.nextInt(16), Math.max(2.5f, half), 0.3f, b.deco ? c(0xd8dce2) : b.stone);
                break;
            case 1: {
                // a dome: stacked rings shrinking to a point, copper green or gold
                Color dome = r.nextBoolean() ? c(0x6fae96) : c(0xd8b04a);
                float rad = Math.max(3f, half * 0.9f);
                for (int k = 0; k < 5; k++) {
                    float a0 = k * 18f, a1 = (k + 1) * 18f;
                    prism(cx, cy, tier + MathUtils.sinDeg(a0) * rad, tier + MathUtils.sinDeg(a1) * rad,
                        MathUtils.cosDeg(a0) * rad, MathUtils.cosDeg(a1) * rad, 8, dome);
                }
                box(cx - 0.4f, cy - 0.4f, cx + 0.4f, cy + 0.4f, tier + rad, tier + rad + 8f, dome);
                break;
            }
            case 2:
                box(cx - 3f, cy - 0.6f, cx - 1.8f, cy + 0.6f, tier, tier + 26f + r.nextInt(10), c(0xe8e8e8));
                box(cx + 1.8f, cy - 0.6f, cx + 3f, cy + 0.6f, tier, tier + 18f + r.nextInt(10), c(0xe8e8e8));
                break;
            case 3: {
                // a lit glass lantern on top
                float l = Math.max(3f, half * 0.7f);
                box(cx - l, cy - l, cx + l, cy + l, tier, tier + 8f, c(0xffd36b));
                box(cx - l - 0.6f, cy - l - 0.6f, cx + l + 0.6f, cy + l + 0.6f, tier + 8f, tier + 9f, b.stone);
                break;
            }
            default: {
                // a helipad: a dark slab with its white H
                box(cx - half, cy - half, cx + half, cy + half, tier, tier + 1f, c(0x3a3a3e));
                float hs = half * 0.4f;
                box(cx - hs, cy - hs, cx - hs * 0.6f, cy + hs, tier + 1f, tier + 1.1f, c(0xf2f2f2));
                box(cx + hs * 0.6f, cy - hs, cx + hs, cy + hs, tier + 1f, tier + 1.1f, c(0xf2f2f2));
                box(cx - hs, cy - hs * 0.2f, cx + hs, cy + hs * 0.2f, tier + 1f, tier + 1.1f, c(0xf2f2f2));
                break;
            }
        }
    }

    private void waterTower(float cx, float cy, float z, Random r) {
        Color wood = pick(r, c(0x8c5e3c), c(0x7a5236), c(0x9a6a44), c(0x6a4a34)), iron = c(0x29231f);
        float legs = 5f + r.nextInt(3), rad = 4.2f + r.nextFloat() * 1.4f, tall = 8f + r.nextInt(4);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                box(cx + sx * rad * 0.7f - 0.5f, cy + sy * rad * 0.7f - 0.5f, cx + sx * rad * 0.7f + 0.5f, cy + sy * rad * 0.7f + 0.5f,
                    z, z + legs, iron);
            }
        }
        prism(cx, cy, z + legs, z + legs + tall, rad, rad, 8, wood);
        prism(cx, cy, z + legs + tall * 0.25f, z + legs + tall * 0.25f + 0.7f, rad + 0.15f, rad + 0.15f, 8, iron);
        prism(cx, cy, z + legs + tall * 0.7f, z + legs + tall * 0.7f + 0.7f, rad + 0.15f, rad + 0.15f, 8, iron);
        prism(cx, cy, z + legs + tall, z + legs + tall + 4f, rad + 0.6f, 0.6f, 8, c(0x4a3e36));
    }

    private void waterTank(float cx, float cy, float z) {
        box(cx - 3f, cy - 3f, cx + 3f, cy + 3f, z, z + 2f, c(0x4d4d52));
        prism(cx, cy, z + 2f, z + 9f, 3f, 3f, 8, c(0xc9ced4));
        prism(cx, cy, z + 9f, z + 10f, 3f, 1.5f, 8, c(0xa6abb2));
    }

    private void chimney(float cx, float cy, float z, Color brick, Random r) {
        float w = 1.2f + r.nextFloat(), l = 2f + r.nextFloat() * 2f, t = 4f + r.nextInt(4);
        box(cx - w, cy - l, cx + w, cy + l, z - 2f, z + t, brick);
        box(cx - w - 0.4f, cy - l - 0.4f, cx + w + 0.4f, cy + l + 0.4f, z + t, z + t + 0.8f, c(0xddd6c8));
        for (float k = -l + 0.9f; k < l; k += 1.8f) {
            box(cx - 0.6f, cy + k - 0.6f, cx + 0.6f, cy + k + 0.6f, z + t + 0.8f, z + t + 2.6f, c(0xb8603e));
        }
    }

    private void plant(float cx, float cy, float z, Random r) {
        Color grey = pick(r, c(0x9ea2a8), c(0x8a8e94), c(0xb4b8bc));
        float w = 2f + r.nextInt(3), l = 2f + r.nextInt(3);
        box(cx - w, cy - l, cx + w, cy + l, z, z + 4f + r.nextInt(3), grey);
        box(cx + w + 1f, cy - 1.5f, cx + w + 4f, cy + 1.5f, z, z + 2.4f, c(0xd8dade));
        if (r.nextBoolean()) {
            box(cx - 0.4f, cy - 0.4f, cx + 0.4f, cy + 0.4f, z + 4f, z + 14f + r.nextInt(6), c(0x46464c));
            box(cx - 2.5f, cy - 0.2f, cx + 2.5f, cy + 0.2f, z + 11f, z + 11.5f, c(0x46464c));
        }
    }

    private void billboard(float cx, float cy, float z, Random r) {
        Color panel = pick(r, c(0xd9433b), c(0xf2c84b), c(0x3f6fd0), c(0x2f9f6f), c(0xf08ab0), c(0xf2f2f2), c(0x7a4aa8));
        boolean alongX = r.nextBoolean();
        float w = 6f + r.nextInt(4);
        float x0 = alongX ? cx - w : cx - 0.4f, x1 = alongX ? cx + w : cx + 0.4f;
        float y0 = alongX ? cy - 0.4f : cy - w, y1 = alongX ? cy + 0.4f : cy + w;
        box(cx - 0.4f, cy - 0.4f, cx + 0.4f, cy + 0.4f, z, z + 6f, c(0x2a2a2e));
        box(x0 - 0.3f, y0 - 0.3f, x1 + 0.3f, y1 + 0.3f, z + 5f, z + 13f, c(0xf2f2f2));
        box(x0, y0, x1, y1, z + 5.6f, z + 12.4f, panel);
        box(alongX ? cx - w * 0.5f : x0 - 0.05f, alongX ? y0 - 0.05f : cy - w * 0.5f, alongX ? cx + w * 0.3f : x1 + 0.05f,
            alongX ? y1 + 0.05f : cy + w * 0.3f, z + 8f, z + 9.4f, mul(panel, 0.4f));
    }

    private void garden(float cx, float cy, float z, Random r) {
        box(cx - 5f, cy - 5f, cx + 5f, cy + 5f, z, z + 0.8f, c(0x5aa83f));
        for (int k = 0; k < 2 + r.nextInt(3); k++) {
            float tx = cx - 3f + r.nextFloat() * 6f, ty = cy - 3f + r.nextFloat() * 6f;
            prism(tx, ty, z + 0.8f, z + 5f + r.nextInt(3), 1.8f, 0.6f, 6, pick(r, c(0x2f7f2e), c(0x3f8f3a), c(0x4a9a3e)));
        }
    }

    private void solar(float cx, float cy, float z, Random r) {
        for (int k = 0; k < 2 + r.nextInt(2); k++) {
            float y = cy - 4f + k * 3.2f;
            quad(cx - 5f, y, z + 0.6f, cx + 5f, y, z + 0.6f, cx + 5f, y + 2.4f, z + 2f, cx - 5f, y + 2.4f, z + 2f, c(0x24345a));
            quad(cx - 5f, y + 2.4f, z, cx + 5f, y + 2.4f, z, cx + 5f, y + 2.4f, z + 2f, cx - 5f, y + 2.4f, z + 2f, c(0x55575c));
        }
    }

    private void shack(float cx, float cy, float z, Random r) {
        box(cx - 3f, cy - 2.5f, cx + 3f, cy + 2.5f, z, z + 4.5f, pick(r, c(0xb8b4ae), c(0x9a8a7a), c(0x8aa0a8)));
        quad(cx - 3.4f, cy - 2.9f, z + 4.5f, cx + 3.4f, cy - 2.9f, z + 5.2f, cx + 3.4f, cy + 2.9f, z + 5.2f, cx - 3.4f, cy + 2.9f, z + 4.5f,
            c(0x5a6a7a));
    }

    // ---------------------------------------------------------------- shapes

    private void quad(float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3,
            float x4, float y4, float z4, Color c) {
        float u = 0.5f;
        solid.quad(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, u, u, u, u, u, u, u, u, c);
    }

    private void box(float x0, float y0, float x1, float y1, float z0, float z1, Color c) {
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, c);
        quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, mul(c, 0.9f));
        quad(x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1, mul(c, 0.7f));
        quad(x1, y1, z0, x0, y1, z0, x0, y1, z1, x1, y1, z1, mul(c, 0.82f));
        quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, mul(c, 0.6f));
    }

    private void boxFree(float ax, float ay, float bx, float by, float half, float z0, float z1, Color c) {
        float dx = bx - ax, dy = by - ay, len = (float) Math.sqrt(dx * dx + dy * dy);
        float nx = -dy / len * half, ny = dx / len * half;
        float[][] p = {{ax + nx, ay + ny}, {bx + nx, by + ny}, {bx - nx, by - ny}, {ax - nx, ay - ny}};
        for (int i = 0; i < 4; i++) {
            float[] a = p[i], b2 = p[(i + 1) % 4];
            quad(a[0], a[1], z0, b2[0], b2[1], z0, b2[0], b2[1], z1, a[0], a[1], z1, mul(c, 0.75f + 0.08f * i));
        }
        quad(p[0][0], p[0][1], z1, p[1][0], p[1][1], z1, p[2][0], p[2][1], z1, p[3][0], p[3][1], z1, c);
    }

    private void prism(float cx, float cy, float z0, float z1, float r0, float r1, int n, Color c) {
        for (int i = 0; i < n; i++) {
            float a = i * 360f / n, b2 = (i + 1) * 360f / n;
            float f = 0.65f + 0.35f * (0.5f + 0.5f * MathUtils.cosDeg((a + b2) / 2f - 40f));
            quad(cx + MathUtils.cosDeg(a) * r0, cy + MathUtils.sinDeg(a) * r0, z0, cx + MathUtils.cosDeg(b2) * r0, cy + MathUtils.sinDeg(b2) * r0, z0,
                cx + MathUtils.cosDeg(b2) * r1, cy + MathUtils.sinDeg(b2) * r1, z1, cx + MathUtils.cosDeg(a) * r1, cy + MathUtils.sinDeg(a) * r1, z1,
                mul(c, f));
        }
        if (r1 > 0.8f) {
            for (int i = 0; i < n; i += 2) {
                float a = i * 360f / n, b2 = (i + 1) * 360f / n, c2 = (i + 2) * 360f / n;
                quad(cx, cy, z1, cx + MathUtils.cosDeg(a) * r1, cy + MathUtils.sinDeg(a) * r1, z1,
                    cx + MathUtils.cosDeg(b2) * r1, cy + MathUtils.sinDeg(b2) * r1, z1, cx + MathUtils.cosDeg(c2) * r1, cy + MathUtils.sinDeg(c2) * r1, z1, c);
            }
        }
    }

    private void pyramid(float cx, float cy, float z0, float z1, float r0, float r1, Color c) {
        float[][] k = {{1, -1, 1, 1, 0.9f}, {-1, 1, -1, -1, 0.7f}, {1, 1, -1, 1, 0.82f}, {-1, -1, 1, -1, 0.6f}};
        for (float[] s : k) {
            quad(cx + s[0] * r0, cy + s[1] * r0, z0, cx + s[2] * r0, cy + s[3] * r0, z0, cx + s[2] * r1, cy + s[3] * r1, z1,
                cx + s[0] * r1, cy + s[1] * r1, z1, mul(c, s[4]));
        }
    }

    // ---------------------------------------------------------------- for Scenery3D

    /** the roof's own picture sits this much higher on a mansard building (0 = flat) */
    public float roofLift(int gx, int gy) {
        Building b = at(gx, gy);
        return b != null && b.mansard ? b.slopeRise : 0f;
    }

    /** how far the roof picture is pulled in from a side that drops (for the mansard slope) */
    public float roofInset(int gx, int gy) {
        Building b = at(gx, gy);
        return b != null && b.mansard ? b.slopeInset : 0f;
    }

    private Building at(int gx, int gy) {
        return gx >= 0 && gy >= 0 && gx < gw && gy < gh && owner[gx][gy] >= 0 ? buildings.get(owner[gx][gy]) : null;
    }

    public boolean builds(int gx, int gy) {
        return at(gx, gy) != null;
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
