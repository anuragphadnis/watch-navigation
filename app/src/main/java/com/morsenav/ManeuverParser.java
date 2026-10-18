package com.morsenav;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the next maneuver from a Google Maps navigation notification.
 *
 * <p>Maps' classic card carries the turn only in its icon; the text is
 * {@code [distance, road, "1 min · 450 m · 14:35 ETA"]}. So the direction normally comes from the
 * icon, with the text supplying distance and road. Text such as "Turn left onto X" is still
 * recognised in case Maps shows it.
 */
public final class ManeuverParser {

    public enum Direction { LEFT, RIGHT, U_TURN }

    private static final Pattern U_TURN = Pattern.compile("\\bu[- ]?turn\\b");
    // "turn left", "keep right", "slight left", "turn sharp right", "bear left" ...
    private static final Pattern TURN = Pattern.compile(
            "\\b(?:turn|keep|bear|slight|sharp)\\s+(?:(?:slight|slightly|sharp)\\s+)?(left|right)\\b");
    private static final Pattern NOW = Pattern.compile("\\bnow\\b");
    // "200 m", "50m", "1.2 km" -- but not "20 min".
    private static final Pattern DISTANCE = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s?(m|km)\\b");
    // Trip summary line, "1 min · 450 m · 14:35 ETA": its distance is to the destination, not the turn.
    private static final Pattern SUMMARY = Pattern.compile("·|\\beta\\b");

    public static final class Maneuver {
        public final Direction direction;
        /** Maps said "now"; distance-based timing is decided by {@link NavStore}. */
        public final boolean saysNow;
        /** Distance to the maneuver in meters, or -1 if the notification did not show one. */
        public final int distanceMeters;
        /** Direction plus instruction/road text without distances; identifies the maneuver across updates. */
        public final String key;
        /** The road or instruction line as Maps shows it, for display on the watch. */
        public final String label;
        /** For a U-turn, the side Maps' arrow turns to (LEFT/RIGHT), or null if unknown. */
        public final Direction uTurnSide;

        Maneuver(Direction direction, boolean saysNow, int distanceMeters, String key, String label) {
            this(direction, saysNow, distanceMeters, key, label, null);
        }

        Maneuver(Direction direction, boolean saysNow, int distanceMeters, String key, String label,
                 Direction uTurnSide) {
            this.direction = direction;
            this.saysNow = saysNow;
            this.distanceMeters = distanceMeters;
            this.key = key;
            this.label = label;
            this.uTurnSide = uTurnSide;
        }

        Maneuver withUTurnSide(Direction side) {
            return new Maneuver(direction, saysNow, distanceMeters, key, label, side);
        }

        @Override
        public String toString() {
            return direction + (saysNow ? " NOW" : "")
                    + (distanceMeters >= 0 ? " " + distanceMeters + "m" : "") + " [" + key + "]";
        }
    }

    private ManeuverParser() {}

    public static Maneuver parse(List<String> fields) {
        return parse(fields, null);
    }

    /**
     * Returns the maneuver described by {@code fields}, using {@code iconDirection} (from the
     * notification's arrow, may be null) when the text names no turn. Null if neither does.
     */
    public static Maneuver parse(List<String> fields, Direction iconDirection) {
        String label = null;
        Direction direction = null;

        // The current instruction comes before any "then ..." hint, so take the first match.
        for (String field : fields) {
            direction = textDirection(field.toLowerCase(Locale.ROOT));
            if (direction != null) {
                label = field;
                break;
            }
        }
        if (direction == null) {
            direction = iconDirection;
            label = roadField(fields);
        }
        String instruction = label.toLowerCase(Locale.ROOT);
        if (direction == null) return null;

        int distance = -1;
        boolean now = false;
        for (String field : fields) {
            String lower = field.toLowerCase(Locale.ROOT);
            if (distance < 0 && !SUMMARY.matcher(lower).find()) distance = findDistance(lower);
            if (!now) now = NOW.matcher(lower).find();
        }
        return new Maneuver(direction, now, distance, direction + "|" + normalize(instruction), label.trim());
    }

    private static Direction textDirection(String lower) {
        Direction direction = null;
        int bestPos = Integer.MAX_VALUE;
        Matcher u = U_TURN.matcher(lower);
        if (u.find()) {
            bestPos = u.start();
            direction = Direction.U_TURN;
        }
        Matcher t = TURN.matcher(lower);
        if (t.find() && t.start() < bestPos) {
            direction = "left".equals(t.group(1)) ? Direction.LEFT : Direction.RIGHT;
        }
        return direction;
    }

    /** The road/destination line: the first field that is neither a bare distance nor the trip summary. */
    private static String roadField(List<String> fields) {
        for (String field : fields) {
            String lower = field.toLowerCase(Locale.ROOT);
            if (SUMMARY.matcher(lower).find()) continue;
            if (normalize(lower).isEmpty()) continue;
            return field;
        }
        return "";
    }

    private static int findDistance(String lower) {
        Matcher m = DISTANCE.matcher(lower);
        if (!m.find()) return -1;
        double value = Double.parseDouble(m.group(1));
        return (int) Math.round("km".equals(m.group(2)) ? value * 1000 : value);
    }

    private static String normalize(String lower) {
        String s = DISTANCE.matcher(lower).replaceAll(" ");
        s = NOW.matcher(s).replaceAll(" ");
        s = s.replaceAll("\\d", "");
        return s.replaceAll("\\s+", " ").trim();
    }
}
