package io.github.VincentL0L.VARraces;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * The trees and buildings of a map, stood up in 3D for the first-person view.
 * heights.png (made by tools/MakeHeights.java from the map picture) says how tall things
 * are on a grid of CELL x CELL patches.
 *  - buildings: blocks whose roofs are that bit of the map picture and whose walls are
 *    rows of windows, painted in the roof's colour
 *  - trees: two crossed upright pictures of a tree (the old arcade trick), tinted the
 *    colour of the leaves seen from above, so cherry blossom stays pink
 * Everything fades into the sky with distance (FOG).
 */
public class Scenery3D {
    /** map pixels per height cell (must match MakeHeights.CELL) */
    private static final int CELL = 12;
    /** quads per mesh, so the vertex count fits a short index */
    private static final int QUADS_PER_MESH = 16000;
    /** one window bay is this wide, one storey this tall (map pixels) */
    private static final float BAY = 16f, STOREY = 12f;
    public static final float FOG_START = 700f, FOG_END = 2400f;

    /** fog, plus see-through pixels cut out (for the trees) */
    public static final String VERTEX =
        "attribute vec4 a_position;\n"
        + "attribute vec4 a_color;\n"
        + "attribute vec2 a_texCoord0;\n"
        + "uniform mat4 u_projTrans;\n"
        + "uniform vec3 u_eye;\n"
        + "uniform vec2 u_fogRange;\n"
        + "varying vec4 v_color;\n"
        + "varying vec2 v_texCoords;\n"
        + "varying float v_fog;\n"
        + Terrain.GLSL
        + "void main() {\n"
        + "  v_color = a_color;\n"
        + "  v_color.a = v_color.a * (255.0/254.0);\n"
        + "  v_texCoords = a_texCoord0;\n"
        + "  vec4 pos = a_position;\n"
        + "  pos.z += hill(pos.xy);\n"
        + "  float d = distance(pos.xy, u_eye.xy);\n"
        + "  v_fog = clamp((d - u_fogRange.x) / (u_fogRange.y - u_fogRange.x), 0.0, 1.0);\n"
        + "  gl_Position = u_projTrans * pos;\n"
        + "}\n";
    public static final String FRAGMENT =
        "#ifdef GL_ES\n"
        + "precision mediump float;\n"
        + "#endif\n"
        + "varying vec4 v_color;\n"
        + "varying vec2 v_texCoords;\n"
        + "varying float v_fog;\n"
        + "uniform sampler2D u_texture;\n"
        + "uniform vec3 u_fogColor;\n"
        + "uniform float u_cutout;\n"
        + "void main() {\n"
        + "  vec4 c = v_color * texture2D(u_texture, v_texCoords);\n"
        + "  if (c.a < u_cutout) discard;\n"
        + "  gl_FragColor = vec4(mix(c.rgb, u_fogColor, v_fog * v_fog), c.a);\n"
        + "}\n";

    private final List<Mesh> roofs = new ArrayList<>(), walls = new ArrayList<>(), trees = new ArrayList<>();
    private final ShaderProgram shader;
    private final Texture picture;
    private Texture facade, tree;

    /**
     * @param map the map   @param picture its picture (already loaded for the ground)
     */
    private final Landmarks3D landmarks;

