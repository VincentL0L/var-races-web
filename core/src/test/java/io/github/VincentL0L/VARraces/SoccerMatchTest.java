package io.github.VincentL0L.VARraces;

import io.github.VincentL0L.VARraces.soccer.SoccerMatch;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoccerMatchTest {
    private SoccerMatch started(int size) {
        SoccerMatch m = new SoccerMatch(size);
        for (int i = 0; i < size * 2; i++) m.add(i);
        for (int i = 0; i < 70; i++) m.step(0.05f);
        assertEquals(SoccerMatch.PLAYING, m.phase);
        return m;
    }

    @Test public void oneVersusOneWaitsForOpponentThenStartsCountdown() {
        SoccerMatch m = new SoccerMatch(1); m.add(0);
        m.step(0.05f); assertEquals(SoccerMatch.WAITING, m.phase);
        m.add(1); assertEquals(SoccerMatch.KICKOFF, m.phase);
        assertEquals(0, m.cars[0].team); assertEquals(1, m.cars[1].team);
    }

    @Test public void carCanStrikeBallAndScoreForBlue() {
        SoccerMatch m = started(1);
        m.cars[1].y = 700;
        boolean hit = false;
        for (int i = 0; i < 240 && m.blue == 0; i++) {
            m.input(0, 1, 0, true, false); m.step(0.05f);
            hit |= m.ball.vx > 50;
        }
        assertTrue("Car transfers momentum to ball", hit);
        assertEquals("Ball crosses the opponent's goal line", 1, m.blue);
        assertEquals(0, m.orange);
    }

    @Test public void wallsBounceBallButGoalsOnlyCountInsideTheMouth() {
        SoccerMatch m = started(1);
        m.ball.x = SoccerMatch.WIDTH - 27; m.ball.y = 100; m.ball.vx = 200;
        m.step(0.05f); assertTrue(m.ball.vx < 0); assertEquals(0, m.blue);
        m.ball.x = SoccerMatch.WIDTH + 27; m.ball.y = SoccerMatch.HEIGHT / 2;
        m.ball.z = SoccerMatch.BALL_RADIUS; m.ball.vx = 0;
        m.step(0.05f); assertEquals(1, m.blue); assertEquals(SoccerMatch.GOAL, m.phase);
        for (int i = 0; i < 30; i++) m.step(0.05f);
        assertEquals("A goal only counts once", 1, m.blue);
    }

    @Test public void highShotsHitTheBackWallAboveTheCrossbar() {
        SoccerMatch m = started(1);
        m.ball.x = SoccerMatch.WIDTH - 27; m.ball.y = SoccerMatch.HEIGHT / 2;
        m.ball.z = 150; m.ball.vx = 200; m.step(0.05f);
        assertTrue(m.ball.vx < 0); assertEquals(0, m.blue);
    }

    @Test public void goalResetsBothCarsAndBallForAnotherKickoff() {
        SoccerMatch m = started(1);
        m.ball.x = -27; m.step(0.05f);
        for (int i = 0; i < 52; i++) m.step(0.05f);
        assertEquals(1, m.orange); assertEquals(SoccerMatch.KICKOFF, m.phase);
        assertEquals(700, m.ball.x, 0); assertEquals(260, m.cars[0].x, 0);
    }

    @Test public void jumpBoostAndInputTimeoutWorkWithoutLeavingArena() {
        SoccerMatch m = started(1);
        for (int i = 0; i < 10; i++) { m.input(0, 1, 0, true, i == 0); m.step(0.05f); }
        assertTrue(m.cars[0].z > 0); assertTrue(m.cars[0].boost < 100);
        for (int i = 0; i < 100; i++) m.step(0.05f);
        assertEquals(0, m.cars[0].throttle, 0);
        assertEquals(0, m.cars[0].z, 0);
        assertTrue(m.cars[0].x >= -SoccerMatch.GOAL_DEPTH);
    }

    @Test public void timerFinishesWinnerAndTiesGoToSuddenDeath() {
        SoccerMatch m = started(1); m.blue = 2; m.remaining = 0.02f; m.step(0.05f);
        assertEquals(SoccerMatch.FINISHED, m.phase);
        m.restart(); for (int i = 0; i < 65; i++) m.step(0.05f);
        m.remaining = 0.02f; m.step(0.05f); assertTrue(m.overtime);
        m.ball.x = SoccerMatch.WIDTH + 27; m.step(0.05f);
        assertEquals(SoccerMatch.FINISHED, m.phase);
    }

    @Test public void invalidInputsCannotPoisonAuthoritativeState() {
        SoccerMatch m = started(1);
        m.input(0, Float.NaN, Float.POSITIVE_INFINITY, true, false); m.step(0.05f);
        assertTrue(Float.isFinite(m.cars[0].x)); assertTrue(Float.isFinite(m.cars[0].heading));
        assertFalse(m.snapshot().contains("NaN"));
    }

    @Test public void twoVersusTwoBalancesTeamsAndWaitsForAllFour() {
        SoccerMatch m = new SoccerMatch(2); m.add(0); m.add(1); m.add(2);
        assertEquals(SoccerMatch.WAITING, m.phase);
        m.add(3); assertEquals(SoccerMatch.KICKOFF, m.phase);
        assertEquals(m.cars[0].team, m.cars[2].team);
        assertEquals(m.cars[1].team, m.cars[3].team);
        assertNotEquals(m.cars[0].y, m.cars[2].y, 1);
        m.remove(2); assertEquals(SoccerMatch.WAITING, m.phase);
        m.add(2); assertEquals(SoccerMatch.KICKOFF, m.phase);
    }
}
