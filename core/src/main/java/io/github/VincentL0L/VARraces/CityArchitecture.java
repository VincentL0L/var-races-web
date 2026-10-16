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
    /** a facade picture covers 4 bays of 16 units across and 8 storeys of 12 up */
    private static final float TILE_W = 64f, TILE_H = 128f, STOREY = 12f;

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
        if (family != GENERIC) {
            outskirts(gw * CELL, worldH);
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

    /**
     * a facade picture being drawn: 128 x 256 pixels (sizes WebGL can repeat) covering 4 bays
     * of 16 units across and 128 units up, so one pixel is half a unit, as fine as the cars'
     * pixels. Storeys are 24 pixels; the ground floor is the bottom one, counted up from there.
     */
    private static final int PW = 128, PH = 256, BAYP = 32, STP = 24, GROUND = PH - STP;

    private static final class Art {
        final Pixmap p = new Pixmap(PW, PH, Pixmap.Format.RGBA8888);

        void px(int x, int y, Color c) {
            if (x >= 0 && y >= 0 && x < PW && y < PH) {
                p.setColor(c);
                p.drawPixel(x, y);
            }
        }

        void rect(int x, int y, int w, int h, Color c) {
            int x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(PW, x + w), y1 = Math.min(PH, y + h);
            if (x1 > x0 && y1 > y0) {
                p.setColor(c);
                p.fillRectangle(x0, y0, x1 - x0, y1 - y0);
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
        int material;
        Color wall;
        int wwMin, wwMax, whMin, whMax;
        switch (family) {
            case PARIS:
                material = r.nextFloat() < 0.6f ? STONE : STUCCO;
                wall = pick(r, c(0xeee5cf), c(0xe6dcc3), c(0xf2ead8), c(0xdcd3c2), c(0xe9dccb), c(0xd9cdb4), c(0xefe2d0), c(0xe4d6c0));
                wwMin = 13; wwMax = 17; whMin = 16; whMax = 19;
                break;
            case LONDON:
                material = r.nextFloat() < 0.65f ? BRICK : STUCCO;
                wall = material == BRICK ? pick(r, c(0xb89a6e), c(0xa8835a), c(0x9c5a42), c(0x8e4a38), c(0xc2a578), c(0x7f5f48))
                    : pick(r, c(0xf2efe6), c(0xefe7d4), c(0xe9e4da), c(0xdfe3e6));
                wwMin = 12; wwMax = 15; whMin = 15; whMax = 19;
                break;
            case NYC:
                material = r.nextFloat() < 0.6f ? BRICK : r.nextFloat() < 0.5f ? STONE : STUCCO;
                wall = material == BRICK ? pick(r, c(0xa8452f), c(0x8a3a2a), c(0xb5684a), c(0x7a3326), c(0xc08a5e), c(0x9a5a3e), c(0x6e4a3a))
                    : material == STONE ? pick(r, c(0x8a6a50), c(0x7a5a42), c(0xc9bba0), c(0xb7a88d)) : pick(r, c(0xd8d0c0), c(0xc9c2b5));
                wwMin = 11; wwMax = 15; whMin = 13; whMax = 17;
                break;
            case CHICAGO:
                material = r.nextFloat() < 0.55f ? BRICK : STONE;
                wall = material == BRICK ? pick(r, c(0x9a4a35), c(0xb3794f), c(0x7d3e2e), c(0xa88760), c(0x6f5544))
                    : pick(r, c(0xa9a59b), c(0x8f8b82), c(0xc2bdb1));
                wwMin = 12; wwMax = 18; whMin = 13; whMax = 16;
                break;
            case TOKYO:
                material = r.nextFloat() < 0.5f ? TILE : CONCRETE;
                wall = pick(r, c(0xf0eee8), c(0xe2ded6), c(0xc9c6c0), c(0xdcd4c4), c(0xd2dde3), c(0xe8d8d6), c(0xb8b4ae), c(0xece4cf), c(0xa8aeb4));
                wwMin = 16; wwMax = 24; whMin = 9; whMax = 12;
                break;
            case TAIPEI:
                material = r.nextFloat() < 0.4f ? TILE : CONCRETE;
                wall = pick(r, c(0xc9c4b8), c(0xb5b1a8), c(0xd8d2c4), c(0xa9a8a2), c(0xc4b9a4), c(0xd6cfc8), c(0xb9c0c2), c(0x9ea39b));
                wwMin = 13; wwMax = 20; whMin = 11; whMax = 13;
                break;
            case SF:
                material = r.nextFloat() < 0.75f ? SIDING : STUCCO;
                wall = pick(r, c(0xf7b7c4), c(0xb4e3cc), c(0xd3c0f2), c(0xfbe39a), c(0xa9d1f7), c(0xf9cba0), c(0xe9f2b0), c(0xf2f2f2),
                    c(0xc7e8f0), c(0xf0a8a0), c(0x9fd8c0));
                wwMin = 11; wwMax = 13; whMin = 15; whMax = 19;
                break;
            default:
                material = r.nextInt(6);
                wall = pick(r, c(0xd8d0c0), c(0xb8a890), c(0xa8452f), c(0xc9c6c0), c(0xe8e2d4), c(0x9a9a9a));
                wwMin = 11; wwMax = 17; whMin = 12; whMax = 16;
                break;
        }
        wall = jitter(wall, r, 0.05f);
        Color dark = mul(wall, 0.84f), light = mul(wall, 1.07f), shadow = mul(wall, 0.62f);
        b.stone = family == SF ? c(0xfafafa) : material == BRICK ? pick(r, c(0xe6dfd0), c(0xd9cfbd), c(0xcfc6b4))
            : mul(wall, r.nextBoolean() ? 1.1f : 0.86f);
        Color stoneDark = mul(b.stone, 0.78f);
        fillMaterial(a, material, wall, dark, light, r);

        // window settings for this building
        boolean ribbon = family == TOKYO && r.nextFloat() < 0.35f;
        int bays = ribbon ? 1 : r.nextFloat() < 0.15f ? 2 : 4;
        int bw = PW / bays;
        int ww = bays == 2 ? 26 + r.nextInt(19) : ribbon ? PW : wwMin + r.nextInt(wwMax - wwMin + 1);
        int wh = whMin + r.nextInt(whMax - whMin + 1);
        int top = Math.max(2, (STP - wh) / 2 - 1);
        boolean arch = (family == PARIS || family == LONDON || family == NYC || family == CHICAGO) && !ribbon && r.nextFloat() < 0.3f;
        int paneCols = ribbon ? 16 : ww >= 20 ? 3 : ww >= 12 ? (r.nextBoolean() ? 2 : 1) : 1;
        int paneRows = wh >= 16 ? (r.nextBoolean() ? 3 : 2) : wh >= 11 ? 2 : 1;
        boolean lintel = r.nextFloat() < 0.75f, keystone = lintel && r.nextFloat() < 0.4f, sill = r.nextFloat() < 0.8f;
        boolean shutters = (family == PARIS || family == SF || family == GENERIC) && bays == 4 && ww <= 15 && r.nextFloat() < 0.3f;
        boolean caged = family == TAIPEI && r.nextFloat() < 0.6f;
        boolean fireEscape = family == NYC && material != STUCCO && bays >= 2 && r.nextFloat() < 0.55f;
        boolean balconyAll = (family == TAIPEI || family == TOKYO) && r.nextFloat() < 0.45f;
        int balconyA = family == PARIS ? 1 : -1, balconyB = family == PARIS && r.nextBoolean() ? 4 : -1;
        boolean flowers = (family == PARIS || family == LONDON) && r.nextFloat() < 0.3f;
        boolean band = r.nextFloat() < 0.55f, dentils = band && (family == PARIS || family == NYC || family == LONDON) && r.nextBoolean();
        boolean curtains = r.nextFloat() < 0.4f;
        Color frame = family == LONDON || family == SF ? c(0xf6f4ee)
            : pick(r, c(0x1e1a18), c(0xf6f4ee), mul(wall, 0.5f), c(0x3a3330), c(0x4a5a4a), c(0x2b3a4a));
        Color glass = pick(r, c(0x2f3e55), c(0x26313f), c(0x354a5e), c(0x2b3a4a), c(0x3a4a58), c(0x2e4048));
        Color glassLight = mul(glass, 1.45f), glint = c(0xa9c8e8), lit = c(0xffd36b), litDeep = c(0xe8a848);
        Color curtain = pick(r, c(0xe8dcc0), c(0xb8463a), c(0x6a7a9a), c(0xd8d0e8), c(0x8a9a6a));
        Color shutter = pick(r, c(0x3f6f5a), c(0x47607a), c(0x7a4a3a), c(0x5a6b7a), c(0xd9d0b8), c(0x6a8a5a));
        Color rail = family == TAIPEI ? c(0xb8bcc0) : c(0x18181a);
        int neonBay = (family == TOKYO || family == TAIPEI) && r.nextFloat() < 0.45f ? r.nextInt(Math.max(1, bays)) : -1;
        Color neon = pick(r, c(0xff3d7f), c(0x33e6ff), c(0xffd23f), c(0x7dff4a), c(0xff7a26), c(0xc46bff));

        // storey s = 0 is the first floor above the shops; the picture runs up from the ground floor
        for (int s = 0; GROUND - (s + 1) * STP >= 0; s++) {
            int y0 = GROUND - (s + 1) * STP;
            if (band) {
                a.rect(0, y0, PW, 2, b.stone);
                a.rect(0, y0 + 2, PW, 1, stoneDark);
                if (dentils) {
                    for (int x = 0; x < PW; x += 3) {
                        a.px(x, y0 + 1, stoneDark);
                    }
                }
            }
            for (int k = 0; k < bays; k++) {
                int x0 = k * bw + (bw - ww) / 2;
                int wy = y0 + top + (band ? 1 : 0);
                int hh = wh;
                boolean on = r.nextInt(7) == 0;
                window(a, r, x0, wy, ww, hh, frame, glass, glassLight, glint, on ? lit : null, litDeep, paneCols, paneRows, arch,
                    curtains && !on && r.nextBoolean() ? curtain : null, wall);
                if (lintel) {
                    a.rect(x0 - 2, wy - 2, ww + 4, 2, b.stone);
                    a.rect(x0 - 2, wy - 2, ww + 4, 1, mul(b.stone, 1.05f));
                    if (keystone) {
                        a.rect(x0 + ww / 2 - 2, wy - 3, 4, 3, b.stone);
                        a.rect(x0 + ww / 2 - 2, wy - 3, 1, 3, stoneDark);
                    }
                }
                if (sill) {
                    a.rect(x0 - 2, wy + hh, ww + 4, 1, b.stone);
                    a.rect(x0 - 1, wy + hh + 1, ww + 2, 1, shadow);
                }
                if (shutters) {
                    for (int side = 0; side < 2; side++) {
                        int sx = side == 0 ? x0 - 4 : x0 + ww + 1;
                        a.rect(sx, wy, 3, hh, shutter);
                        for (int y = wy + 1; y < wy + hh; y += 2) {
                            a.rect(sx, y, 3, 1, mul(shutter, 0.75f));
                        }
                    }
                }
                if (caged) {
                    for (int cx = x0 - 1; cx <= x0 + ww; cx += 3) {
                        a.rect(cx, wy - 1, 1, hh + 2, c(0x8a8f96));
                    }
                    for (int cy = wy - 1; cy <= wy + hh; cy += 4) {
                        a.rect(x0 - 1, cy, ww + 2, 1, c(0x8a8f96));
                    }
                }
                if (flowers && r.nextBoolean()) {
                    a.rect(x0 - 1, wy + hh + 1, ww + 2, 2, c(0x5a3a2a));
                    for (int fx = x0 - 1; fx < x0 + ww + 1; fx++) {
                        a.px(fx, wy + hh, fx % 2 == 0 ? c(0x3f8f3a) : pick(r, c(0xd9433b), c(0xf2c84b), c(0xf08ab0), c(0xffffff)));
                    }
                }
                if ((family == TAIPEI || family == TOKYO) && r.nextInt(3) == 0) {
                    acUnit(a, x0 + ww - 6, wy + hh + 2);
                }
                if (family == TAIPEI && r.nextInt(4) == 0) {
                    a.rect(x0 - 1, wy + hh + 2, ww + 2, 1, c(0x3a3a3a));
                    for (int lx = x0; lx < x0 + ww - 2; lx += 3) {
                        if (r.nextBoolean()) {
                            a.rect(lx, wy + hh + 3, 2, 3, pick(r, c(0xf2f2f2), c(0x3f6fd0), c(0xd9433b), c(0xf2c84b), c(0x6cb84b), c(0xf08ab0)));
                        }
                    }
                }
            }
            if (balconyAll || s == balconyA || s == balconyB) {
                balcony(a, y0 + STP - 1, rail, wall, family == TAIPEI || family == TOKYO);
            }
            if (fireEscape) {
                fireEscape(a, bw + bw / 2 - 12, y0, s);
            }
            if (neonBay >= 0) {
                int nx = neonBay * bw + bw - 10;
                a.rect(nx, y0, 8, STP, c(0x1a1a1e));
                a.rect(nx + 1, y0, 6, STP, neon);
                for (int g = 0; g < 2; g++) {
                    glyph(a, nx + 2, y0 + 2 + g * 8, mul(neon, 0.35f), r);
                }
            }
        }
        groundFloor(a, b, r, wall, frame, glass, glint, lit, neon);
        return a.done();
    }

    /** fill the whole picture with a material's pattern */
    private static void fillMaterial(Art a, int material, Color wall, Color dark, Color light, Random r) {
        for (int y = 0; y < PH; y++) {
            for (int x = 0; x < PW; x++) {
                Color px = wall;
                switch (material) {
                    case BRICK: {
                        int course = y / 3;
                        int brick = (x + (course % 2) * 3) / 6;
                        if (y % 3 == 2 || (x + (course % 2) * 3) % 6 == 0) {
                            px = light;                                     // mortar
                        } else if (Math.floorMod(brick * 7 + course * 13, 5) == 0) {
                            px = dark;                                      // a darker brick here and there
                        }
                        break;
                    }
                    case STONE: {
                        int row = y / 6;
                        if (y % 6 == 5 || (x + (row % 2) * 6) % 12 == 0) {
                            px = dark;
                        } else if (y % 6 == 0) {
                            px = light;
                        }
                        break;
                    }
                    case SIDING:
                        px = y % 3 == 2 ? dark : y % 3 == 0 ? light : wall;
                        break;
                    case TILE:
                        px = (x % 3 == 0 || y % 3 == 0) ? light : wall;
                        break;
                    case CONCRETE:
                        px = (x % 24 == 23 || y % 18 == 17) ? dark : r.nextInt(14) == 0 ? dark : wall;
                        break;
                    default:
                        px = r.nextInt(30) == 0 ? dark : wall;
                        break;
                }
                a.px(x, y, px);
            }
        }
    }

    /** a window: frame, panes divided by glazing bars, glass shading and a glint (or lit), arched if asked */
    private static void window(Art a, Random r, int x0, int y0, int w, int h, Color frame, Color glass, Color glassLight,
            Color glint, Color lit, Color litDeep, int cols, int rows, boolean arch, Color curtain, Color wall) {
        a.rect(x0, y0, w, h, frame);
        for (int y = y0 + 1; y < y0 + h - 1; y++) {
            float t = (y - y0) / (float) h;
            Color g = lit != null ? (t < 0.5f ? lit : litDeep) : (t < 0.3f ? glassLight : glass);
            a.rect(x0 + 1, y, w - 2, 1, g);
        }
        if (lit == null) {
            for (int d = 0; d < 3; d++) {
                a.px(x0 + 2 + d, y0 + 4 - d, glint);
            }
        }
        if (curtain != null) {
            a.rect(x0 + 1, y0 + 1, 2, h - 2, curtain);
            a.rect(x0 + w - 3, y0 + 1, 2, h - 2, curtain);
        }
        for (int cI = 1; cI < cols; cI++) {
            a.rect(x0 + cI * w / cols, y0, 1, h, frame);
        }
        for (int rI = 1; rI < rows; rI++) {
            a.rect(x0, y0 + rI * h / rows, w, 1, frame);
        }
        if (arch && w >= 6) {
            // round the top corners off into an arch, wall showing outside it
            float rad = w / 2f;
            for (int y = 0; y < (int) rad; y++) {
                for (int x = 0; x < w; x++) {
                    float dx = x + 0.5f - rad, dy = rad - y - 0.5f;
                    if (dx * dx + dy * dy > rad * rad) {
                        a.px(x0 + x, y0 + y, wall);
                    }
                }
            }
        }
    }

    private static void acUnit(Art a, int x, int y) {
        a.rect(x, y, 6, 4, c(0xe4e6e8));
        a.rect(x, y + 3, 6, 1, c(0xa8acb2));
        for (int gx = x + 1; gx < x + 5; gx += 2) {
            a.rect(gx, y + 1, 1, 2, c(0x8a8f96));
        }
        a.px(x + 4, y + 4, c(0x6a6e74));
        a.px(x + 4, y + 6, c(0x6a6e74));
    }

    /** a balcony across a storey: a slab, a top rail, balusters (or a solid parapet) */
    private static void balcony(Art a, int y, Color rail, Color wall, boolean solid) {
        a.rect(0, y, PW, 1, mul(wall, 0.7f));
        if (solid) {
            a.rect(0, y - 4, PW, 4, mul(wall, 0.92f));
            a.rect(0, y - 4, PW, 1, mul(wall, 1.08f));
            return;
        }
        a.rect(0, y - 5, PW, 1, rail);
        for (int x = 0; x < PW; x += 2) {
            a.rect(x, y - 4, 1, 4, rail);
        }
    }

    /** one storey of a New York fire escape: platform, railing, and the stair down */
    private static void fireEscape(Art a, int x, int y0, int s) {
        Color iron = c(0x141414);
        int py = y0 + STP - 2;
        a.rect(x, py, 26, 2, iron);
        a.rect(x, py - 6, 26, 1, iron);
        for (int k = x; k <= x + 25; k += 4) {
            a.rect(k, py - 6, 1, 6, iron);
        }
        boolean right = s % 2 == 0;
        for (int d = 0; d < STP - 2; d++) {
            int sx = right ? x + 4 + d : x + 21 - d;
            a.px(sx, py - d, iron);
            a.px(sx, py - d - 1, iron);
            if (d % 3 == 0) {
                a.rect(sx - 1, py - d, 3, 1, iron);
            }
        }
    }

    /** a tiny made-up sign character: a few pixels in a 4 x 5 box */
    private static void glyph(Art a, int x, int y, Color c, Random r) {
        for (int gy = 0; gy < 5; gy++) {
            for (int gx = 0; gx < 4; gx++) {
                if (r.nextInt(5) < 2 || gx == 0 || gy == 2) {
                    a.px(x + gx, y + gy, c);
                }
            }
        }
    }

    /** the ground floor: a shop, a front door with its fanlight and steps, a garage, or an arcade */
    private void groundFloor(Art a, Building b, Random r, Color wall, Color frame, Color glass, Color glint, Color lit, Color neon) {
        int gy = GROUND;
        if (b.shop) {
            // the awning: stripes with a scalloped edge
            Color stripe = family == PARIS || family == LONDON ? mul(b.awning, 1.35f) : pick(r, mul(b.awning, 1.35f), c(0xf2f2f2));
            for (int x = 0; x < PW; x++) {
                Color col = (x / 4) % 2 == 0 ? b.awning : stripe;
                a.rect(x, gy, 1, 5, col);
                if (x % 4 != 3) {
                    a.px(x, gy + 5, col);
                }
            }
            a.rect(0, gy, PW, 1, mul(b.awning, 0.6f));
            // the sign, with letters
            Color sign = pick(r, c(0x1e1a18), mul(b.awning, 0.7f), c(0xf2f0e8), c(0x2b3a6b));
            Color letters = sign.r + sign.g + sign.b > 2f ? c(0x1e1a18) : (family == TOKYO || family == TAIPEI ? neon : c(0xf2c84b));
            a.rect(0, gy + 6, PW, 5, sign);
            for (int x = 8; x < PW - 8; x += 5) {
                if (r.nextInt(5) > 0) {
                    for (int y2 = 0; y2 < 3; y2++) {
                        for (int x2 = 0; x2 < 3; x2++) {
                            if (r.nextInt(3) > 0) {
                                a.px(x + x2, gy + 7 + y2, letters);
                            }
                        }
                    }
                }
            }
            // shop windows with goods in them, and the door
            a.rect(0, gy + 11, PW, 12, frame);
            Color display = r.nextBoolean() ? glass : mul(lit, 0.9f);
            for (int k = 0; k < 4; k++) {
                int x0 = k * BAYP;
                if (k == 1) {
                    a.rect(x0 + 10, gy + 11, 12, 13, mul(b.awning, 0.55f));
                    a.rect(x0 + 12, gy + 13, 8, 5, glass);
                    a.px(x0 + 19, gy + 19, c(0xf2c84b));
                } else {
                    a.rect(x0 + 2, gy + 12, BAYP - 4, 10, display);
                    a.rect(x0 + BAYP / 2, gy + 12, 1, 10, frame);
                    for (int d = 0; d < 3; d++) {
                        a.px(x0 + 3 + d, gy + 15 - d, glint);
                    }
                    for (int g = x0 + 4; g < x0 + BAYP - 4; g += 3) {
                        if (r.nextBoolean()) {
                            a.rect(g, gy + 19, 2, 3, pick(r, c(0xd9433b), c(0xf2c84b), c(0x3f6fd0), c(0xf2f2f2), c(0x6cb84b)));
                        }
                    }
                }
            }
            a.rect(0, gy + 23, PW, 1, mul(wall, 0.5f));
        } else if (family == SF && r.nextBoolean()) {
            a.rect(6, gy + 6, 48, 18, c(0xf2f0ea));
            for (int y = gy + 9; y < gy + 24; y += 3) {
                a.rect(6, y, 48, 1, c(0xcfccc4));
            }
            a.rect(8, gy + 7, 44, 2, c(0x3a4a58));
            for (int x = 8; x < 52; x += 11) {
                a.rect(x, gy + 7, 1, 2, c(0xf2f0ea));
            }
            door(a, 80, gy + 2, mul(wall, 0.5f), c(0xfafafa), glass, false, r);
        } else if (family == TAIPEI) {
            a.rect(0, gy, PW, STP, c(0x26262a));
            for (int x = 0; x < PW; x += BAYP) {
                a.rect(x, gy, 5, STP, wall);
                a.rect(x + 4, gy, 1, STP, mul(wall, 0.7f));
            }
            for (int k = 0; k < 4; k++) {
                a.rect(k * BAYP + 9, gy + 4, 16, 4, pick(r, neon, c(0xf2c84b), c(0xf2f2f2), c(0xd9433b)));
                a.px(k * BAYP + 16, gy + 1, c(0xffe8a0));
            }
        } else {
            a.rect(0, gy + STP - 1, PW, 1, mul(wall, 0.55f));
            Color doorColour = pick(r, c(0x1e1a18), c(0x8c2b2b), c(0x1f3b6b), c(0x2b5c3a), c(0x5a3a2a), c(0xc9792b), c(0x3f8f8f));
            door(a, 58, gy + 2, doorColour, b.stone, glass, family == LONDON || family == PARIS || family == NYC, r);
            for (int k : new int[] {0, 3}) {
                window(a, r, k * BAYP + 8, gy + 4, 16, 15, c(0xf6f4ee), glass, mul(glass, 1.45f), glint, null, null,
                    2, 2, false, null, wall);
            }
        }
    }

    /** a front door: panelled, with a fanlight over it if asked, a stone surround and steps */
    private static void door(Art a, int x, int gy, Color colour, Color stone, Color glass, boolean fanlight, Random r) {
        a.rect(x - 2, gy + 2, 16, 16, stone);
        a.rect(x, gy + 5, 12, 13, colour);
        a.rect(x + 2, gy + 7, 3, 4, mul(colour, 0.75f));
        a.rect(x + 7, gy + 7, 3, 4, mul(colour, 0.75f));
        a.rect(x + 2, gy + 12, 3, 4, mul(colour, 0.75f));
        a.rect(x + 7, gy + 12, 3, 4, mul(colour, 0.75f));
        a.px(x + 10, gy + 11, c(0xf2c84b));
        if (fanlight) {
            a.rect(x, gy + 3, 12, 2, glass);
            for (int k = x + 1; k < x + 12; k += 2) {
                a.px(k, gy + 3, stone);
            }
        }
        a.rect(x - 3, gy + 17, 18, 1, mul(stone, 0.8f));
    }

    /** a skyscraper's skin: a glass curtain wall, or Art Deco stone piers with recessed windows */
    private Texture towerFacade(Building b, Random r) {
        Art a = new Art();
        if (b.deco) {
            Color stone = pick(r, c(0xe8e0cc), c(0xd8cfb8), c(0xcfc6b0), c(0xbfb6a0));
            Color glass = pick(r, c(0x2b3a4a), c(0x33302a), c(0x26313f));
            Color spandrel = pick(r, c(0x5a4a3a), c(0x3a3a3e), c(0x6b5a40));
            b.stone = mul(stone, 1.05f);
            for (int y = 0; y < PH; y++) {
                for (int x = 0; x < PW; x++) {
                    int cx = x % 16, cy = y % STP;
                    Color px;
                    if (cx < 5) {
                        px = cx == 0 ? mul(stone, 1.08f) : cx == 4 ? mul(stone, 0.82f) : stone;     // the piers
                    } else if (cy >= STP - 6) {
                        // decorated spandrel panels: a little zigzag
                        px = ((cx + cy) % 4 == 0) ? mul(spandrel, 1.4f) : spandrel;
                    } else {
                        px = cy < 4 ? mul(glass, 1.4f) : glass;
                        if (cx == 5 || cx == 15) {
                            px = mul(stone, 0.6f);
                        }
                    }
                    a.px(x, y, px);
                }
            }
            for (int y = GROUND - STP; y >= 0; y -= STP) {
                for (int x = 5; x < PW; x += 16) {
                    if (r.nextInt(7) == 0) {
                        a.rect(x + 1, y + 4, 9, STP - 10, c(0xffd36b));
                    }
                }
            }
        } else {
            Color glass = pick(r, c(0x3f6f9a), c(0x2f7f7f), c(0x4a7a5a), c(0x8a6a40), c(0x9aa4ae), c(0x2a3038), c(0x5a8ab8), c(0x6a5a8a));
            Color mull = pick(r, c(0xd8dde2), c(0xf2f2f2), c(0x1e1e22), c(0xb08a50), c(0x8a9096));
            Color spandrel = r.nextBoolean() ? mul(glass, 0.65f) : mull;
            int every = pick(r, 4, 8, 8, 16);
            int spandrelRows = pick(r, 1, 3, 4);
            b.stone = mull;
            for (int y = 0; y < PH; y++) {
                for (int x = 0; x < PW; x++) {
                    int row = Math.floorMod(y - GROUND, STP);
                    Color px;
                    if (x % every == 0) {
                        px = mull;
                    } else if (row >= STP - spandrelRows) {
                        px = spandrel;
                    } else {
                        float t = row / (float) STP;
                        px = mul(glass, 1.2f - t * 0.35f);
                        // a sky reflection sweeping diagonally across the whole face
                        if (Math.floorMod(x + y, 64) < 6) {
                            px = mul(glass, 1.55f);
                        }
                    }
                    a.px(x, y, px);
                }
            }
            for (int y = GROUND - STP; y >= 0; y -= STP) {
                for (int x = 0; x < PW; x += every) {
                    if (r.nextInt(9) == 0) {
                        a.rect(x + 1, y + 1, every - 1, STP - spandrelRows - 1, c(0xffd36b));
                    }
                }
            }
        }
        // the lobby: tall glass, doors and a canopy
        int gy = GROUND;
        a.rect(0, gy, PW, STP, c(0x1e2228));
        for (int x = 0; x < PW; x += 16) {
            a.rect(x, gy, 2, STP, b.stone);
        }
        a.rect(0, gy, PW, 3, b.stone);
        a.rect(48, gy + 8, 32, 16, c(0x3a5068));
        a.rect(63, gy + 8, 2, 16, b.stone);
        a.rect(40, gy + 4, 48, 3, c(0x2a2a2e));
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
                    for (int floor = 1; floor <= 4; floor += 3) {
                        float z = (floor + 1) * STOREY + 0.4f;
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

    // ---------------------------------------------------------------- the city carrying on

    /**
     * blocks of the same city all round the map, out into the haze, so it never just stops:
     * streets in a grid, a pavement round each block, and a few buildings on it (sharing a
     * dozen facades), lower near the map and taller further out
     */
    private void outskirts(float mapW, float mapH) {
        Random r = new Random(city.hashCode() * 7L + 1);
        int variants = 12;
        Scenery3D.Builder[] fill = new Scenery3D.Builder[variants];
        Color[] stones = new Color[variants];
        for (int i = 0; i < variants; i++) {
            Building d = new Building();
            d.seed = r.nextInt();
            d.rnd = new Random(d.seed);
            boolean tower = i >= 8 && family != PARIS;
            d.tall = tower;
            d.deco = tower && (family == NYC || family == CHICAGO) && i % 2 == 0;
            d.shop = !tower && r.nextFloat() < 0.6f;
            d.awning = pick(r, c(0x8c2b2b), c(0x2b5c3a), c(0x1f3b6b), c(0x6b2b4e), c(0xc9792b));
            Texture t = tower ? towerFacade(d, d.rnd) : facade(d, d.rnd);
            fill[i] = new Scenery3D.Builder(list(t));
            stones[i] = d.stone;
        }
        float step = 140f, street = 26f, margin = 2000f;
        Color asphalt = c(0x45464c), pavement = c(0xa9a8a3);
        for (float bx = -margin; bx < mapW + margin; bx += step) {
            for (float by = -margin; by < mapH + margin; by += step) {
                if (bx + step > -40 && bx < mapW + 40 && by + step > -40 && by < mapH + 40) {
                    continue;                                  // the map itself
                }
                float dist = Math.max(Math.max(-bx - step, bx - mapW), Math.max(-by - step, by - mapH));
                // lifted a touch: these big flat pieces bow less than the hills under them
                quad(bx, by, 1.2f, bx + step, by, 1.2f, bx + step, by + step, 1.2f, bx, by + step, 1.2f, asphalt);
                float x0 = bx + street / 2f, y0 = by + street / 2f, x1 = bx + step - street / 2f, y1 = by + step - street / 2f;
                quad(x0, y0, 1.5f, x1, y0, 1.5f, x1, y1, 1.5f, x0, y1, 1.5f, pavement);
                // one building, two side by side, or four, set back a little from the kerb
                int split = dist > 1100 ? 1 : r.nextInt(3) == 0 ? 1 : r.nextBoolean() ? 2 : 4;
                float in = 4f;
                for (int k = 0; k < split; k++) {
                    float ax = x0 + in, ay = y0 + in, cx = x1 - in, cy = y1 - in;
                    if (split == 2) {
                        float mid = (ax + cx) / 2f;
                        if (k == 0) {
                            cx = mid - 1f;
                        } else {
                            ax = mid + 1f;
                        }
                    } else if (split == 4) {
                        float mx = (ax + cx) / 2f, my = (ay + cy) / 2f;
                        if (k % 2 == 0) {
                            cx = mx - 1f;
                        } else {
                            ax = mx + 1f;
                        }
                        if (k < 2) {
                            cy = my - 1f;
                        } else {
                            ay = my + 1f;
                        }
                    }
                    boolean towerHere = family != PARIS && r.nextFloat() < 0.12f + Math.min(0.35f, dist / 4000f);
                    int v = towerHere ? 8 + r.nextInt(4) : r.nextInt(8);
                    int storeys = towerHere ? 6 + r.nextInt(5) : 2 + r.nextInt(4);
                    float h = Math.min(TILE_H - 2, storeys * STOREY + 2);
                    if (towerHere && storeys > 9) {
                        h = 9 * STOREY + 2;
                    }
                    fillBlock(fill[v], ax, ay, cx, cy, h, stones[v], r);
                    if (towerHere && r.nextBoolean()) {
                        // a taller part rising out of the middle
                        float sx = (cx - ax) * 0.2f, sy = (cy - ay) * 0.2f;
                        fillBlock(fill[v], ax + sx, ay + sy, cx - sx, cy - sy, h + 24f + r.nextInt(3) * 12f, stones[v], r);
                    }
                }
            }
        }
        for (Scenery3D.Builder f : fill) {
            f.finish();
        }
    }

    /** one outskirts building: four walls of its facade, a cornice, a roof and maybe a little plant on it */
    private void fillBlock(Scenery3D.Builder fb, float x0, float y0, float x1, float y1, float h, Color stone, Random r) {
        float[] sh = {0.93f, 0.72f, 1f, 0.64f};
        float[][] e = {{x1, y0, x1, y1}, {x0, y1, x0, y0}, {x1, y1, x0, y1}, {x0, y0, x1, y0}};
        float[][] n = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int s = 0; s < 4; s++) {
            facadeWall(fb, e[s][0], e[s][1], e[s][2], e[s][3], 0f, h, new Color(sh[s], sh[s], sh[s], 1f));
            moulding(e[s], n[s][0], n[s][1], h - 1.4f, h, 1f, stone, sh[s], true);
        }
        quad(x0, y0, h, x1, y0, h, x1, y1, h, x0, y1, h, mul(stone, 0.7f));
        if (r.nextInt(3) == 0) {
            float cx = (x0 + x1) / 2f, cy = (y0 + y1) / 2f;
            box(cx - 3f, cy - 3f, cx + 3f, cy + 3f, h, h + 4f, c(0x9ea2a8));
        }
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
