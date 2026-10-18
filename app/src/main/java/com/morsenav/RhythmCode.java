package com.morsenav;

import com.morsenav.ManeuverParser.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * The rhythm code: Morse-style dots and dashes.
 *
 * <pre>
 * left upcoming      — •
 * right upcoming     • —          (mirror of left)
 * U-turn upcoming    — — •
 * left now           • • • • •    a run of dots
 * right now          — — — —      a run of dashes
 * </pre>
 * "Upcoming" is a short mixed pattern, played twice so that missing one still says "pay attention";
 * "now" is a run of one element, played once and felt like an alarm. A U-turn
 * has no "now" cue of its own: it plays left-now or right-now by the side of Maps' U-turn arrow
 * ({@link #cueDirection}). No cue is the start of another, so the rider never waits to see whether
 * more is coming; every cue differs from every other by its 3rd element at the latest.
 *
 * <p>On the watch a dot is one notification (its fixed ~420 ms buzz; nothing we send cuts it short)
 * and a dash is a chain of notifications {@link #WATCH_CHAIN_SPACING_MS} apart: each one restarts the
 * buzz timer, so the buzz lasts {@code 420 + 150 x (n - 1)} ms (measured 2026-10-18, within ~40 ms).
 */
final class RhythmCode {

    static final boolean DOT = false;
    static final boolean DASH = true;

    static final int NOW_LEFT_DOTS = 5;
    static final int NOW_RIGHT_DASHES = 4;
    /** U-turn "now" side when Maps gives no arrow: India drives on the left, so U-turns go right. */
    static final Direction DEFAULT_U_TURN_SIDE = Direction.RIGHT;

    // Watch: a notification buzzes ~420 ms; one sent within that restarts the buzz.
    static final long WATCH_BUZZ_MS = 420;
    static final long WATCH_CHAIN_SPACING_MS = 150;
    /** Notifications per dash: 4 = ~870 ms, about twice a dot. */
    static final int WATCH_DASH_NOTIFICATIONS = 4;
    /** Silence between elements, and between the two plays of an upcoming cue. */
    static final long WATCH_ELEMENT_GAP_MS = 300;
    static final long WATCH_REPEAT_PAUSE_MS = 1200;
    static final int UPCOMING_PLAYS = 2;

    // Phone: element lengths and spacing, Morse's 1:3.
    static final long PHONE_DOT_MS = 150;
    static final long PHONE_DASH_MS = 450;
    static final long PHONE_ELEMENT_GAP_MS = 250;
    static final long PHONE_REPEAT_PAUSE_MS = 900;

    private RhythmCode() {}

    /** The cue to play: a U-turn "now" becomes left-now or right-now by {@code uTurnSide} (may be null). */
    static Direction cueDirection(Direction direction, boolean imminent, Direction uTurnSide) {
        if (direction != Direction.U_TURN || !imminent) return direction;
        return uTurnSide != null ? uTurnSide : DEFAULT_U_TURN_SIDE;
    }

    /** Dots and dashes of a cue. A U-turn "now" is played as {@link #DEFAULT_U_TURN_SIDE}'s. */
    static boolean[] elements(Direction direction, boolean imminent) {
        direction = cueDirection(direction, imminent, null);
        if (imminent) {
            boolean[] run = new boolean[direction == Direction.LEFT ? NOW_LEFT_DOTS : NOW_RIGHT_DASHES];
            java.util.Arrays.fill(run, direction != Direction.LEFT);
            return run;
        }
        switch (direction) {
            case LEFT: return new boolean[]{DASH, DOT};
            case RIGHT: return new boolean[]{DOT, DASH};
            default: return new boolean[]{DASH, DASH, DOT};
        }
    }

    static int plays(boolean imminent) {
        return imminent ? 1 : UPCOMING_PLAYS;
    }

    /** How long the watch buzzes for {@code notifications} chained notifications. */
    static long watchBuzzMs(int notifications) {
        return WATCH_BUZZ_MS + WATCH_CHAIN_SPACING_MS * (notifications - 1);
    }

    /**
     * Gaps between successive watch notifications (notifications = gaps + 1): the chain spacing within
     * a dash, and after each element its buzz tail plus the silence before the next element.
     */
    static long[] watchGaps(Direction direction, boolean imminent) {
        List<Long> gaps = new ArrayList<>();
        boolean[] elements = elements(direction, imminent);
        for (int play = 0; play < plays(imminent); play++) {
            for (int k = 0; k < elements.length; k++) {
                if (k > 0) gaps.add(WATCH_BUZZ_MS + WATCH_ELEMENT_GAP_MS);
                else if (play > 0) gaps.add(WATCH_BUZZ_MS + WATCH_REPEAT_PAUSE_MS);
                int notifications = elements[k] == DASH ? WATCH_DASH_NOTIFICATIONS : 1;
                for (int i = 1; i < notifications; i++) gaps.add(WATCH_CHAIN_SPACING_MS);
            }
        }
        long[] out = new long[gaps.size()];
        for (int i = 0; i < out.length; i++) out[i] = gaps.get(i);
        return out;
    }

    /** Buzz time of a watch cue from its first notification to the end of the last buzz. */
    static long watchDurationMs(Direction direction, boolean imminent) {
        long sum = WATCH_BUZZ_MS;
        for (long g : watchGaps(direction, imminent)) sum += g;
        return sum;
    }

    /** VibrationEffect.createWaveform timings: off, on, off, on, ... starting with 0. */
    static long[] phoneTimings(Direction direction, boolean imminent) {
        boolean[] e = elements(direction, imminent);
        long[] t = new long[plays(imminent) * e.length * 2];
        int i = 0;
        for (int play = 0; play < plays(imminent); play++) {
            for (int k = 0; k < e.length; k++) {
                t[i++] = k > 0 ? PHONE_ELEMENT_GAP_MS : play > 0 ? PHONE_REPEAT_PAUSE_MS : 0;
                t[i++] = e[k] == DASH ? PHONE_DASH_MS : PHONE_DOT_MS;
            }
        }
        return t;
    }
}
