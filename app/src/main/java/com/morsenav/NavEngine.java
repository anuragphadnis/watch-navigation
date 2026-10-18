package com.morsenav;

import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/** Ties parsing, the in-memory store and haptics together. One instance per process. */
final class NavEngine {

    /** Where cues are played. In memory only; resets to BOTH when the app restarts. */
    enum Output { BOTH, PHONE, WATCH }

    private static NavEngine instance;

    /** How often distance is predicted between Maps updates during a ride. */
    private static final long CLOCK_MS = 500;

    private volatile Output output = Output.BOTH;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clock = this::onClock;
    private boolean riding;
    /** The maneuver of the latest Maps update, for cues fired by prediction. */
    private ManeuverParser.Maneuver current;
    /** Null below Android 13, where the watch link's GATT calls are unavailable. */
    private final WatchCues watch;

    private final NavStore store = new NavStore();
    private final Haptics haptics;
    private final IconManeuverMatcher iconMatcher;
    /** Last icon match logged, to log only when the arrow changes. */
    private String lastIconMatch;
    /** Notification key of the Maps notification that last produced a maneuver. */
    private String navNotificationKey;

    private NavEngine(Context context) {
        haptics = new Haptics(context);
        iconMatcher = new IconManeuverMatcher(context);
        watch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? new WatchCues(context) : null;
    }

    Output output() {
        return output;
    }

    void setOutput(Output output) {
        this.output = output;
        DebugLog.add("Cue output: " + output);
    }

    /** Whether the watch link is up, or null if watch cues are unavailable on this Android version. */
    Boolean watchReady() {
        return watch != null ? watch.isReady() : null;
    }

    synchronized void startRide() {
        reset();
        riding = true;
        handler.removeCallbacks(stopTestWatch);
        handler.removeCallbacks(clock);
        handler.postDelayed(clock, CLOCK_MS);
        if (watch != null) watch.start();
    }

    synchronized void stopRide() {
        riding = false;
        handler.removeCallbacks(clock);
        reset();
        if (watch != null) watch.stop();
    }

    private synchronized void onClock() {
        if (!riding) return;
        handler.postDelayed(clock, CLOCK_MS);
        if (current == null) return;
        NavStore.Decision decision = store.onClock(SystemClock.elapsedRealtime());
        if (decision == NavStore.Decision.IGNORE) return;
        DebugLog.add("  -> predicted " + current.direction + " " + store.predictedMeters() + "m => " + decision
                + " (" + store.describe() + ")");
        ManeuverParser.Maneuver predicted = new ManeuverParser.Maneuver(current.direction, current.saysNow,
                store.predictedMeters(), current.key, current.label, current.uTurnSide);
        playCue(predicted, decision == NavStore.Decision.IMMINENT);
    }

    /** A watch link opened by a test button outside a ride is closed after this long unused. */
    private static final long TEST_WATCH_IDLE_MS = 60000;
    private final Runnable stopTestWatch = this::stopTestWatch;

    private synchronized void stopTestWatch() {
        if (!riding && watch != null) watch.stop();
    }

    /** Plays the rhythm code; a U-turn "now" plays left-now or right-now by its arrow's side. */
    private void playCue(ManeuverParser.Maneuver m, boolean imminent) {
        ManeuverParser.Direction cue = RhythmCode.cueDirection(m.direction, imminent, m.uTurnSide);
        if (output != Output.WATCH) haptics.playTimings(RhythmCode.phoneTimings(cue, imminent), imminent);
        if (output == Output.PHONE || watch == null) return;
        playOnWatch(() -> watch.play(m.direction, cue, imminent, m.distanceMeters, m.label));
    }

    /** During a ride the link is up; outside one (test buttons) connect on demand, close when idle. */
    private void playOnWatch(Runnable cue) {
        if (riding) {
            cue.run();
        } else {
            watch.whenReady(cue);
            handler.removeCallbacks(stopTestWatch);
            handler.postDelayed(stopTestWatch, TEST_WATCH_IDLE_MS);
        }
    }

    /**
     * Quiz cue: one code on one device, whatever the Cues setting. The watch title is neutral so
     * the notification doesn't give the answer away.
     */
    synchronized void playQuiz(boolean rhythm, boolean onWatch, ManeuverParser.Direction direction, boolean imminent) {
        if (!onWatch) {
            haptics.playTimings(rhythm ? RhythmCode.phoneTimings(direction, imminent)
                    : Haptics.pattern(direction, imminent), imminent);
        } else if (watch != null) {
            long[] gaps = rhythm ? RhythmCode.watchGaps(direction, imminent) : WatchCues.countGaps(direction, imminent);
            playOnWatch(() -> watch.playPattern(gaps, "MorseNav quiz", "?"));
        }
    }

    /** Test button for the proposed rhythm code; see {@link RhythmCode}. */
    synchronized void testRhythm(ManeuverParser.Direction direction, boolean imminent) {
        DebugLog.add("TEST rhythm " + direction + (imminent ? " now" : " coming up"));
        if (output != Output.WATCH) haptics.playTimings(RhythmCode.phoneTimings(direction, imminent), imminent);
        if (output == Output.PHONE || watch == null) return;
        playOnWatch(() -> watch.play(direction, RhythmCode.cueDirection(direction, imminent, null), imminent,
                -1, "Rhythm code test"));
    }

    static synchronized NavEngine get(Context context) {
        if (instance == null) instance = new NavEngine(context.getApplicationContext());
        return instance;
    }

    Haptics haptics() {
        return haptics;
    }

    synchronized void onMapsNotification(String notificationKey, List<String> fields, Icon largeIcon,
                                         boolean rideActive) {
        DebugLog.add("MAPS " + fields);
        IconMatch icon = iconMatcher.match(largeIcon);
        String iconLine = icon != null ? icon.toString() : "no icon match";
        if (!iconLine.equals(lastIconMatch)) {
            lastIconMatch = iconLine;
            DebugLog.add("  icon: " + iconLine);
        }
        ManeuverParser.Maneuver m = ManeuverParser.parse(fields, icon != null ? icon.cue() : null);
        if (m != null && m.direction == ManeuverParser.Direction.U_TURN && icon != null && icon.accepted) {
            m = m.withUTurnSide(ManeuverIcons.uTurnSide(icon.name));
        }
        if (m == null) {
            DebugLog.add("  -> no maneuver");
            return;
        }
        if (!rideActive) {
            DebugLog.add("  -> " + m + " (ride not started, skipped)");
            return;
        }
        navNotificationKey = notificationKey;
        current = m;
        NavStore.Decision decision = store.onManeuver(m, SystemClock.elapsedRealtime());
        DebugLog.add("  -> " + m + " => " + decision
                + (decision != NavStore.Decision.IGNORE ? " (" + store.describe() + ")" : ""));
        if (decision != NavStore.Decision.IGNORE) {
            playCue(m, decision == NavStore.Decision.IMMINENT);
        }
    }

    synchronized void onMapsNotificationRemoved(String notificationKey) {
        if (notificationKey.equals(navNotificationKey)) {
            DebugLog.add("Navigation notification removed, store reset");
            reset();
        }
    }

    /** Test button: parse and play directly to the current output, bypassing the store and the ride check. */
    synchronized void test(List<String> fields, boolean imminent) {
        ManeuverParser.Maneuver m = ManeuverParser.parse(fields);
        DebugLog.add("TEST " + fields + " -> " + (m != null ? m : "no maneuver"));
        if (m != null) playCue(m, imminent);
    }

    synchronized void reset() {
        store.reset();
        navNotificationKey = null;
        lastIconMatch = null;
        current = null;
    }
}
