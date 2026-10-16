package io.github.VincentL0L.VARraces;

import io.github.VincentL0L.VARraces.soccer.SoccerMatch;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoccerHandlingTest {
    private SoccerMatch started() {
        SoccerMatch m = new SoccerMatch(1); m.add(0); m.add(1);
        m.phase = SoccerMatch.PLAYING;
        m.cars[0].x = 400; m.cars[0].y = 250;
        return m;
    }

    private void drive(SoccerMatch m, float throttle, float steer, boolean boost, boolean jump, int steps) {
        for (int i = 0; i < steps; i++) {
            m.input(0, throttle, steer, boost, jump); m.step(0.05f);
        }
    }

    @Test public void wheelsEaseIntoTurnsAndMomentumFollowsGradually() {
        SoccerMatch m = started(); m.cars[0].vx = 250;
        m.input(0, 1, 1, false, false); m.step(1f / 120);
        assertTrue(m.cars[0].heading > 0 && m.cars[0].heading < 0.2f);
        assertEquals("Steering cannot instantly rotate velocity", 0, m.cars[0].vy, 0.001f);
        drive(m, 1, 1, false, false, 8);
        assertTrue("Tires arc the path into the turn", m.cars[0].vy > 25);
    }

    @Test public void fastTurnsAreWiderAndReverseSteeringFlips() {
        SoccerMatch slow = started(), fast = started(), reverse = started(), stopped = started();
        slow.cars[0].vx = 120; fast.cars[0].vx = 550; reverse.cars[0].vx = -120;
        drive(slow, 0, 1, false, false, 2); drive(fast, 1, 1, true, false, 2);
        drive(reverse, -1, 1, false, false, 2); drive(stopped, 0, 1, false, false, 2);
        assertTrue(slow.cars[0].heading > fast.cars[0].heading);
        assertTrue(reverse.cars[0].heading > 350);
        assertEquals(0, stopped.cars[0].heading, 0);
    }

    @Test public void aerialBoostClimbsAboveOldCeilingAndReleaseDescends() {
        SoccerMatch boosted = started(), normal = started();
        drive(boosted, 0, 0, true, true, 36);
        drive(normal, 0, 0, false, true, 36);
        assertTrue("Boost alone works after a jump", boosted.cars[0].z > 300);
        assertEquals("Unboosted jump lands", 0, normal.cars[0].z, 0);
        assertTrue(boosted.cars[0].boost < 70);
        drive(boosted, 0, 0, false, false, 85);
        assertEquals(0, boosted.cars[0].z, 0);
    }

    @Test public void aerialsCanStrikeHighBallsAndCannotEscapeCeiling() {
        SoccerMatch m = started();
        drive(m, 0, 0, true, true, 22);
        SoccerMatch.Car c = m.cars[0];
        assertTrue(c.z > SoccerMatch.GOAL_HEIGHT);
        // A stationary ball directly above the climbing bumper gets a real impulse.
        m.ball.x = c.x; m.ball.y = c.y; m.ball.z = c.z + 43;
        m.ball.vx = m.ball.vy = m.ball.vz = 0;
        m.input(0, 0, 0, true, true); m.step(0.05f);
        assertTrue("Aerial contact transfers upward momentum", m.ball.vz > 50);
        boolean exhausted = false;
        for (int i = 0; i < 150; i++) {
            m.input(0, 0, 0, true, true); m.step(0.05f);
            exhausted |= c.boost < 0.01f;
            assertTrue(c.z <= SoccerMatch.CEILING - 20);
            assertTrue(m.ball.z <= SoccerMatch.CEILING - SoccerMatch.BALL_RADIUS + 1);
        }
        assertTrue("Fuel runs out during the aerial", exhausted);
        assertEquals("Car lands after fuel runs out", 0, c.z, 0);
    }

    @Test public void airborneTurningPreservesMomentumAndGoalCrossbarBlocksCars() {
        SoccerMatch m = started(); SoccerMatch.Car c = m.cars[0];
        c.z = 250; c.vx = 200;
        drive(m, 0, 1, false, false, 8);
        assertEquals(200, c.vx, 0.01f); assertEquals(0, c.vy, 0.01f);
        c.x = SoccerMatch.WIDTH - 16; c.y = SoccerMatch.HEIGHT / 2;
        c.z = SoccerMatch.GOAL_HEIGHT + 20; c.vx = 400;
        m.input(0, 0, 0, false, false); m.step(0.05f);
        assertTrue(c.x <= SoccerMatch.WIDTH - SoccerMatch.CAR_RADIUS);
    }

    @Test public void pickupsStaySymmetricInsideExpandedPitch() {
        SoccerMatch m = started();
        assertTrue(SoccerMatch.WIDTH > 1400 && SoccerMatch.HEIGHT > 900);
        for (SoccerMatch.Pickup p : m.pickups) {
            assertTrue(p.x > 0 && p.x < SoccerMatch.WIDTH && p.y > 0 && p.y < SoccerMatch.HEIGHT);
        }
        assertEquals(SoccerMatch.WIDTH, m.pickups[0].x + m.pickups[1].x, 0.01f);
    }
}
