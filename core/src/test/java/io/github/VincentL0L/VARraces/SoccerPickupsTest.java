package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import io.github.VincentL0L.VARraces.soccer.SoccerMatch;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoccerPickupsTest {
    private SoccerMatch started() {
        SoccerMatch m = new SoccerMatch(1); m.add(0); m.add(1);
        for (int i = 0; i < 65; i++) m.step(0.05f);
        return m;
    }

    private SoccerMatch.Pickup collect(SoccerMatch m, int type) {
        for (SoccerMatch.Pickup p : m.pickups) if (p.type == type) {
            m.cars[0].x = p.x; m.cars[0].y = p.y; m.cars[0].vx = m.cars[0].vy = 0;
            m.step(0.05f);
            assertTrue("Pickup enters cooldown", p.cooldown > 0);
            return p;
        }
        throw new AssertionError("Missing pickup type " + type);
    }

    @Test public void boostPadsRefillOnceThenRespawn() {
        SoccerMatch m = started(); m.cars[0].boost = 0;
        SoccerMatch.Pickup p = collect(m, SoccerMatch.BOOST_PAD);
        assertEquals(25, m.cars[0].boost, 1);
        int serial = m.cars[0].pickupSerial;
        for (int i = 0; i < 20; i++) m.step(0.05f);
        assertEquals("Unavailable pad cannot be collected twice", serial, m.cars[0].pickupSerial);
        m.cars[0].x = 250;
        for (int i = 0; i < 65; i++) m.step(0.05f);
        assertEquals(0, p.cooldown, 0);
        m.cars[0].boost = 0; m.cars[0].x = p.x;
        m.step(0.05f); assertEquals(serial + 1, m.cars[0].pickupSerial);
    }

    @Test public void fullBoostCannotBeWastedAtFullFuel() {
        SoccerMatch m = started();
        SoccerMatch.Pickup p = null;
        for (SoccerMatch.Pickup candidate : m.pickups) if (candidate.type == SoccerMatch.FULL_BOOST) { p = candidate; break; }
        assertNotNull(p); m.cars[0].x = p.x; m.cars[0].y = p.y;
        m.step(0.05f); assertEquals(0, p.cooldown, 0);
        m.cars[0].boost = 10; m.step(0.05f);
        assertEquals(100, m.cars[0].boost, 0); assertTrue(p.cooldown > 9);
    }

    @Test public void simultaneousPickupHasOnlyOneWinner() {
        SoccerMatch m = started(); SoccerMatch.Pickup p = m.pickups[0];
        for (SoccerMatch.Car c : m.cars) { c.x = p.x; c.y = p.y; c.boost = 0; }
        m.step(0.05f);
        assertEquals(1, m.cars[0].pickupSerial + m.cars[1].pickupSerial);
    }

    @Test public void carsMustBeNearTheGroundToCollect() {
        SoccerMatch m = started(); SoccerMatch.Pickup p = m.pickups[0];
        m.cars[0].x = p.x; m.cars[0].y = p.y; m.cars[0].z = 30; m.cars[0].boost = 0;
        m.step(0.05f);
        assertEquals(0, p.cooldown, 0); assertEquals(0, m.cars[0].pickupSerial);
    }

    @Test public void nitroRaisesSpeedWithoutDrainingBoostAndExpires() {
        SoccerMatch m = started(); collect(m, SoccerMatch.NITRO);
        m.cars[0].boost = 50; m.cars[0].heading = 0;
        for (int i = 0; i < 20; i++) { m.input(0, 1, 0, true, false); m.step(0.05f); }
        assertTrue(m.cars[0].vx > 560); assertTrue(m.cars[0].boost >= 50);
        for (int i = 0; i < 110; i++) m.step(0.05f);
        assertEquals(0, m.cars[0].nitroTime, 0);
    }

    @Test public void powerShotStrengthensOneBallHitThenIsConsumed() {
        SoccerMatch powered = started(); collect(powered, SoccerMatch.POWER_SHOT);
        SoccerMatch normal = started();
        for (SoccerMatch m : new SoccerMatch[] {powered, normal}) {
            m.cars[0].x = 800; m.cars[0].y = 450; m.cars[0].vx = 200;
            m.ball.x = 830; m.ball.y = 450; m.input(0, 1, 0, false, false); m.step(0.05f);
        }
        assertTrue(powered.ball.vx > normal.ball.vx + 200);
        assertEquals(0, powered.cars[0].shotTime, 0);
    }

    @Test public void magnetAppliesForceOnlyWithinRange() {
        SoccerMatch m = started(); collect(m, SoccerMatch.MAGNET);
        m.cars[0].x = 500; m.cars[0].y = 100; m.cars[0].heading = 0;
        m.ball.x = 650; m.ball.y = 100; m.step(0.05f);
        assertTrue("Ball accelerates toward the bumper", m.ball.vx < -5);
        m.ball.x = 1000; m.ball.y = 100; m.ball.vx = m.ball.vy = 0; m.step(0.05f);
        assertEquals("Out-of-range ball is unaffected", 0, m.ball.vx, 0);
    }

    @Test public void pickupsAndEffectsResetOnRematchAndSerializeForEveryone() {
        SoccerMatch m = started(); collect(m, SoccerMatch.NITRO);
        JsonValue snapshot = new JsonReader().parse(m.snapshot());
        assertEquals(m.pickups.length, snapshot.get("pickups").size);
        assertTrue(snapshot.get("effects").get(0).getFloat(1) > 0);
        m.restart();
        for (SoccerMatch.Pickup p : m.pickups) assertEquals(0, p.cooldown, 0);
        assertEquals(0, m.cars[0].nitroTime, 0);
        assertEquals(0, m.cars[0].shotTime, 0);
        assertEquals(0, m.cars[0].magnetTime, 0);
    }

    @Test public void waitingRoomsCannotCollectPickups() {
        SoccerMatch m = new SoccerMatch(1); m.add(0);
        m.cars[0].x = m.pickups[0].x; m.cars[0].y = m.pickups[0].y; m.cars[0].boost = 0;
        m.step(0.1f); assertEquals(0, m.cars[0].pickupSerial);
        assertEquals(0, m.pickups[0].cooldown, 0);
    }
}
