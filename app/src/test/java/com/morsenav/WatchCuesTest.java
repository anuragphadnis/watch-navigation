package com.morsenav;

import static org.junit.Assert.assertEquals;

import com.morsenav.ManeuverParser.Direction;

import org.junit.Test;

public class WatchCuesTest {

    @Test
    public void tickCountsMatchPhoneVocabulary() {
        assertEquals(2, WatchCues.ticks(Direction.LEFT));
        assertEquals(3, WatchCues.ticks(Direction.RIGHT));
        assertEquals(4, WatchCues.ticks(Direction.U_TURN));
    }

    @Test
    public void titlesPutTheArrowOnTheSideOfTheTurn() {
        assertEquals("<< LEFT 200 m", WatchCues.title(Direction.LEFT, false, 200));
        assertEquals("<< LEFT NOW", WatchCues.title(Direction.LEFT, true, 40));
        assertEquals("RIGHT 150 m >>", WatchCues.title(Direction.RIGHT, false, 150));
        assertEquals("RIGHT NOW >>", WatchCues.title(Direction.RIGHT, true, 30));
        assertEquals("RIGHT >>", WatchCues.title(Direction.RIGHT, false, -1));
        assertEquals("<< U-TURN NOW", WatchCues.title(Direction.U_TURN, true, 40));
    }

    @Test
    public void titlesAreAscii() {
        for (Direction d : Direction.values()) {
            String t = WatchCues.title(d, false, 12345);
            assertEquals(t, WatchCues.ascii(t));
        }
    }

    @Test
    public void nonAsciiIsDropped() {
        assertEquals("MG Road - Indiranagar", WatchCues.ascii("MG Road \u00b7 - Indiranagar"));
        assertEquals("Rd", WatchCues.ascii("\u0cae\u0cb9\u0cbe\u0ca4\u0ccd\u0cae Rd"));
        assertEquals("", WatchCues.ascii("\u2190"));
    }

    @Test
    public void titleFitsTheWatchField() {
        assertEquals(true, WatchCues.title(Direction.U_TURN, false, 99999).getBytes().length <= 31);
    }

    @Test
    public void labelKeepsMapsCasing() {
        ManeuverParser.Maneuver m = ManeuverParser.parse(java.util.Arrays.asList(
                "220 m", "Lakeview Main Rd / Station Main Rd", "1 min · 450 m · 14:35 ETA"), Direction.LEFT);
        assertEquals("Lakeview Main Rd / Station Main Rd", m.label);
    }
}
