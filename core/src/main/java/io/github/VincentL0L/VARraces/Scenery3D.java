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
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * The trees and buildings of a map, stood up in 3D for the first-person view.
 * heights.png (made by tools/MakeHeights.java from the map picture) says how tall things
 * are on a grid of CELL x CELL patches. Each raised patch becomes a block: its top is that
 * bit of the map picture (so roofs and tree tops look as they do from above), and its
 * sides are the picture's edge pulled down to the ground and shaded, like walls.
 */
public class Scenery3D {
    /** map pixels per height cell (must match MakeHeights.CELL) */
    private static final int CELL = 12;
    /** quads per mesh, so the vertex count fits a short index */
    private static final int QUADS_PER_MESH = 16000;

    private final List<Mesh> meshes = new ArrayList<>();
    private final ShaderProgram shader = SpriteBatch.createDefaultShader();
    private final Texture texture;

    /**
     * @param map the map   @param texture its picture (already loaded for the ground)
     */
    public Scenery3D(TrackMap map, Texture texture) {
        this.texture = texture;
        String file = map.background.replace("background.png", "heights.png");
        if (!Gdx.files.internal(file).exists()) {
            return;
        }
        Pixmap hp;
        try {
            hp = new Pixmap(Gdx.files.internal(file));
        } catch (RuntimeException e) {
            return;
        }
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
        float worldW = texture.getWidth() * map.scale, worldH = texture.getHeight() * map.scale;

        FloatArray verts = new FloatArray();
        int quads = 0;
        for (int gx = 0; gx < gw; gx++) {
            for (int gy = 0; gy < gh; gy++) {
                int h = height[gx][gy];
                if (h == 0) {
                    continue;
                }
                boolean tree = kind[gx][gy] == 1;
                // the picture's rows run top down; the map's y runs up
                float x0 = gx * CELL, x1 = Math.min(worldW, x0 + CELL);
                float y1 = worldH - gy * CELL, y0 = Math.max(0f, y1 - CELL);
                float u0 = x0 / worldW, u1 = x1 / worldW, v0 = 1f - y0 / worldH, v1 = 1f - y1 / worldH;
                // roof (tree tops a touch darker toward the edges)
                quad(verts, x0, y0, h, x1, y0, h, x1, y1, h, x0, y1, h, u0, v0, u1, v0, u1, v1, u0, v1, 1f);
                quads++;
                // walls on any side that drops lower; south and west in shade, north and east in sun
                float wall = tree ? 0.62f : 0.78f;
                quads += side(verts, height, gx + 1, gy, gw, gh, h, x1, y0, x1, y1, u1, v0, u1, v1, wall * 0.9f);
                quads += side(verts, height, gx - 1, gy, gw, gh, h, x0, y1, x0, y0, u0, v1, u0, v0, wall * 0.7f);
                quads += side(verts, height, gx, gy - 1, gw, gh, h, x1, y1, x0, y1, u1, v1, u0, v1, wall);
                quads += side(verts, height, gx, gy + 1, gw, gh, h, x0, y0, x1, y0, u0, v0, u1, v0, wall * 0.6f);
                if (quads >= QUADS_PER_MESH) {
                    flush(verts, quads);
                    verts.clear();
                    quads = 0;
                }
            }
        }
        if (quads > 0) {
            flush(verts, quads);
        }
    }

    /** one wall, from the neighbour's height (or the ground) up to ours */
    private static int side(FloatArray v, int[][] height, int nx, int ny, int gw, int gh, int h,
            float ax, float ay, float bx, float by, float ua, float va, float ub, float vb, float shade) {
        int low = nx >= 0 && ny >= 0 && nx < gw && ny < gh ? height[nx][ny] : 0;
        if (low >= h) {
            return 0;
        }
        // the roof's edge pixels, run down the wall: gives facades the colour of their roofs
        quad(v, ax, ay, low, bx, by, low, bx, by, h, ax, ay, h, ua, va, ub, vb, ub, vb, ua, va, shade);
        return 1;
    }

    private static void quad(FloatArray v, float x1, float y1, float z1, float x2, float y2, float z2,
            float x3, float y3, float z3, float x4, float y4, float z4,
            float u1, float v1, float u2, float v2, float u3, float v3, float u4, float v4, float shade) {
        float c = new Color(shade, shade, shade, 1f).toFloatBits();
        v.addAll(x1, y1, z1, c, u1, v1);
        v.addAll(x2, y2, z2, c, u2, v2);
        v.addAll(x3, y3, z3, c, u3, v3);
        v.addAll(x4, y4, z4, c, u4, v4);
    }

    private void flush(FloatArray verts, int quads) {
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
        meshes.add(mesh);
    }

    /** @return true if this map has anything standing up */
    public boolean isEmpty() {
        return meshes.isEmpty();
    }

    /**
     * draws the blocks, writing depth so that cars drawn afterwards hide behind them
     */
    public void render(Camera cam) {
        if (meshes.isEmpty()) {
            return;
        }
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        texture.bind(0);
        shader.bind();
        shader.setUniformMatrix("u_projTrans", cam.combined);
        shader.setUniformi("u_texture", 0);
        for (Mesh m : meshes) {
            m.render(shader, GL20.GL_TRIANGLES);
        }
    }

    public void dispose() {
        for (Mesh m : meshes) {
            m.dispose();
        }
        shader.dispose();
    }
}
