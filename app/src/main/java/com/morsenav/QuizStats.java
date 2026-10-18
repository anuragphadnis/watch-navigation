package com.morsenav;

import com.morsenav.ManeuverParser.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** In-memory pattern-quiz results, kept per code (count / rhythm) and device (phone / watch). */
final class QuizStats {

    enum Code { COUNT, RHYTHM }

    enum Device { PHONE, WATCH }

    /** One of the six cues: a direction, upcoming or immediate. */
    static final class Cue {
        final Direction direction;
        final boolean imminent;

        Cue(Direction direction, boolean imminent) {
            this.direction = direction;
            this.imminent = imminent;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Cue && ((Cue) o).direction == direction && ((Cue) o).imminent == imminent;
        }

        @Override
        public int hashCode() {
            return direction.hashCode() * 2 + (imminent ? 1 : 0);
        }

        @Override
        public String toString() {
            String d = direction == Direction.U_TURN ? "U-turn" : direction == Direction.LEFT ? "Left" : "Right";
            return d + (imminent ? " now" : " upcoming");
        }
    }

    static final List<Cue> ALL = new ArrayList<>();

    static {
        for (Direction d : Direction.values()) {
            ALL.add(new Cue(d, false));
            ALL.add(new Cue(d, true));
        }
    }

    private static final class Tally {
        int trials;
        int correct;
        int directionCorrect;
        int missed;
        final Map<String, Integer> confusions = new LinkedHashMap<>();
    }

    private final Map<String, Tally> tallies = new LinkedHashMap<>();
    private final Random random;

    QuizStats(Random random) {
        this.random = random;
    }

    Cue randomCue() {
        return ALL.get(random.nextInt(ALL.size()));
    }

    /** The cues {@code code} has: the rhythm code has no U-turn "now" (it plays left- or right-now). */
    static List<Cue> cues(Code code) {
        List<Cue> list = new ArrayList<>(ALL);
        if (code == Code.RHYTHM) list.remove(new Cue(Direction.U_TURN, true));
        return list;
    }

    Cue randomCue(Code code) {
        List<Cue> list = cues(code);
        return list.get(random.nextInt(list.size()));
    }

    /** {@code answer} null = "didn't feel it". */
    void record(Code code, Device device, Cue actual, Cue answer) {
        Tally t = tallies.computeIfAbsent(code + "/" + device, k -> new Tally());
        t.trials++;
        if (answer == null) {
            t.missed++;
            return;
        }
        if (answer.equals(actual)) t.correct++;
        if (answer.direction == actual.direction) {
            t.directionCorrect++;
        } else {
            t.confusions.merge(actual.direction + " heard as " + answer.direction, 1, Integer::sum);
        }
    }

    int trials(Code code, Device device) {
        Tally t = tallies.get(code + "/" + device);
        return t == null ? 0 : t.trials;
    }

    void reset() {
        tallies.clear();
    }

    String summary() {
        if (tallies.isEmpty()) return "No answers yet.";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Tally> e : tallies.entrySet()) {
            Tally t = e.getValue();
            sb.append(e.getKey().replace('_', ' ').toLowerCase(Locale.ROOT)).append(": ")
                    .append(String.format(Locale.ROOT, "%d/%d exact (%d%%), direction %d%%, missed %d",
                            t.correct, t.trials, pct(t.correct, t.trials), pct(t.directionCorrect, t.trials), t.missed));
            for (Map.Entry<String, Integer> c : t.confusions.entrySet()) {
                sb.append("\n    ").append(c.getKey()).append(" ×").append(c.getValue());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static int pct(int n, int of) {
        return of == 0 ? 0 : Math.round(100f * n / of);
    }
}
