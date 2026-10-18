package com.morsenav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.morsenav.ManeuverParser.Maneuver;
import com.morsenav.NavStore.Decision;

import java.util.Arrays;

import org.junit.Test;

public class NavStoreTest {

    private final NavStore store = new NavStore();
    private long clockMs = 0;

    private static Maneuver left(String road, int meters) {
        return ManeuverParser.parse(Arrays.asList(meters + " m", road, "2 min · 900 m · 14:35 ETA"),
                ManeuverParser.Direction.LEFT);
    }

    private Decision post(String road, int meters) {
        return store.onManeuver(left(road, meters), clockMs);
    }

    /** Seconds before the turn at which ADVANCE and IMMINENT fired. */
    private static final class Ride {
        double advanceSeconds = -1;
        double imminentSeconds = -1;
        int advances;
        int imminents;
    }

    /**
     * Approaches a turn at constant speed, one Maps update per second with distances shown in
     * 10 m steps as Maps does.
     */
    private Ride ride(String road, double kmh, int startMeters) {
        return ride(road, kmh, startMeters, 0);
    }

    private Ride ride(String road, double kmh, int startMeters, int endMeters) {
        double mps = kmh / 3.6;
        Ride r = new Ride();
        for (double d = startMeters; d > endMeters; d -= mps) {
            int shown = (int) (Math.round(d / 10) * 10);
            Decision decision = post(road, shown);
            if (decision == Decision.ADVANCE) {
                r.advances++;
                r.advanceSeconds = d / mps;
            } else if (decision == Decision.IMMINENT) {
                r.imminents++;
                r.imminentSeconds = d / mps;
            }
            clockMs += 1000;
        }
        return r;
    }

    private static void assertBetween(String what, double min, double max, double actual) {
        assertTrue(what + " was " + actual + ", expected " + min + ".." + max, actual >= min && actual <= max);
    }

    private void assertTimed(double kmh) {
        Ride r = ride("MG Road", kmh, 800);
        assertEquals(1, r.advances);
        assertEquals(1, r.imminents);
        assertBetween(kmh + " km/h advance seconds", 20, 30, r.advanceSeconds);
        assertBetween(kmh + " km/h imminent seconds", 8, 11, r.imminentSeconds);
    }

    @Test
    public void timedAt20Kmh() {
        assertTimed(20);
    }

    @Test
    public void timedAt30Kmh() {
        assertTimed(30);
    }

    @Test
    public void timedAt50Kmh() {
        assertTimed(50);
    }

    @Test
    public void verySlowIsClampedToMinimumDistances() {
        Ride r = ride("MG Road", 8, 300);
        assertEquals(1, r.advances);
        assertEquals(1, r.imminents);
        // 100 m / 30 m minimums at ~2.2 m/s.
        assertBetween("advance seconds", 40, 50, r.advanceSeconds);
        assertBetween("imminent seconds", 12, 15, r.imminentSeconds);
    }

    @Test
    public void farTurnDoesNotBuzzWhenFirstSeen() {
        assertEquals(Decision.IGNORE, post("Rd Number", 3000));
    }

    @Test
    public void fallbackDistancesBeforeSpeedIsKnown() {
        assertEquals(Decision.IGNORE, post("MG Road", 250));
        assertEquals(Decision.ADVANCE, post("MG Road", 200));
        assertEquals(Decision.IGNORE, post("MG Road", 100));
        assertEquals(Decision.IMMINENT, post("MG Road", 80));
        assertEquals(Decision.IGNORE, post("MG Road", 20));
    }

    @Test
    public void firstSeenInsideImminentWindowGivesOnlyImminent() {
        assertEquals(Decision.IMMINENT, post("Park Street", 60));
        assertEquals(Decision.IGNORE, post("Park Street", 10));
    }

    @Test
    public void mapsSayingNowIsImminent() {
        assertEquals(Decision.IMMINENT, store.onManeuver(
                ManeuverParser.parse(Arrays.asList("Turn left now")), clockMs));
    }

    @Test
    public void stoppedAtALightThenMovingOn() {
        // Rolling up at ~18 km/h to a light 60 m before the turn: advance fires, imminent not yet.
        Ride approach = ride("Ring Road", 18, 300, 60);
        assertEquals(1, approach.advances);
        assertEquals(0, approach.imminents);

        for (int i = 0; i < 30; i++) {
            assertEquals("waiting " + i, Decision.IGNORE, post("Ring Road", 60));
            clockMs += 1000;
        }
        // Moving off slowly: imminent fires by 30 m at the latest, and only once.
        int imminentAt = -1;
        for (int d = 60; d >= 0; d -= 5) {
            if (post("Ring Road", d) == Decision.IMMINENT) {
                assertEquals(-1, imminentAt);
                imminentAt = d;
            }
            clockMs += 1000;
        }
        assertBetween("imminent meters", 30, 55, imminentAt);
    }