    public Scenery3D(TrackMap map, Texture picture, ShaderProgram shader) {
        this.picture = picture;
        this.shader = shader;
        landmarks = new Landmarks3D(map);
        java.util.List<float[]> clear = landmarks.clearAreas();
        String file = map.background.replace("background.png", "heights.png");
        Pixmap hp, colours;
        try {
            if (!Gdx.files.internal(file).exists()) {
                return;
            }
            hp = new Pixmap(Gdx.files.internal(file));
            colours = new Pixmap(Gdx.files.internal(map.background));
        } catch (RuntimeException e) {
            return;
        }
        facade = makeFacade();
        tree = makeTree();
        int gw = hp.getWidth(), gh = hp.getHeight();
        int[][] height = new int[gw][gh];
        int[][] kind = new int[gw][gh];
        for (int x = 0; x < gw; x++) {
            for (int y = 0; y < gh; y++) {
                int c = hp.getPixel(x, y);       // RGBA8888
                height[x][y] = (c >>> 24) & 0xff;
                kind[x][y] = (c >>> 16) & 0xff;
            }
        }
        hp.dispose();
        int scale = map.scale;
        float worldW = picture.getWidth() * scale, worldH = picture.getHeight() * scale;

        Builder roofB = new Builder(roofs), treeB = new Builder(trees);
        Color avg = new Color();
        // first, each building patch's colour from above, and the patches a landmark stands on
        Color[][] roofColour = new Color[gw][gh];
        boolean[][] skip = new boolean[gw][gh];
        for (int gx = 0; gx < gw; gx++) {
            for (int gy = 0; gy < gh; gy++) {
                if (height[gx][gy] == 0 || kind[gx][gy] != 2) {
                    continue;
                }
                float cx = gx * CELL + CELL / 2f, cy = worldH - gy * CELL - CELL / 2f;
                skip[gx][gy] = inside(clear, cx, cy);
                roofColour[gx][gy] = new Color();
                average(colours, gx * CELL / scale, gy * CELL / scale, CELL / scale, roofColour[gx][gy], false);
            }
        }
        // the buildings themselves, each in its city's architecture
        architecture = new CityArchitecture(map, height, kind, roofColour, skip, worldH);
        for (int gx = 0; gx < gw; gx++) {
            for (int gy = 0; gy < gh; gy++) {
                int h = height[gx][gy];
                if (h == 0) {
                    continue;
                }
                // the picture's rows run top down; the map's y runs up
                float x0 = gx * CELL, x1 = Math.min(worldW, x0 + CELL);
                float y1 = worldH - gy * CELL, y0 = Math.max(0f, y1 - CELL);
                if (inside(clear, (x0 + x1) / 2f, (y0 + y1) / 2f)) {
                    continue;     // a landmark stands here, built properly (Landmarks3D)
                }
                average(colours, gx * CELL / scale, gy * CELL / scale, CELL / scale, avg, kind[gx][gy] == 1);
                int seed = gx * 73856093 ^ gy * 19349663;
                if (kind[gx][gy] == 1) {
                    // a tree in most tree cells, nudged about so they don't line up
                    if (Math.floorMod(seed, 10) < 3) {
                        continue;
                    }
                    float cx = (x0 + x1) / 2f + (Math.floorMod(seed >> 4, 9) - 4), cy = (y0 + y1) / 2f + (Math.floorMod(seed >> 8, 9) - 4);
                    float size = 15f + Math.floorMod(seed >> 12, 9);
                    float tall = h * 1.35f;
                    Color leaf = new Color(avg).mul(1.45f);
                    leaf.a = 1f;
                    float turn = Math.floorMod(seed >> 16, 90);
                    for (int k = 0; k < 2; k++) {
                        float a = turn + k * 90f;
                        float dx = MathUtils.cosDeg(a) * size / 2f, dy = MathUtils.sinDeg(a) * size / 2f;
                        treeB.quad(cx - dx, cy - dy, 0f, cx + dx, cy + dy, 0f, cx + dx, cy + dy, tall, cx - dx, cy - dy, tall,
                            0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f, leaf);
                    }
                    continue;
                }
                if (!architecture.builds(gx, gy)) {
                    continue;
                }
                // the roof: the map's own picture of it, on top of the mansard where there is one
                float lift = architecture.roofLift(gx, gy), in = architecture.roofInset(gx, gy);
                float rx0 = x0 + (height(height, gx - 1, gy) < h ? in : 0), rx1 = x1 - (height(height, gx + 1, gy) < h ? in : 0);
                float ry0 = y0 + (height(height, gx, gy + 1) < h ? in : 0), ry1 = y1 - (height(height, gx, gy - 1) < h ? in : 0);
                float u0 = rx0 / worldW, u1 = rx1 / worldW, v0 = 1f - ry0 / worldH, v1 = 1f - ry1 / worldH;
                float rz = h + lift;
                roofB.quad(rx0, ry0, rz, rx1, ry0, rz, rx1, ry1, rz, rx0, ry1, rz, u0, v0, u1, v0, u1, v1, u0, v1, Color.WHITE);
            }
        }
        colours.dispose();
        roofB.finish();
        treeB.finish();
    }

