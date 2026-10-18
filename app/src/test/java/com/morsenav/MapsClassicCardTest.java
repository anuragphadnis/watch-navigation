package com.morsenav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import com.morsenav.ManeuverParser.Direction;
import com.morsenav.ManeuverParser.Maneuver;

import java.util.Arrays;

import org.junit.Test;

/**
 * Field lists as Google Maps 26.41 (two-wheeler mode) posts them, where the turn is only in the icon.
 * Road names are made up.
 */
public class MapsClassicCardTest {

    private static final String JUNCTION = "Lakeview Main Rd / Station Main Rd / Temple Main Rd";

    private static Maneuver card(Direction icon, String... fields) {
        return ManeuverParser.parse(Arrays.asList(fields), icon);
    }

    @Test
    public void directionComesFromIcon() {
        Maneuver m = card(Direction.LEFT, "220 m", JUNCTION, "1 min · 450 m · 14:35 ETA");
        assertEquals(Direction.LEFT, m.direction);
        assertEquals(220, m.distanceMeters);
        assertFalse(m.saysNow);
        assertNull(card(null, "220 m", JUNCTION, "1 min · 450 m · 14:35 ETA"));
    }

    @Test
    public void summaryDistanceIsNotTheTurnDistance() {
        assertEquals(-1, card(Direction.RIGHT, JUNCTION, "1 min · 230 m · 14:29 ETA").distanceMeters);
        assertEquals(-1, card(Direction.RIGHT, "towards Old Mill Rd / Canal Main Rd",
                "3 min · 700 m · 14:30 ETA").distanceMeters);
    }

    @Test
    public void keyFollowsRoadNotDistanceOrEta() {
        String a = card(Direction.LEFT, "350 m", JUNCTION, "2 min · 600 m · 14:35 ETA").key;
        String b = card(Direction.LEFT, "210 m", JUNCTION, "1 min · 450 m · 14:34 ETA").key;
        String c = card(Direction.LEFT, "40 m", "Market Rd", "1 min · 190 m · 14:29 ETA").key;
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

}
