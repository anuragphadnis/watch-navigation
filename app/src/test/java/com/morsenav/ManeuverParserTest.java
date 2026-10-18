package com.morsenav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.morsenav.ManeuverParser.Direction;
import com.morsenav.ManeuverParser.Maneuver;

import java.util.Arrays;

import org.junit.Test;

public class ManeuverParserTest {

    private static Maneuver parse(String... fields) {
        return ManeuverParser.parse(Arrays.asList(fields));
    }

    @Test
    public void basicDirections() {
        assertEquals(Direction.LEFT, parse("Turn left onto MG Road", "200 m").direction);
        assertEquals(Direction.RIGHT, parse("Turn right onto MG Road", "200 m").direction);
        assertEquals(Direction.U_TURN, parse("Make a U-turn", "200 m").direction);
        assertEquals(Direction.U_TURN, parse("Make a uturn", "200 m").direction);
    }

    @Test
    public void minorMovesCountAsTurns() {
        assertEquals(Direction.LEFT, parse("Keep left at the fork").direction);
        assertEquals(Direction.RIGHT, parse("Slight right onto NH 48").direction);
        assertEquals(Direction.LEFT, parse("Turn sharp left").direction);
        assertEquals(Direction.RIGHT, parse("Turn slight right toward Flyover").direction);
    }

    @Test
    public void landmarkNamesDoNotTrigger() {
        assertNull(parse("Continue onto Right Choice Lane", "500 m"));
        assertNull(parse("Head north toward Left Bank Road"));
        assertEquals(Direction.LEFT, parse("Turn left after Right Choice Store").direction);
    }

    @Test
    public void firstInstructionWins() {
        assertEquals(Direction.LEFT, parse("Turn left, then turn right").direction);
        assertEquals(Direction.U_TURN, parse("Make a U-turn, then turn left").direction);
        assertEquals(Direction.RIGHT, parse("Turn right, then make a U-turn").direction);
    }

    @Test
    public void saysNow() {
        assertTrue(parse("Turn left now").saysNow);
        assertFalse(parse("Turn left", "20 m").saysNow);
        assertFalse(parse("Turn left onto Snow Lane", "300 m").saysNow);
        assertFalse(parse("Turn left", "300 m", "Known traffic · 20 min").saysNow);
    }

    @Test
    public void distanceParsing() {
        assertEquals(200, parse("Turn left", "200 m").distanceMeters);
        assertEquals(1200, parse("Turn left", "1.2 km").distanceMeters);
        assertEquals(-1, parse("Turn left", "ETA 12 min").distanceMeters);
    }

    @Test
    public void keyIgnoresDistance() {
        assertEquals(parse("200 m · Turn left onto MG Road").key, parse("40 m · Turn left onto MG Road").key);
        assertNotEquals(parse("Turn left onto MG Road").key, parse("Turn left onto Park Street").key);
    }
}