    private CityArchitecture architecture;

    private static int height(int[][] height, int x, int y) {
        return x >= 0 && y >= 0 && x < height.length && y < height[0].length ? height[x][y] : 0;
    }

    private static boolean inside(java.util.List<float[]> areas, float x, float y) {
        for (float[] a : areas) {
            if (x >= a[0] && x <= a[2] && y >= a[1] && y <= a[3]) {
                return true;
            }
        }
        return false;
    }

    /** the colour of a patch of the map picture (just the leafy pixels for a tree) */
    private static void average(Pixmap p, int px, int py, int size, Color out, boolean leaves) {
        float r = 0, g = 0, b = 0;
        int n = 0;
        Color c = new Color();
        size = Math.max(1, size);
        for (int x = px; x < Math.min(p.getWidth(), px + size); x++) {
            for (int y = py; y < Math.min(p.getHeight(), py + size); y++) {
                Color.rgba8888ToColor(c, p.getPixel(x, y));
                if (leaves && c.r + c.g + c.b > 2.2f) {
                    continue;
                }
                r += c.r;
                g += c.g;
                b += c.b;
                n++;
            }
        }
        if (n == 0) {
            out.set(0.3f, 0.5f, 0.25f, 1f);
        } else {
            out.set(r / n, g / n, b / n, 1f);
        }
    }

    /** texture spots in the facade that are one flat colour: the dark outline, and near-white */
    static final float OUTLINE_U = 1.5f / 32f, OUTLINE_V = 2.5f / 32f, WHITE_U = 0.5f / 32f, WHITE_V = 0.5f / 32f;

    /**
     * one wall, from the neighbour's height (or the ground) up to ours, with a dark pixel
     * outline along its top (the cornice) and a dark plinth at street level, like the
     * outlines round the cars' sprites
     * @param ox, oy which way the wall faces
     */
    private static void wall(Builder b, int[][] height, int nx, int ny, int gw, int gh, int h,
            float ax, float ay, float bx, float by, float ox, float oy, Color paint, float shade) {
        int low = nx >= 0 && ny >= 0 && nx < gw && ny < gh ? height[nx][ny] : 0;
        if (low >= h) {
            return;
        }
        // windows line up across the whole map: u from where along the street we are, v from the height
        float ua = (ax == bx ? ay : ax) / BAY, ub = (ax == bx ? by : bx) / BAY;
        Color c = new Color(paint.r * shade, paint.g * shade, paint.b * shade, 1f);
        b.quad(ax, ay, low, bx, by, low, bx, by, h, ax, ay, h,
            ua / 4f, -low / STOREY / 4f, ub / 4f, -low / STOREY / 4f, ub / 4f, -h / STOREY / 4f, ua / 4f, -h / STOREY / 4f, c);
        float out = 0.3f, ou = OUTLINE_U, ov = OUTLINE_V;
        Color line = new Color(0.13f, 0.1f, 0.09f, 1f);
        b.quad(ax + ox * out, ay + oy * out, h - 1.6f, bx + ox * out, by + oy * out, h - 1.6f,
            bx + ox * out, by + oy * out, h + 0.15f, ax + ox * out, ay + oy * out, h + 0.15f, ou, ov, ou, ov, ou, ov, ou, ov, line);
        if (low == 0) {
            b.quad(ax + ox * out, ay + oy * out, 0f, bx + ox * out, by + oy * out, 0f,
                bx + ox * out, by + oy * out, 1.8f, ax + ox * out, ay + oy * out, 1.8f, ou, ov, ou, ov, ou, ov, ou, ov,
                new Color(c).mul(0.45f, 0.45f, 0.45f, 1f));
        }
    }


