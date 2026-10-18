package com.morsenav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.morsenav.ManeuverParser.Direction;
import com.morsenav.QuizStats.Code;
import com.morsenav.QuizStats.Cue;
import com.morsenav.QuizStats.Device;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

public class QuizStatsTest {

    private static final Cue LEFT_UP = new Cue(Direction.LEFT, false);
    private static final Cue LEFT_NOW = new Cue(Direction.LEFT, true);
    private static final Cue RIGHT_UP = new Cue(Direction.RIGHT, false);

    @Test
    public void sixDistinctCues() {
        assertEquals(6, new HashSet<>(QuizStats.ALL).size());
    }

    @Test
    public void randomCoversAllCues() {
        QuizStats q = new QuizStats(new Random(1));
        Set<Cue> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) seen.add(q.randomCue());
        assertEquals(6, seen.size());
    }

    @Test
    public void scoresExactDirectionAndMisses() {
        QuizStats q = new QuizStats(new Random(1));
        q.record(Code.RHYTHM, Device.WATCH, LEFT_UP, LEFT_UP);    // exact
        q.record(Code.RHYTHM, Device.WATCH, LEFT_UP, LEFT_NOW);   // direction only
        q.record(Code.RHYTHM, Device.WATCH, RIGHT_UP, LEFT_UP);   // confusion
        q.record(Code.RHYTHM, Device.WATCH, RIGHT_UP, null);      // missed
        String s = q.summary();
        assertTrue(s, s.contains("rhythm/watch: 1/4 exact (25%), direction 50%, missed 1"));
        assertTrue(s, s.contains("RIGHT heard as LEFT ×1"));
    }

    @Test
    public void codesAndDevicesAreKeptApart() {
        QuizStats q = new QuizStats(new Random(1));
        q.record(Code.COUNT, Device.PHONE, LEFT_UP, LEFT_UP);
        q.record(Code.RHYTHM, Device.PHONE, LEFT_UP, LEFT_UP);
        q.record(Code.RHYTHM, Device.PHONE, LEFT_UP, LEFT_UP);
        assertEquals(1, q.trials(Code.COUNT, Device.PHONE));
        assertEquals(2, q.trials(Code.RHYTHM, Device.PHONE));
        assertEquals(0, q.trials(Code.RHYTHM, Device.WATCH));
        q.reset();
        assertEquals("No answers yet.", q.summary());
    }
}
