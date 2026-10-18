package com.morsenav;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * In-memory record of what has already been announced, so that Maps' frequent notification
 * updates produce one advance cue and one imminent cue per maneuver instead of a buzz per update.
 *
 * <p>Cues are timed by seconds to the turn: speed is estimated from how fast the distance to the
 * turn drops, and each cue fires once the turn is within that many seconds. Distances are clamped
 * so a poor estimate cannot produce silly values, and fixed distances are used until speed is known.
 */
public final class NavStore {

    public enum Decision { IGNORE, ADVANCE, IMMINENT }

    // Seconds before the turn at which each cue is triggered; felt ~1 s later after Maps' update lag.
    static final double ADVANCE_SECONDS = 26;
    static final double IMMINENT_SECONDS = 10;

    static final int ADVANCE_MIN_METERS = 100;
    static final int ADVANCE_MAX_METERS = 500;
    static final int IMMINENT_MIN_METERS = 30;
    static final int IMMINENT_MAX_METERS = 150;

    /** Used while speed is unknown (start of a ride, after a reroute). */
    static final int FALLBACK_ADVANCE_METERS = 200;
    static final int FALLBACK_IMMINENT_METERS = 80;

    /** Below this (~4 km/h) the rider is treated as stopped: no time-based cues, imminent only at the minimum. */
    static final double STOPPED_MPS = 1.1;

    // Speed is measured against the newest sample at least 3 s old, which smooths Maps' 10 m distance
    // steps; samples are kept for 15 s because with the screen off Maps may update only every ~10 s.
    static final long SPEED_MIN_WINDOW_MS = 3000;
    static final long SPEED_MAX_WINDOW_MS = 15000;
    /** A speed estimate not refreshed for this long is dropped, and distance is no longer predicted. */
    static final long SPEED_STALE_MS = 15000;

    /**
     * If the distance for the same instruction grows by more than this after its imminent cue, the
     * maneuver was passed and an identically worded one is next (e.g. "Turn left" twice in a row).
     * Before the imminent cue a jump is a reroute, and the cues already given stand.
     */
    static final int REAPPEAR_MARGIN_METERS = 50;

    private enum Phase { NONE, ADVANCED, IMMINENT }

    private static final class Sample {
        final long timeMs;
        final int meters;

        Sample(long timeMs, int meters) {
            this.timeMs = timeMs;
            this.meters = meters;
        }
    }

    private String lastKey;
    private Phase phase = Phase.NONE;
    private int lastDistance = -1;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private double speedMps = Double.NaN;
    private long speedTimeMs;

    // Thresholds used for the last decision, for logging.
    private int advanceMeters;
    private int imminentMeters;
    private String mode = "fallback";
    private int predictedMeters = -1;

    public synchronized Decision onManeuver(ManeuverParser.Maneuver m, long nowMs) {
        boolean isNew = !m.key.equals(lastKey)
                || (phase == Phase.IMMINENT && m.distanceMeters >= 0 && lastDistance >= 0
                        && m.distanceMeters > lastDistance + REAPPEAR_MARGIN_METERS);
        if (isNew) {
            lastKey = m.key;
            phase = Phase.NONE;
            lastDistance = -1;
            samples.clear();
        }
        if (m.distanceMeters >= 0) {
            lastDistance = m.distanceMeters;
            updateSpeed(nowMs, m.distanceMeters);
        }
        updateThresholds(nowMs);

        int d = m.distanceMeters;
        boolean imminent = m.saysNow || (d >= 0 && d <= imminentMeters);
        if (phase != Phase.IMMINENT && imminent) {
            phase = Phase.IMMINENT;
            return Decision.IMMINENT;
        }
        if (phase == Phase.NONE && d >= 0 && d <= advanceMeters) {
            phase = Phase.ADVANCED;
            return Decision.ADVANCE;
        }
        return Decision.IGNORE;
    }

    private void updateSpeed(long nowMs, int meters) {
        samples.addLast(new Sample(nowMs, meters));
        while (nowMs - samples.peekFirst().timeMs > SPEED_MAX_WINDOW_MS) samples.removeFirst();

        Sample reference = null;
        for (java.util.Iterator<Sample> it = samples.descendingIterator(); it.hasNext(); ) {
            Sample sample = it.next();
            if (nowMs - sample.timeMs >= SPEED_MIN_WINDOW_MS) {
                reference = sample;
                break;
            }
        }
        if (reference == null) return;
        long dt = nowMs - reference.timeMs;
        double v = Math.max(0, (reference.meters - meters) * 1000.0 / dt);
        speedMps = Double.isNaN(speedMps) || nowMs - speedTimeMs > SPEED_STALE_MS ? v : (speedMps + v) / 2;
        speedTimeMs = nowMs;
    }

    /**
     * Between Maps updates (which can be ~10 s apart with the screen off), predicts the distance from
     * the last one and the speed, so cues still fire on time. Call regularly, e.g. every 500 ms.
     */
    public synchronized Decision onClock(long nowMs) {
        if (lastKey == null || phase == Phase.IMMINENT || samples.isEmpty()) return Decision.IGNORE;
        updateThresholds(nowMs);
        if (!"timed".equals(mode)) return Decision.IGNORE;
        Sample last = samples.peekLast();
        double predicted = last.meters - speedMps * (nowMs - last.timeMs) / 1000.0;
        predictedMeters = (int) Math.max(0, Math.round(predicted));
        if (predicted <= imminentMeters) {
            phase = Phase.IMMINENT;
            return Decision.IMMINENT;
        }
        if (phase == Phase.NONE && predicted <= advanceMeters) {
            phase = Phase.ADVANCED;
            return Decision.ADVANCE;
        }
        return Decision.IGNORE;
    }

    /** Distance predicted by the last {@link #onClock} call. */
    public synchronized int predictedMeters() {
        return predictedMeters;
    }

    private void updateThresholds(long nowMs) {
        boolean known = !Double.isNaN(speedMps) && nowMs - speedTimeMs <= SPEED_STALE_MS;
        if (!known) {
            mode = "fallback";
            advanceMeters = FALLBACK_ADVANCE_METERS;
            imminentMeters = FALLBACK_IMMINENT_METERS;
        } else if (speedMps < STOPPED_MPS) {
            mode = "stopped";
            advanceMeters = -1;
            imminentMeters = IMMINENT_MIN_METERS;
        } else {
            mode = "timed";
            advanceMeters = clamp(speedMps * ADVANCE_SECONDS, ADVANCE_MIN_METERS, ADVANCE_MAX_METERS);
            imminentMeters = clamp(speedMps * IMMINENT_SECONDS, IMMINENT_MIN_METERS, IMMINENT_MAX_METERS);
        }
    }

    private static int clamp(double meters, int min, int max) {
        return (int) Math.round(Math.max(min, Math.min(max, meters)));
    }

    /** Speed and thresholds behind the last decision, e.g. "31 km/h timed, advance<=215m now<=86m". */
    public synchronized String describe() {
        String speed = Double.isNaN(speedMps) ? "speed unknown" : String.format(Locale.ROOT, "%.0f km/h", speedMps * 3.6);
        return speed + " " + mode
                + ", advance<=" + (advanceMeters < 0 ? "off" : advanceMeters + "m")
                + " now<=" + imminentMeters + "m";
    }

    public synchronized void reset() {
        lastKey = null;
        phase = Phase.NONE;
        lastDistance = -1;
        samples.clear();
        speedMps = Double.NaN;
    }
}