    /** builds meshes of quads, starting a new one when it gets full */
    static class Builder {
        final List<Mesh> out;
        final FloatArray verts = new FloatArray();
        int quads = 0;

        Builder(List<Mesh> out) {
            this.out = out;
        }

        void quad(float x1, float y1, float z1, float x2, float y2, float z2,
                float x3, float y3, float z3, float x4, float y4, float z4,
                float u1, float v1, float u2, float v2, float u3, float v3, float u4, float v4, Color color) {
            float c = color.toFloatBits();
            verts.addAll(x1, y1, z1, c, u1, v1);
            verts.addAll(x2, y2, z2, c, u2, v2);
            verts.addAll(x3, y3, z3, c, u3, v3);
            verts.addAll(x4, y4, z4, c, u4, v4);
            if (++quads >= QUADS_PER_MESH) {
                finish();
            }
        }

        void finish() {
            if (quads == 0) {
                return;
            }
            Mesh mesh = new Mesh(true, quads * 4, quads * 6,
                new VertexAttribute(VertexAttributes.Usage.Position, 3, ShaderProgram.POSITION_ATTRIBUTE),
                new VertexAttribute(VertexAttributes.Usage.ColorPacked, 4, ShaderProgram.COLOR_ATTRIBUTE),
                new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, ShaderProgram.TEXCOORD_ATTRIBUTE + "0"));
            ShortArray idx = new ShortArray(quads * 6);
            for (int q = 0; q < quads; q++) {
                short b = (short) (q * 4);
                idx.addAll(b, (short) (b + 1), (short) (b + 2), (short) (b + 2), (short) (b + 3), b);
            }
            mesh.setVertices(verts.items, 0, verts.size);
            mesh.setIndices(idx.items, 0, idx.size);
            out.add(mesh);
            verts.clear();
            quads = 0;
        }
    }

    /**
     * the building walls, in the same chunky 16-bit style as the car sprites: 4 x 4 window
     * bays of 8 x 8 big pixels each, with a highlight along the top, a shadow underneath,
     * dark outlined frames, two-tone glass with a glint, a sill, and a few lit windows.
     * The wall pixels are near-white so each building's paint colour tints them.
     * (Pixel (0, 0) is plain near-white and (1, 2) is the outline colour: see WHITE_U, OUTLINE_U.)
     */
    static Texture makeFacade() {
        Pixmap p = new Pixmap(32, 32, Pixmap.Format.RGBA8888);
        java.util.Random rnd = new java.util.Random(7);
        Color wall = new Color(0.92f, 0.92f, 0.9f, 1f), light = new Color(0.98f, 0.98f, 0.97f, 1f),
            dark = new Color(0.66f, 0.66f, 0.68f, 1f), outline = new Color(0.16f, 0.13f, 0.12f, 1f),
            glass = new Color(0.27f, 0.38f, 0.55f, 1f), glint = new Color(0.62f, 0.78f, 0.95f, 1f),
            lit = new Color(1f, 0.85f, 0.42f, 1f), litDim = new Color(0.95f, 0.68f, 0.3f, 1f), sill = new Color(0.82f, 0.82f, 0.8f, 1f);
        for (int by = 0; by < 4; by++) {
            for (int bx = 0; bx < 4; bx++) {
                boolean on = rnd.nextInt(5) == 0;
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        Color c;
                        if (y == 0) {
                            c = light;                                  // highlight along the top of the storey
                        } else if (y == 7) {
                            c = dark;                                   // shadow under it
                        } else if (y == 6) {
                            c = x >= 1 && x <= 6 ? sill : wall;         // the sill
                        } else if (x == 0) {
                            c = light;
                        } else if (x == 7) {
                            c = dark;
                        } else if (x == 1 || x == 6 || y == 1 || y == 5) {
                            c = outline;                                // the frame
                        } else if (on) {
                            c = y == 2 ? lit : litDim;
                        } else {
                            c = (y == 2 && x < 4) ? glint : glass;      // glass with a glint in the corner
                        }
                        p.setColor(c);
                        p.drawPixel(bx * 8 + x, by * 8 + y);
                    }
                }
            }
        }
        // the two flat colours the cornices and plinths use
        p.setColor(light);
        p.drawPixel(0, 0);
        p.setColor(outline);
        p.drawPixel(1, 2);
        Texture t = new Texture(p);
        p.dispose();
        t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return t;
    }

    /**
     * a pixel-art tree, light and shade only (it's tinted by the leaves' colour): a brown
     * trunk and a round, lumpy crown lit from the top left
     */
    private static Texture makeTree() {
        int w = 32, h = 48;
        Pixmap p = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        p.setColor(0f, 0f, 0f, 0f);
        p.fill();
        // trunk (drawn dark: it's tinted green, so it needs to be near black-brown)
        for (int y = 30; y < h; y++) {
            for (int x = 14; x < 18; x++) {
                float s = x < 16 ? 0.42f : 0.3f;
                p.setColor(s * 1.1f, s * 0.75f, s * 0.5f, 1f);
                p.drawPixel(x, y);
            }
        }
        // crown: overlapping blobs
        float[][] blobs = {{16, 18, 13}, {9, 22, 8}, {23, 22, 8}, {16, 10, 9}, {11, 13, 7}, {21, 13, 7}, {16, 27, 8}};
        java.util.Random rnd = new java.util.Random(3);
        for (int y = 0; y < 36; y++) {
            for (int x = 0; x < w; x++) {
                float best = 99f;
                for (float[] b : blobs) {
                    float d = (float) Math.hypot(x + 0.5f - b[0], y + 0.5f - b[1]) / b[2];
                    best = Math.min(best, d);
                }
                if (best > 1f) {
                    continue;
                }
                // light from the top left, darker underneath, a little speckle for leaves
                float light = 0.95f - (y / 36f) * 0.45f - (x / (float) w) * 0.15f + (rnd.nextFloat() - 0.5f) * 0.18f;
                if (best > 0.85f) {
                    light *= 0.7f;
                }
                light = MathUtils.clamp(light, 0.25f, 1f);
                p.setColor(light, light, light, 1f);
                p.drawPixel(x, y);
            }
        }
        Texture t = new Texture(p);
        p.dispose();
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return t;
    }

    /** @return true if this map has anything standing up */
    public boolean isEmpty() {
        return roofs.isEmpty() && trees.isEmpty() && landmarks.isEmpty() && (architecture == null || architecture.isEmpty());
    }

    /**
     * draws everything, writing depth so that cars drawn afterwards hide behind it
     * (the shader's fog uniforms are set by FirstPersonView)
     */
    public void render(Camera cam) {
        if (isEmpty()) {
            return;
        }
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        shader.bind();
        shader.setUniformMatrix("u_projTrans", cam.combined);
        shader.setUniformi("u_texture", 0);
        shader.setUniformf("u_cutout", 0f);
        draw(roofs, picture);
        if (architecture != null) {
            architecture.render(shader);
        }
        draw(landmarks.meshes(), landmarks.texture());
        shader.setUniformf("u_cutout", 0.5f);
        draw(trees, tree);
        shader.setUniformf("u_cutout", 0f);
    }

    private void draw(List<Mesh> meshes, Texture t) {
        if (meshes.isEmpty() || t == null) {
            return;
        }
        t.bind(0);
        for (Mesh m : meshes) {
            m.render(shader, GL20.GL_TRIANGLES);
        }
    }

    public void dispose() {
        for (List<Mesh> l : List.of(roofs, walls, trees)) {
            for (Mesh m : l) {
                m.dispose();
            }
        }
        landmarks.dispose();
        if (architecture != null) {
            architecture.dispose();
        }
        if (facade != null) {
            facade.dispose();
            tree.dispose();
        }
    }
}
