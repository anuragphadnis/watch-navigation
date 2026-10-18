package com.morsenav;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.morsenav.ManeuverParser.Direction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class RhythmCodeTest {

    /** The five distinct cues; a U-turn "now" plays one of the "now" runs. */
    private static final Object[][] CUES = {
            {Direction.LEFT, false}, {Direction.RIGHT, false}, {Direction.U_TURN, false},
            {Direction.LEFT, true}, {Direction.RIGHT, true},
    };

    private static String morse(Direction d, boolean now) {
        StringBuilder sb = new StringBuilder();
        for (boolean e : RhythmCode.elements(d, now)) sb.append(e ? '-' : '.');
        return sb.toString();
    }

    @Test
    public void words() {
        assertEquals("-.", morse(Direction.LEFT, false));
        assertEquals(".-", morse(Direction.RIGHT, false));
        assertEquals("--.", morse(Direction.U_TURN, false));
        assertEquals(".....", morse(Direction.LEFT, true));
        assertEquals("----", morse(Direction.RIGHT, true));
    }

    @Test
    public void uTurnNowPlaysItsArrowsSide() {
        assertEquals(Direction.LEFT, RhythmCode.cueDirection(Direction.U_TURN, true, Direction.LEFT));
        assertEquals(Direction.RIGHT, RhythmCode.cueDirection(Direction.U_TURN, true, Direction.RIGHT));
        assertEquals(RhythmCode.DEFAULT_U_TURN_SIDE, RhythmCode.cueDirection(Direction.U_TURN, true, null));
        assertEquals(Direction.U_TURN, RhythmCode.cueDirection(Direction.U_TURN, false, Direction.LEFT));
        assertEquals(Direction.LEFT, RhythmCode.cueDirection(Direction.LEFT, true, Direction.RIGHT));
        assertEquals(morse(Direction.RIGHT, true), morse(Direction.U_TURN, true));
    }

    @Test
    public void noCueIsTheStartOfAnotherAndAllDifferByTheThirdElement() {
        for (Object[] a : CUES) {
            for (Object[] b : CUES) {
                if (a == b) continue;
                String x = morse((Direction) a[0], (Boolean) a[1]);
                String y = morse((Direction) b[0], (Boolean) b[1]);
                assertFalse(x + " starts " + y, y.startsWith(x));
                int first = 0;
                while (x.charAt(first) == y.charAt(first)) first++;
                assertTrue(x + " vs " + y, first <= 2);
            }
        }
    }

    @Test
    public void watchPatterns() {
        // Upcoming plays twice, 1.2 s of silence (1620 ms notification to notification) between.
        assertArrayEquals(new long[]{150, 150, 150, 720, 1620, 150, 150, 150, 720},
                RhythmCode.watchGaps(Direction.LEFT, false));
        assertArrayEquals(new long[]{720, 150, 150, 150, 1620, 720, 150, 150, 150},
                RhythmCode.watchGaps(Direction.RIGHT, false));
        assertArrayEquals(new long[]{150, 150, 150, 720, 150, 150, 150, 720, 1620, 150, 150, 150, 720, 150, 150, 150, 720},
                RhythmCode.watchGaps(Direction.U_TURN, false));
        assertArrayEquals(new long[]{720, 720, 720, 720}, RhythmCode.watchGaps(Direction.LEFT, true));
        assertArrayEquals(new long[]{150, 150, 150, 720, 150, 150, 150, 720, 150, 150, 150, 720, 150, 150, 150},
                RhythmCode.watchGaps(Direction.RIGHT, true));
    }

    @Test
    public void watchDurations() {
        assertEquals(1590 + 1200 + 1590, RhythmCode.watchDurationMs(Direction.LEFT, false));
        assertEquals(1590 + 1200 + 1590, RhythmCode.watchDurationMs(Direction.RIGHT, false));
        assertEquals(2760 + 1200 + 2760, RhythmCode.watchDurationMs(Direction.U_TURN, false));
        assertEquals(3300, RhythmCode.watchDurationMs(Direction.LEFT, true));
        assertEquals(4380, RhythmCode.watchDurationMs(Direction.RIGHT, true));
    }

    @Test
    public void dashIsAboutTwoDots() {
        double ratio = (double) RhythmCode.watchBuzzMs(RhythmCode.WATCH_DASH_NOTIFICATIONS) / RhythmCode.watchBuzzMs(1);
        assertTrue(String.valueOf(ratio), ratio > 1.8 && ratio < 2.5);
        assertEquals(3.0, (double) RhythmCode.PHONE_DASH_MS / RhythmCode.PHONE_DOT_MS, 0.001);
    }

    @Test
    public void aMissedWatchNotificationNeverProducesAnotherCue() {
        for (Object[] c : CUES) {
            long[] g = RhythmCode.watchGaps((Direction) c[0], (Boolean) c[1]);
            for (int drop = 0; drop <= g.length; drop++) {
                long[] missed = dropTick(g, drop);
                for (Object[] o : CUES) {
                    if (o == c) continue;
                    assertFalse(c[0] + "/" + c[1] + " minus notification " + drop + " looks like " + o[0] + "/" + o[1],
                            Arrays.equals(missed, RhythmCode.watchGaps((Direction) o[0], (Boolean) o[1])));
                }
            }
        }
    }

    /** Gaps after one notification is lost: an end gap disappears, or two neighbouring gaps merge. */
    private static long[] dropTick(long[] gaps, int tick) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < gaps.length; i++) {
            if (tick == 0 && i == 0) continue;
            if (tick == gaps.length && i == gaps.length - 1) continue;
            if (tick > 0 && tick < gaps.length && i == tick - 1) {
                out.add(gaps[i] + gaps[i + 1]);
                i++;
                continue;
            }
            out.add(gaps[i]);
        }
        long[] a = new long[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    @Test
    public void phoneTimings() {
        assertArrayEquals(new long[]{0, 450, 250, 150, 900, 450, 250, 150}, RhythmCode.phoneTimings(Direction.LEFT, false));
        assertArrayEquals(new long[]{0, 150, 250, 450, 900, 150, 250, 450}, RhythmCode.phoneTimings(Direction.RIGHT, false));
        assertArrayEquals(new long[]{0, 150, 250, 150, 250, 150, 250, 150, 250, 150},
                RhythmCode.phoneTimings(Direction.LEFT, true));
    }
}
