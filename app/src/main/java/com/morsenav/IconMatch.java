package com.morsenav;

import com.morsenav.ManeuverParser.Direction;

import java.util.Map;

/**
 * Nearest-reference classification of a maneuver icon. Icons are compared as SIZE x SIZE alpha
 * masks by mean absolute difference (0 = identical, 255 = opposite).
 */
final class IconMatch {

    static final int SIZE = 32;
    /** Best match must be at least this close... */
    static final double MAX_DISTANCE = 20;
    /** ...and this much closer than the best reference that would give a different cue. */
    static final double MIN_MARGIN = 8;

    final String name;
    final Direction direction;
    final double distance;
    final String runnerUp;
    final double runnerUpDistance;
    final boolean accepted;

    private IconMatch(String name, Direction direction, double distance,
                      String runnerUp, double runnerUpDistance) {
        this.name = name;
        this.direction = direction;
        this.distance = distance;
        this.runnerUp = runnerUp;
        this.runnerUpDistance = runnerUpDistance;
        this.accepted = distance <= MAX_DISTANCE && runnerUpDistance - distance >= MIN_MARGIN;
    }

    /** The cue to play, or null if there is none or the match is not trustworthy. */
    Direction cue() {
        return accepted ? direction : null;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "%s %s (%.1f) vs %s (%.1f)",
                accepted ? "MATCH" : "REJECT", name, distance, runnerUp, runnerUpDistance);
    }

    /** Returns null if there are no references. */
    static IconMatch classify(int[] sample, Map<String, int[]> references) {
        String bestName = null;
        double best = Double.MAX_VALUE;
        for (Map.Entry<String, int[]> e : references.entrySet()) {
            double d = distance(sample, e.getValue());
            if (d < best) {
                best = d;
                bestName = e.getKey();
            }
        }
        if (bestName == null) return null;

        // Runner-up is the closest reference that would produce a different cue, so look-alikes
        // with the same meaning (e.g. turn_slight_left vs keep_left) don't count as ambiguity.
        Direction bestDirection = ManeuverIcons.direction(bestName);
        String runnerName = "none";
        double runner = 255;
        for (Map.Entry<String, int[]> e : references.entrySet()) {
            if (ManeuverIcons.direction(e.getKey()) == bestDirection) continue;
            double d = distance(sample, e.getValue());
            if (d < runner) {
                runner = d;
                runnerName = e.getKey();
            }
        }
        return new IconMatch(bestName, bestDirection, best, runnerName, runner);
    }

    static double distance(int[] a, int[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i]);
        return (double) sum / a.length;
    }
}
