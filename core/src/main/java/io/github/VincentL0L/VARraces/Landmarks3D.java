package io.github.VincentL0L.VARraces;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.MathUtils;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * Each city's famous landmarks, built in 3D for the first-person view, standing where
 * tools/MakeCities.java and MakeSanFrancisco.java painted them on the map (the same map
 * coordinates, y up). Made of plain coloured boxes and tapering blocks, shaded by which way
 * each face points, and drawn with the scenery's haze like everything else.
 *
 * The map pictures draw the tall ones as a side view rising up the picture from their
 * base, so the 3D model stands on that base and the block-finder (heights.png) is told to
 * leave the area clear ({@link #clearAreas()}).
 */
public class Landmarks3D {
    private final List<Mesh> meshes = new ArrayList<>();
    private final Scenery3D.Builder b = new Scenery3D.Builder(meshes);
    private final Texture white;
    /** map areas (x0, y0, x1, y1) where the map's own picture of a landmark is */
    private final List<float[]> clear = new ArrayList<>();

    public Landmarks3D(TrackMap map) {
        Pixmap p = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        p.setColor(Color.WHITE);
        p.fill();
        white = new Texture(p);
        p.dispose();
        switch (map.id) {
            case TrackMap.TOKYO:
                tokyoTower(1495, 300);
                break;
            case TrackMap.NEW_YORK:
                empireState(560, 420);
                flatiron(1355, 320);
                liberty(330, 60);
                break;
            case TrackMap.CHICAGO:
                willis(420, 320);
                bean(1300, 440);
                ferrisWheel(1790, 850, 33, true, c(0xd9433b));
                break;
            case TrackMap.LONDON:
                parliament(360, 520);
                bigBen(330, 515);
                ferrisWheel(1380, 830, 66, true, c(0xd9dde3));
                towerBridge(1760, 652, 733);
                break;
            case TrackMap.PARIS:
                eiffel(1100, 650);
                arc(1430, 370);
                break;
            case TrackMap.TAIPEI:
                taipei101(1540, 760);
                break;
            case TrackMap.SAN_FRANCISCO:
                goldenGate();
                transamerica(1440, 370);
                coit(1588, 555);
                ferryBuilding();
                alcatraz(1000, 1015);
                break;
            default:
                break;
        }
        b.finish();
    }

    /** @return the areas the height map should leave flat */
    public List<float[]> clearAreas() {
        return clear;
    }

    public boolean isEmpty() {
        return meshes.isEmpty();
    }

    public List<Mesh> meshes() {
        return meshes;
    }

    public Texture texture() {
        return white;
    }

    public void dispose() {
        for (Mesh m : meshes) {
            m.dispose();
        }
        white.dispose();
    }

    // ---------------------------------------------------------------- the landmarks

    /** Tokyo Tower: an orange and white lattice tower with two observation decks */
    private void tokyoTower(float x, float y) {
        clearAbove(x, y, 60, 240);
        float h = 230f;
        int bands = 10;
        for (int i = 0; i < bands; i++) {
            float z0 = h * i / bands, z1 = h * (i + 1) / bands;
            float r0 = halfAt(z0 / h, 42f), r1 = halfAt(z1 / h, 42f);
            Color col = i % 2 == 0 ? c(0xf05a28) : c(0xf2efe8);
            legsFrustum(x, y, z0, z1, r0, r1, Math.max(2f, r0 * 0.22f), col);
        }
        // the main deck and the special deck
        deck(x, y, 0.38f * h, halfAt(0.38f, 42f) + 6f, 9f, c(0x3a4a5e), c(0xf2efe8));
        deck(x, y, 0.72f * h, halfAt(0.72f, 42f) + 3f, 6f, c(0x3a4a5e), c(0xf2efe8));
        box(x - 1.5f, y - 1.5f, h, x + 1.5f, y + 1.5f, h + 25f, c(0xf2efe8));
    }

    /** the Empire State Building: stepped limestone tiers and its mast */
    private void empireState(float x, float y) {
        clearAbove(x, y, 45, 240);
        float[][] tiers = {{39, 0, 120}, {27, 120, 175}, {18, 175, 215}, {9, 215, 235}};
        for (float[] t : tiers) {
            tower(x, y, t[0], t[0] * 0.7f, t[1], t[2], c(0xcfc8b8), true);
        }
        box(x - 4f, y - 4f, 235f, x + 4f, y + 4f, 252f, c(0xb7b0a2));
        box(x - 1f, y - 1f, 252f, x + 1f, y + 1f, 290f, c(0xe8e2d4));
    }

    /** the Flatiron: a wedge of a building on its triangular block */
    private void flatiron(float x, float y) {
        clear.add(new float[] {x - 4, y - 70, x + 46, y + 4});
        // the picture's wedge runs downward from (x, y), widening: a stack of slices
        for (int i = 0; i < 11; i++) {
            float y0 = y - i * 6f, y1 = y - (i + 1) * 6f;
            float w = 6f + (i + 1) * 3.4f;
            tower2(x, y1, x + w, y0, 0f, 72f, c(0xdccaa6), i == 0);
        }
    }

    /** the Statue of Liberty: green, torch held high, on her star-shaped pedestal */
    private void liberty(float x, float y) {
        Color stone = c(0xc9bfa9), green = c(0x6fb8a0);
        frustum(x, y, 0f, 6f, 18f, 16f, stone);
        box(x - 8f, y - 8f, 6f, x + 8f, y + 8f, 30f, stone);
        frustum(x, y, 30f, 34f, 9f, 7f, stone);
        frustum(x, y, 34f, 58f, 5f, 3.2f, green);       // robes
        box(x - 2f, y - 2f, 58f, x + 2f, y + 2f, 63f, green);   // head
        for (int k = 0; k < 7; k++) {                  // the crown's rays
            float a = k * 51f;
            box(x + MathUtils.cosDeg(a) * 2.6f - 0.5f, y + MathUtils.sinDeg(a) * 2.6f - 0.5f, 62f,
                x + MathUtils.cosDeg(a) * 2.6f + 0.5f, y + MathUtils.sinDeg(a) * 2.6f + 0.5f, 65f, green);
        }
        box(x + 3f, y - 1f, 52f, x + 5f, y + 1f, 70f, green);   // the raised arm
        box(x + 2.5f, y - 1.5f, 70f, x + 5.5f, y + 1.5f, 73f, c(0xffd84a));   // the torch flame
    }

    /** Willis Tower: nine black bundled tubes stepping up, two white antennas */
    private void willis(float x, float y) {
        clearAbove(x, y, 42, 220);
        float s = 13f;
        float[][] tubes = {{-1, -1, 150}, {0, -1, 210}, {1, -1, 170}, {-1, 0, 190}, {0, 0, 245},
            {1, 0, 210}, {-1, 1, 170}, {0, 1, 230}, {1, 1, 150}};
        for (float[] t : tubes) {
            float cx = x + t[0] * s, cy = y + 20f + t[1] * s;
            tower(cx, cy, s / 2f, s / 2f, 0f, t[2], c(0x30333b), true);
        }
        box(x - 4f, y + 18f, 245f, x - 2.5f, y + 19.5f, 300f, c(0xf2f2f2));
        box(x + 2.5f, y + 18f, 245f, x + 4f, y + 19.5f, 290f, c(0xf2f2f2));
    }

    /** Cloud Gate (the Bean): a squashed, shiny steel blob with an arch under it */
    private void bean(float x, float y) {
        Color steel = c(0xc3cbd6);
        for (int i = 0; i < 6; i++) {
            float z0 = 4f + i * 3f, z1 = z0 + 3f;
            float t = (z0 + 1.5f - 12f) / 10f;
            float k = (float) Math.sqrt(Math.max(0.05f, 1f - t * t));
            Color shade = new Color(steel).lerp(c(0x7fc0ff), i / 10f);
            box(x - 27f * k, y - 15f * k, z0, x + 27f * k, y + 15f * k, z1, shade);
        }
        box(x - 27f, y - 9f, 0f, x - 16f, y + 9f, 4f, steel);
        box(x + 16f, y - 9f, 0f, x + 27f, y + 9f, 4f, steel);
    }

    /** a Ferris wheel standing on its legs (Navy Pier's, or the London Eye) */
    private void ferrisWheel(float x, float y, float r, boolean alongX, Color rim) {
        float hub = r + 6f;
        int spokes = 18;
        for (int k = 0; k < 60; k++) {
            float a = k * 6f, a2 = a + 6f;
            float u = MathUtils.cosDeg(a) * r, v = MathUtils.sinDeg(a) * r + hub;
            float u2 = MathUtils.cosDeg(a2) * r, v2 = MathUtils.sinDeg(a2) * r + hub;
            plank(x, y, u, v, u2, v2, 1.6f, alongX, rim);
        }
        for (int k = 0; k < spokes; k++) {
            float a = k * 360f / spokes;
            plank(x, y, 0f, hub, MathUtils.cosDeg(a) * r, MathUtils.sinDeg(a) * r + hub, 0.5f, alongX, c(0xb7bec8));
            // a pod at the rim
            float pu = MathUtils.cosDeg(a) * (r + 2f), pv = MathUtils.sinDeg(a) * (r + 2f) + hub;
            float px = alongX ? x + pu : x, py = alongX ? y : y + pu;
            box(px - 2f, py - 2f, pv - 2f, px + 2f, py + 2f, pv + 2f, c(0x9fd3ef));
        }
        // the A-frame legs
        plank(x, y, 0f, hub, -r * 0.45f, 0f, 2.4f, alongX, c(0x8a8f99));
        plank(x, y, 0f, hub, r * 0.45f, 0f, 2.4f, alongX, c(0x8a8f99));
    }

    /** the Palace of Westminster: a long gothic block with pinnacles */
    private void parliament(float x, float y) {
        clear.add(new float[] {x, y - 4, x + 510, y + 70});
        Color stone = c(0xd8c48e);
        box(x, y, 0f, x + 510f, y + 42f, 48f, stone);
        for (float px = x + 6f; px < x + 505f; px += 36f) {
            box(px, y - 1f, 48f, px + 6f, y + 5f, 60f, c(0xb79f6c));
        }
        tower(x + 495f, y + 21f, 18f, 18f, 0f, 110f, stone, true);   // Victoria Tower
    }

    /** Big Ben (the Elizabeth Tower): clock faces near the top, and its spire */
    private void bigBen(float x, float y) {
        clearAbove(x, y, 20, 215);
        Color stone = c(0xd2bd86);
        tower(x, y, 12f, 12f, 0f, 135f, stone, false);
        box(x - 13f, y - 13f, 135f, x + 13f, y + 13f, 160f, c(0xe9d9a6));
        // a clock face on each side
        Color face = c(0xf6f1df);
        box(x - 8f, y - 13.4f, 139f, x + 8f, y - 13f, 155f, face);
        box(x - 8f, y + 13f, 139f, x + 8f, y + 13.4f, 155f, face);
        box(x - 13.4f, y - 8f, 139f, x - 13f, y + 8f, 155f, face);
        box(x + 13f, y - 8f, 139f, x + 13.4f, y + 8f, 155f, face);
        frustum(x, y, 160f, 205f, 12f, 1f, c(0x5f6e5a));
    }

    /** Tower Bridge: two stone towers over the road with blue walkways across the top */
    private void towerBridge(float x, float y0, float y1) {
        Color stone = c(0xd6c6a3), blue = c(0x4f7fbf);
        float inner = 68f, outer = 104f;
        for (float ty : new float[] {y0, y1}) {
            for (int side = -1; side <= 1; side += 2) {
                float a = x + side * inner, bb = x + side * outer;
                box(Math.min(a, bb), ty - 20f, 0f, Math.max(a, bb), ty + 20f, 105f, stone);
                frustum((a + bb) / 2f, ty, 105f, 125f, 12f, 2f, c(0x6d7b88));
            }
            // the arch over the road
            box(x - inner, ty - 20f, 60f, x + inner, ty + 20f, 105f, stone);
        }
        // the high walkways
        box(x - outer, y0 + 20f, 92f, x - outer + 14f, y1 - 20f, 100f, blue);
        box(x + outer - 14f, y0 + 20f, 92f, x + outer, y1 - 20f, 100f, blue);
    }

    /** the Eiffel Tower: four splayed iron legs meeting at the first level, a needle above */
    private void eiffel(float x, float y) {
        clearAbove(x, y, 66, 225);
        Color iron = c(0x7a6550), light = c(0xa58b6e);
        float h = 260f;
        int bands = 14;
        for (int i = 0; i < bands; i++) {
            float z0 = h * i / bands, z1 = h * (i + 1) / bands;
            float r0 = halfAt(z0 / h, 62f, 2.2f), r1 = halfAt(z1 / h, 62f, 2.2f);
            float leg = z0 < h * 0.22f ? Math.max(4f, r0 * 0.3f) : r0;
            legsFrustum(x, y, z0, z1, r0, r1, leg, i % 2 == 0 ? iron : light);
        }
        deck(x, y, h * 0.22f, halfAt(0.22f, 62f, 2.2f) + 4f, 6f, c(0x5a4a3c), light);
        deck(x, y, h * 0.48f, halfAt(0.48f, 62f, 2.2f) + 2f, 5f, c(0x5a4a3c), light);
        box(x - 1.5f, y - 1.5f, h, x + 1.5f, y + 1.5f, h + 20f, iron);
    }

    /** the Arc de Triomphe in the middle of its round plaza */
    private void arc(float x, float y) {
        clear.add(new float[] {x - 40, y - 30, x + 40, y + 30});
        Color stone = c(0xe3d6b8);
        box(x - 25f, y - 12f, 0f, x - 9f, y + 12f, 40f, stone);
        box(x + 9f, y - 12f, 0f, x + 25f, y + 12f, 40f, stone);
        box(x - 25f, y - 12f, 28f, x + 25f, y + 12f, 52f, stone);
        box(x - 26f, y - 13f, 52f, x + 26f, y + 13f, 55f, c(0xcbbd9e));
    }

    /** Taipei 101: eight flared green-glass sections stacked like bamboo, and its spire */
    private void taipei101(float x, float y) {
        clearAbove(x, y, 32, 250);
        Color glass = c(0x5f9a8e), light = c(0x9fc7bd);
        tower(x, y, 27f, 27f, 0f, 48f, glass, true);
        frustum(x, y, 48f, 54f, 27f, 18f, light);
        float z = 54f;
        for (int seg = 0; seg < 8; seg++) {
            frustum(x, y, z, z + 22f, 15f, 21f, seg % 2 == 0 ? glass : c(0x6aa89a));
            frustum(x, y, z + 22f, z + 24f, 21f, 15f, light);
            z += 24f;
        }
        frustum(x, y, z, z + 14f, 12f, 8f, glass);
        box(x - 2f, y - 2f, z + 14f, x + 2f, y + 2f, z + 50f, c(0xd9e4e0));
    }

    /** the Golden Gate: two tall orange towers, main cables and suspender ropes */
    private void goldenGate() {
        Color orange = c(0xc0362c), dark = c(0x8f2a22);
        float deckY = 880f, half = 84f, legY = half + 14f, top = 220f;
        for (float tx : new float[] {640f, 1160f}) {
            for (int side = -1; side <= 1; side += 2) {
                float ly = deckY + side * legY;
                tower2(tx - 10f, ly - 9f, tx + 10f, ly + 9f, 0f, top, orange, false);
            }
            for (float z : new float[] {40f, 110f, 170f, 212f}) {
                box(tx - 7f, deckY - legY, z, tx + 7f, deckY + legY, z + 10f, dark);
            }
        }
        // the main cables sag between the towers and down to each shore; ropes hang to the deck
        float[] xs = {400f, 640f, 1160f, 1420f};
        for (int side = -1; side <= 1; side += 2) {
            float cy = deckY + side * (half + 4f);
            for (int span = 0; span < 3; span++) {
                float a = xs[span], bx = xs[span + 1];
                int steps = (int) ((bx - a) / 12f);
                for (int k = 0; k < steps; k++) {
                    float x0 = a + (bx - a) * k / steps, x1 = a + (bx - a) * (k + 1) / steps;
                    float z0 = cable(span, (x0 - a) / (bx - a), top), z1 = cable(span, (x1 - a) / (bx - a), top);
                    plankX(x0, z0, x1, z1, cy, 1.4f, orange);
                    if (k % 2 == 0) {
                        box(x0 - 0.3f, cy - 0.3f, 6f, x0 + 0.3f, cy + 0.3f, z0, orange);
                    }
                }
            }
        }
    }

    /** the cable's height across a span: a sag from tower top to tower top, or down to a shore */
    private static float cable(int span, float t, float top) {
        if (span == 1) {
            return 18f + (top - 18f) * (float) Math.pow(2f * t - 1f, 2);
        }
        return span == 0 ? 10f + (top - 10f) * t * t : 10f + (top - 10f) * (1f - t) * (1f - t);
    }

    /** the Transamerica Pyramid: a slender white pyramid with two shoulders */
    private void transamerica(float x, float y) {
        clear.add(new float[] {x - 40, y - 40, x + 40, y + 40});
        Color white = c(0xf1eee6);
        frustum(x, y, 0f, 200f, 30f, 3f, white);
        box(x - 22f, y - 6f, 120f, x - 14f, y + 6f, 140f, c(0xdcd8cc));
        box(x + 14f, y - 6f, 120f, x + 22f, y + 6f, 140f, c(0xdcd8cc));
        frustum(x, y, 200f, 230f, 3f, 0.5f, white);
    }

    /** Coit Tower on Telegraph Hill: a white fluted column */
    private void coit(float x, float y) {
        clear.add(new float[] {x - 22, y - 22, x + 22, y + 22});
        frustum(x, y, 0f, 75f, 14f, 13f, c(0xf0eadb));
        frustum(x, y, 75f, 82f, 15f, 15f, c(0xd9d2c0));
    }

    /** the Ferry Building on the Embarcadero, and its clock tower */
    private void ferryBuilding() {
        clear.add(new float[] {1825, 375, 1895, 645});
        box(1830f, 380f, 0f, 1890f, 640f, 34f, c(0xdccdb0));
        box(1832f, 382f, 34f, 1888f, 638f, 40f, c(0x9a8a70));
        float cx = 1860f, cy = 510f;
        tower(cx, cy, 12f, 12f, 40f, 105f, c(0xc7b08a), false);
        box(cx - 12.5f, cy - 6f, 85f, cx + 12.5f, cy + 6f, 97f, c(0xf6f1df));
        frustum(cx, cy, 105f, 125f, 9f, 1f, c(0x2f6f62));
    }

    /** Alcatraz: the cell house on the rock, and its lighthouse */
    private void alcatraz(float x, float y) {
        box(x - 27f, y - 9f, 0f, x + 21f, y + 6f, 22f, c(0xece8de));
        box(x - 27f, y - 9f, 22f, x + 21f, y + 6f, 25f, c(0xb9b2a4));
        tower(x + 36f, y + 12f, 3f, 3f, 0f, 42f, c(0xf2f2f2), false);
        box(x + 33.5f, y + 9.5f, 42f, x + 38.5f, y + 14.5f, 47f, c(0xffe08a));
    }

    // ---------------------------------------------------------------- building blocks

    private void clearAbove(float x, float y, float half, float up) {
        clear.add(new float[] {x - half, y - 6f, x + half, y + up});
    }

    private static float halfAt(float t, float base) {
        return halfAt(t, base, 1.6f);
    }

    /** a tower's half-width at a share of its height: wide feet, narrowing fast */
    private static float halfAt(float t, float base, float power) {
        return base * (float) Math.pow(1f - t, power) + 3f;
    }

    private static Color c(int rgb) {
        return new Color(((rgb >> 16) & 0xff) / 255f, ((rgb >> 8) & 0xff) / 255f, (rgb & 0xff) / 255f, 1f);
    }

    private static Color shade(Color col, float f) {
        return new Color(col.r * f, col.g * f, col.b * f, 1f);
    }

    /** an axis-aligned box, its faces shaded: top brightest, the sides by direction */
    private void box(float x0, float y0, float z0, float x1, float y1, float z1, Color col) {
        float u = 0.5f;
        Color top = col, east = shade(col, 0.9f), north = shade(col, 0.8f), west = shade(col, 0.68f), south = shade(col, 0.6f);
        b.quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, u, u, u, u, u, u, u, u, top);
        b.quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, u, u, u, u, u, u, u, u, east);
        b.quad(x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1, u, u, u, u, u, u, u, u, west);
        b.quad(x1, y1, z0, x0, y1, z0, x0, y1, z1, x1, y1, z1, u, u, u, u, u, u, u, u, north);
        b.quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, u, u, u, u, u, u, u, u, south);
    }

    /** a tapering square block centred on (x, y): half-widths r0 at z0 to r1 at z1 */
    private void frustum(float x, float y, float z0, float z1, float r0, float r1, Color col) {
        float u = 0.5f;
        b.quad(x - r1, y - r1, z1, x + r1, y - r1, z1, x + r1, y + r1, z1, x - r1, y + r1, z1, u, u, u, u, u, u, u, u, col);
        b.quad(x + r0, y - r0, z0, x + r0, y + r0, z0, x + r1, y + r1, z1, x + r1, y - r1, z1, u, u, u, u, u, u, u, u, shade(col, 0.9f));
        b.quad(x - r0, y + r0, z0, x - r0, y - r0, z0, x - r1, y - r1, z1, x - r1, y + r1, z1, u, u, u, u, u, u, u, u, shade(col, 0.68f));
        b.quad(x + r0, y + r0, z0, x - r0, y + r0, z0, x - r1, y + r1, z1, x + r1, y + r1, z1, u, u, u, u, u, u, u, u, shade(col, 0.8f));
        b.quad(x - r0, y - r0, z0, x + r0, y - r0, z0, x + r1, y - r1, z1, x - r1, y - r1, z1, u, u, u, u, u, u, u, u, shade(col, 0.6f));
    }

    /** the four corner legs of an open lattice tower, between z0 and z1 */
    private void legsFrustum(float x, float y, float z0, float z1, float r0, float r1, float leg, Color col) {
        if (leg >= r0 * 0.9f) {
            frustum(x, y, z0, z1, r0, r1, col);
            return;
        }
        float l1 = leg * r1 / Math.max(0.01f, r0);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                float cx0 = x + sx * (r0 - leg / 2f), cy0 = y + sy * (r0 - leg / 2f);
                float cx1 = x + sx * (r1 - l1 / 2f), cy1 = y + sy * (r1 - l1 / 2f);
                slantedPost(cx0, cy0, cx1, cy1, z0, z1, leg / 2f, l1 / 2f, col);
            }
        }
        // a cross-brace band round the tower at the top of the section
        float t = Math.max(1f, (z1 - z0) * 0.12f);
        frame(x, y, z1 - t, z1, r1, Math.max(1.2f, l1 * 0.5f), col);
    }

    /** a square post leaning from one centre to another */
    private void slantedPost(float x0, float y0, float x1, float y1, float z0, float z1, float h0, float h1, Color col) {
        float u = 0.5f;
        b.quad(x0 + h0, y0 - h0, z0, x0 + h0, y0 + h0, z0, x1 + h1, y1 + h1, z1, x1 + h1, y1 - h1, z1, u, u, u, u, u, u, u, u, shade(col, 0.9f));
        b.quad(x0 - h0, y0 + h0, z0, x0 - h0, y0 - h0, z0, x1 - h1, y1 - h1, z1, x1 - h1, y1 + h1, z1, u, u, u, u, u, u, u, u, shade(col, 0.68f));
        b.quad(x0 + h0, y0 + h0, z0, x0 - h0, y0 + h0, z0, x1 - h1, y1 + h1, z1, x1 + h1, y1 + h1, z1, u, u, u, u, u, u, u, u, shade(col, 0.8f));
        b.quad(x0 - h0, y0 - h0, z0, x0 + h0, y0 - h0, z0, x1 + h1, y1 - h1, z1, x1 - h1, y1 - h1, z1, u, u, u, u, u, u, u, u, shade(col, 0.6f));
    }

    /** a square ring of beams round (x, y) at half-width r */
    private void frame(float x, float y, float z0, float z1, float r, float t, Color col) {
        box(x - r, y - r, z0, x + r, y - r + t, z1, col);
        box(x - r, y + r - t, z0, x + r, y + r, z1, col);
        box(x - r, y - r, z0, x - r + t, y + r, z1, col);
        box(x + r - t, y - r, z0, x + r, y + r, z1, col);
    }

    /** an observation deck: a dark glass band with a light roof */
    private void deck(float x, float y, float z, float r, float h, Color glass, Color roof) {
        box(x - r, y - r, z, x + r, y + r, z + h * 0.75f, glass);
        box(x - r - 1f, y - r - 1f, z + h * 0.75f, x + r + 1f, y + r + 1f, z + h, roof);
    }

    /** a square tower block: half-widths hx, hy, with window bands if asked */
    private void tower(float x, float y, float hx, float hy, float z0, float z1, Color col, boolean windows) {
        tower2(x - hx, y - hy, x + hx, y + hy, z0, z1, col, windows);
    }

    private void tower2(float x0, float y0, float x1, float y1, float z0, float z1, Color col, boolean windows) {
        box(x0, y0, z0, x1, y1, z1, col);
        if (windows) {
            Color glass = shade(c(0x46546a), 1f);
            for (float z = z0 + 6f; z + 3f < z1; z += 9f) {
                box(x0 - 0.2f, y0 - 0.2f, z, x1 + 0.2f, y1 + 0.2f, z + 3f, glass);
            }
        }
    }

    /** a thin beam in the upright plane of a wheel, from (u0, v0) to (u1, v1) */
    private void plank(float x, float y, float u0, float v0, float u1, float v1, float t, boolean alongX, Color col) {
        float du = u1 - u0, dv = v1 - v0, len = (float) Math.hypot(du, dv);
        if (len < 0.01f) {
            return;
        }
        float nu = -dv / len * t / 2f, nv = du / len * t / 2f;
        float d = t / 2f, uu = 0.5f;
        float[] a = {u0 + nu, v0 + nv}, bb = {u1 + nu, v1 + nv}, cc = {u1 - nu, v1 - nv}, dd = {u0 - nu, v0 - nv};
        float[][] pts = {a, bb, cc, dd};
        for (int side = -1; side <= 1; side += 2) {
            float[] q = new float[12];
            for (int i = 0; i < 4; i++) {
                float pu = pts[i][0], pv = pts[i][1];
                q[i * 3] = alongX ? x + pu : x + side * d;
                q[i * 3 + 1] = alongX ? y + side * d : y + pu;
                q[i * 3 + 2] = pv;
            }
            b.quad(q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7], q[8], q[9], q[10], q[11], uu, uu, uu, uu, uu, uu, uu, uu,
                side < 0 ? shade(col, 0.75f) : col);
        }
        // the beam's edges
        for (int i = 0; i < 4; i++) {
            float[] p = pts[i], n = pts[(i + 1) % 4];
            float ax = alongX ? x + p[0] : x, ay = alongX ? y : y + p[0];
            float bx = alongX ? x + n[0] : x, by = alongX ? y : y + n[0];
            float ox = alongX ? 0 : d, oy = alongX ? d : 0;
            b.quad(ax - ox, ay - oy, p[1], bx - ox, by - oy, n[1], bx + ox, by + oy, n[1], ax + ox, ay + oy, p[1],
                uu, uu, uu, uu, uu, uu, uu, uu, shade(col, 0.85f));
        }
    }

    /** a beam along x from (x0, z0) to (x1, z1) at y = cy */
    private void plankX(float x0, float z0, float x1, float z1, float cy, float t, Color col) {
        float h = t / 2f, u = 0.5f;
        b.quad(x0, cy - h, z0 + h, x1, cy - h, z1 + h, x1, cy + h, z1 + h, x0, cy + h, z0 + h, u, u, u, u, u, u, u, u, col);
        b.quad(x0, cy - h, z0 - h, x1, cy - h, z1 - h, x1, cy - h, z1 + h, x0, cy - h, z0 + h, u, u, u, u, u, u, u, u, shade(col, 0.7f));
        b.quad(x1, cy + h, z1 - h, x0, cy + h, z0 - h, x0, cy + h, z0 + h, x1, cy + h, z1 + h, u, u, u, u, u, u, u, u, shade(col, 0.85f));
    }
}
