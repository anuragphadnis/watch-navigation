package com.morsenav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.morsenav.ManeuverParser.Direction;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class IconMatchTest {

    private static final int N = IconMatch.SIZE * IconMatch.SIZE;

    /** A mask with the left or right half inked, standing in for an arrow. */
    private static int[] half(boolean left) {
        int[] m = new int[N];
        for (int i = 0; i < N; i++) {
            boolean isLeft = (i % IconMatch.SIZE) < IconMatch.SIZE / 2;
            m[i] = isLeft == left ? 255 : 0;
        }
        return m;
    }

    private static int[] noisy(int[] base, int delta) {
        int[] m = Arrays.copyOf(base, base.length);
        for (int i = 0; i < m.length; i += 7) m[i] = Math.max(0, Math.min(255, m[i] + delta));
        return m;
    }

    private static Map<String, int[]> refs() {
        Map<String, int[]> refs = new HashMap<>();
        refs.put("maneuver_turn_normal_left", half(true));
        refs.put("maneuver_turn_normal_right", half(false));
        refs.put("maneuver_straight", new int[N]);
        return refs;
    }

    @Test
    public void mapsDrawableNamesToCues() {
        assertEquals(Direction.LEFT, ManeuverIcons.direction("maneuver_turn_sharp_left"));
        assertEquals(Direction.LEFT, ManeuverIcons.direction("maneuver_keep_left"));
        assertEquals(Direction.LEFT, ManeuverIcons.direction("maneuver_fork_left"));
        assertEquals(Direction.RIGHT, ManeuverIcons.direction("maneuver_off_ramp_slight_right"));
        assertEquals(Direction.U_TURN, ManeuverIcons.direction("maneuver_u_turn_left"));
        assertEquals(Direction.U_TURN, ManeuverIcons.direction("maneuver_on_ramp_u_turn_right"));
        assertEquals(Direction.LEFT, ManeuverIcons.uTurnSide("maneuver_u_turn_left"));
        assertEquals(Direction.RIGHT, ManeuverIcons.uTurnSide("maneuver_on_ramp_u_turn_right"));
        assertNull(ManeuverIcons.uTurnSide("maneuver_turn_left"));
        assertNull(ManeuverIcons.uTurnSide("maneuver_roundabout_enter_and_exit_cw_u_turn"));
        assertNull(ManeuverIcons.direction("maneuver_straight"));
        assertNull(ManeuverIcons.direction("maneuver_depart"));
        assertNull(ManeuverIcons.direction("maneuver_destination_left"));
        assertNull(ManeuverIcons.direction("maneuver_merge_left"));
        assertNull(ManeuverIcons.direction("maneuver_roundabout_enter_and_exit_ccw_normal_left"));
        assertNull(ManeuverIcons.direction("maneuver_roundabout_enter_and_exit_cw_u_turn"));
    }

    @Test
    public void everyListedNameIsClassifiable() {
        int cues = 0;
        for (String name : ManeuverIcons.NAMES) if (ManeuverIcons.direction(name) != null) cues++;
        assertEquals(64, ManeuverIcons.NAMES.length);
        assertEquals(32, cues);
    }

    @Test
    public void closeMatchIsAccepted() {
        IconMatch m = IconMatch.classify(noisy(half(true), -60), refs());
        assertEquals("maneuver_turn_normal_left", m.name);
        assertTrue(m.accepted);
        assertEquals(Direction.LEFT, m.cue());
    }

    @Test
    public void unlikeAnyReferenceIsRejected() {
        int[] checker = new int[N];
        for (int i = 0; i < N; i++) checker[i] = (i % 2 == 0) ? 255 : 0;
        IconMatch m = IconMatch.classify(checker, refs());
        assertFalse(m.accepted);
        assertNull(m.cue());
    }

    @Test
    public void ambiguousBetweenDifferentCuesIsRejected() {
        Map<String, int[]> refs = refs();
        refs.put("maneuver_u_turn_left", noisy(half(true), -40));
        IconMatch m = IconMatch.classify(half(true), refs);
        assertFalse(m.accepted);
    }

    @Test
    public void lookAlikesWithSameCueAreNotAmbiguous() {
        Map<String, int[]> refs = refs();
        refs.put("maneuver_turn_slight_left", noisy(half(true), -40));
        IconMatch m = IconMatch.classify(half(true), refs);
        assertTrue(m.accepted);
        assertEquals(Direction.LEFT, m.cue());
    }

    @Test
    public void noReferences() {
        assertNull(IconMatch.classify(half(true), new HashMap<>()));
    }
}