    @Test
    public void speedCarriesIntoTheNextTurn() {
        ride("MG Road", 30, 600);
        // Next turn appears 240 m away; at 30 km/h that's ~29 s, just outside the advance window.
        assertEquals(Decision.IGNORE, post("Park Street", 240));
        clockMs += 1000;
        assertEquals(Decision.IGNORE, post("Park Street", 230));
        clockMs += 3000;
        assertEquals(Decision.ADVANCE, post("Park Street", 200));
    }

    @Test
    public void rerouteJumpDoesNotRepeatAdvance() {
        Ride before = ride("Service Rd", 50, 700, 300);
        assertEquals(1, before.advances);
        // A reroute bumps the distance back up; the advance already given stands.
        assertEquals(Decision.IGNORE, post("Service Rd", 420));
        clockMs += 1000;
        Ride after = ride("Service Rd", 50, 410);
        assertEquals(0, after.advances);
        assertEquals(1, after.imminents);
    }

    @Test
    public void identicalWordingTwiceInARow() {
        ride("Turn left", 30, 400);
        // Passed it; the next maneuver is also "Turn left", 150 m away: inside the advance window.
        assertEquals(Decision.ADVANCE, post("Turn left", 150));
    }

    @Test
    public void resetForgetsState() {
        assertEquals(Decision.ADVANCE, post("MG Road", 150));
        store.reset();
        assertEquals(Decision.ADVANCE, post("MG Road", 150));
    }
    /** Advances the clock in 500 ms steps, calling onClock; returns the first non-IGNORE decision. */
    private Decision clockUntil(long untilMs) {
        while (clockMs < untilMs) {
            clockMs += 500;
            Decision d = store.onClock(clockMs);
            if (d != Decision.IGNORE) return d;
        }
        return Decision.IGNORE;
    }

    @Test
    public void sparseUpdatesStillGiveImminentOnTime() {
        // Captured 2026-10-18 with the screen off: 1 Hz updates, then ~10 s gaps near the turn.
        double mps = 46 / 3.6;
        double d = 600;
        while (d > 245) {
            post("Station Main Rd", (int) (Math.round(d / 10) * 10));
            clockMs += 1000;
            d -= mps;
        }
        clockMs += 9000;               // gap
        d -= mps * 9;
        post("Station Main Rd", (int) (Math.round(d / 10) * 10));
        double lastPost = d;
        long lastPostMs = clockMs;
        assertEquals(Decision.IMMINENT, clockUntil(clockMs + 10000));
        double secondsBefore = (lastPost - mps * (clockMs - lastPostMs) / 1000.0) / mps;
        assertBetween("imminent seconds before turn", 8, 11, secondsBefore);
        // Nothing more for this maneuver.
        assertEquals(Decision.IGNORE, clockUntil(clockMs + 5000));
    }

    @Test
    public void advanceAlsoPredicted() {
        double mps = 40 / 3.6;
        for (int d = 700; d > 430; d -= 11) {
            assertEquals(Decision.IGNORE, post("MG Road", d / 10 * 10));
            clockMs += 1000;
        }
        // No updates from ~430 m (13 s before the ~290 m advance point, inside the 15 s horizon).
        assertEquals(Decision.ADVANCE, clockUntil(clockMs + 30000));
        assertBetween("predicted advance meters", 250, 320, store.predictedMeters());
    }

    @Test
    public void noPredictionLongAfterTheLastUpdate() {
        ride("MG Road", 30, 800, 400);
        // 400 m at 8.3 m/s: imminent would be due ~38 s later, beyond the 15 s prediction horizon.
        assertEquals(Decision.IGNORE, clockUntil(clockMs + 60000));
    }

    @Test
    public void noPredictionWhenStopped() {
        ride("Ring Road", 18, 300, 60);
        for (int i = 0; i < 10; i++) {
            post("Ring Road", 60);
            clockMs += 1000;
        }
        assertEquals(Decision.IGNORE, clockUntil(clockMs + 10000));
    }

    @Test
    public void noPredictionBeforeAnyManeuver() {
        assertEquals(Decision.IGNORE, store.onClock(1000));
    }
}
