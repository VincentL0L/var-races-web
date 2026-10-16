package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.GdxNativesLoader;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Waypoints;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class BarriersTest {
    @BeforeClass
    public static void natives() { GdxNativesLoader.load(); }

    private Pixmap load(TrackMap map) {
        Pixmap mask = new Pixmap(new FileHandle(System.getProperty("varraces.assets", "assets") + "/" + map.roadMask));
        Barriers.build(mask, map.scale);
        return mask;
    }

    @Test
    public void allRaceGridsFitInsideTheWalls() {
        for (TrackMap map : TrackMap.all()) {
            if (map.battle) continue;
            assertTrue(map.name, map.hasBarriers());
            Pixmap mask = load(map);
            for (int slot = 0; slot < 12; slot++) {
                Vector2 p = Waypoints.getGridSlot(slot);
                assertTrue(map.name + " grid slot " + slot, Barriers.drivable(p.x + 5, p.y + 10, 10));
            }
            // The finish stays open; a sprint doesn't get a wall across its finish line.
            Vector2 finish = map.finishPoint();
            assertTrue(map.name + " finish", Barriers.drivable(finish.x, finish.y, 10));
            mask.dispose();
        }
    }

    @Test
    public void visibleWallFacesSeparateDrivableAndBlockedGround() {
        TrackMap map = TrackMap.all().get(0);
        Pixmap mask = load(map);
        assertFalse(Barriers.walls().isEmpty());
        for (Barriers.Wall wall : Barriers.walls()) {
            float x = (wall.ax + wall.bx) / 2, y = (wall.ay + wall.by) / 2;
            assertTrue(Barriers.drivable(x + wall.nx, y + wall.ny));
            assertFalse(Barriers.drivable(x - wall.nx, y - wall.ny));
        }
        mask.dispose();
    }

    @Test
    public void collisionKeepsTheCarBodyInsideTheBoundary() {
        Pixmap mask = new Pixmap(100, 100, Pixmap.Format.RGBA8888);
        mask.setColor(Color.BLACK); mask.fill();
        mask.setColor(Color.WHITE); mask.fillRectangle(30, 20, 40, 60);
        Barriers.build(mask);
        assertTrue(Barriers.drivable(50, 50, 10));
        assertTrue(Barriers.drivable(75, 50));
        assertFalse(Barriers.drivable(75, 50, 10));
        assertFalse(Barriers.drivable(95, 50));
        assertFalse(Barriers.drivable(-0.1f, 50));
        assertFalse(Barriers.drivable(50, -0.1f));
        mask.dispose();
    }

    @Test
    public void classicTurnBoardsPointInTheDrivingDirection() {
        TrackMap map = TrackMap.all().get(0);
        Pixmap mask = load(map);
        List<TrackGuidance.Sign> signs = TrackGuidance.signs(map);
        assertTrue("A board for every sharp Classic corner", signs.size() >= 15);
        TrackGuidance.Sign first = signs.get(0);
        assertTrue("First corner turns right", first.turnRight);
        assertTrue("Board is ahead of the corner", first.y > map.waypoints.get(0).y);
        assertTrue("Board's right points to driver's right", first.rightX > 0.9f);
        boolean left = false;
        for (TrackGuidance.Sign sign : signs) left |= !sign.turnRight;
        assertTrue("Left turns also have boards", left);
        mask.dispose();
    }
}
