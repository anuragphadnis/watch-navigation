package com.morsenav;

import com.morsenav.ManeuverParser.Direction;

/**
 * Google Maps' maneuver drawables, by resource name (as shipped in Maps 26.41). Maps' navigation
 * notification shows the turn only as one of these bitmaps, so the posted icon is matched against them.
 */
final class ManeuverIcons {

    static final String[] NAMES = {
            "maneuver_depart",
            "maneuver_destination",
            "maneuver_destination_left",
            "maneuver_destination_right",
            "maneuver_destination_straight",
            "maneuver_fork_left",
            "maneuver_fork_right",
            "maneuver_keep_left",
            "maneuver_keep_right",
            "maneuver_merge",
            "maneuver_merge_left",
            "maneuver_merge_right",
            "maneuver_name_change",
            "maneuver_off_ramp_keep_left",
            "maneuver_off_ramp_keep_right",
            "maneuver_off_ramp_normal_left",
            "maneuver_off_ramp_normal_right",
            "maneuver_off_ramp_sharp_left",
            "maneuver_off_ramp_sharp_right",
            "maneuver_off_ramp_slight_left",
            "maneuver_off_ramp_slight_right",
            "maneuver_off_ramp_u_turn_left",
            "maneuver_off_ramp_u_turn_right",
            "maneuver_on_ramp_keep_left",
            "maneuver_on_ramp_keep_right",
            "maneuver_on_ramp_normal_left",
            "maneuver_on_ramp_normal_right",
            "maneuver_on_ramp_sharp_left",
            "maneuver_on_ramp_sharp_right",
            "maneuver_on_ramp_slight_left",
            "maneuver_on_ramp_slight_right",
            "maneuver_on_ramp_u_turn_left",
            "maneuver_on_ramp_u_turn_right",
            "maneuver_roundabout_enter_and_exit_ccw",
            "maneuver_roundabout_enter_and_exit_ccw_normal_left",
            "maneuver_roundabout_enter_and_exit_ccw_normal_right",
            "maneuver_roundabout_enter_and_exit_ccw_sharp_left",
            "maneuver_roundabout_enter_and_exit_ccw_sharp_right",
            "maneuver_roundabout_enter_and_exit_ccw_slight_left",
            "maneuver_roundabout_enter_and_exit_ccw_slight_right",
            "maneuver_roundabout_enter_and_exit_ccw_straight",
            "maneuver_roundabout_enter_and_exit_ccw_u_turn",
            "maneuver_roundabout_enter_and_exit_cw",
            "maneuver_roundabout_enter_and_exit_cw_normal_left",
            "maneuver_roundabout_enter_and_exit_cw_normal_right",
            "maneuver_roundabout_enter_and_exit_cw_sharp_left",
            "maneuver_roundabout_enter_and_exit_cw_sharp_right",
            "maneuver_roundabout_enter_and_exit_cw_slight_left",
            "maneuver_roundabout_enter_and_exit_cw_slight_right",
            "maneuver_roundabout_enter_and_exit_cw_straight",
            "maneuver_roundabout_enter_and_exit_cw_u_turn",
            "maneuver_roundabout_enter_ccw",
            "maneuver_roundabout_enter_cw",
            "maneuver_roundabout_exit_ccw",
            "maneuver_roundabout_exit_cw",
            "maneuver_straight",
            "maneuver_turn_normal_left",
            "maneuver_turn_normal_right",
            "maneuver_turn_sharp_left",
            "maneuver_turn_sharp_right",
            "maneuver_turn_slight_left",
            "maneuver_turn_slight_right",
            "maneuver_u_turn_left",
            "maneuver_u_turn_right",
    };

    private ManeuverIcons() {}

    /**
     * The cue for a drawable, or null for maneuvers that get no cue: straight, depart, arrive,
     * merges, and roundabouts (Phase 2.1). Keep/fork/slight/sharp and ramps count as left/right.
     */
    /** LEFT/RIGHT for a U-turn icon such as maneuver_u_turn_left, else null. */
    static Direction uTurnSide(String name) {
        if (name == null || !name.contains("u_turn")) return null;
        if (name.endsWith("_left")) return Direction.LEFT;
        if (name.endsWith("_right")) return Direction.RIGHT;
        return null;
    }

    static Direction direction(String name) {
        if (name.contains("roundabout") || name.contains("destination") || name.contains("merge")) {
            return null;
        }
        if (name.contains("u_turn")) return Direction.U_TURN;
        if (name.endsWith("_left")) return Direction.LEFT;
        if (name.endsWith("_right")) return Direction.RIGHT;
        return null;
    }
}
