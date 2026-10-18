package com.morsenav;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/** Plays the turn vocabulary: 2 pulses left, 3 right, 4 U-turn; slow+soft advance, fast+strong imminent. */
final class Haptics {

    private static final int ADVANCE_AMPLITUDE = 120;
    private static final int IMMINENT_AMPLITUDE = 255;

    private static final long[] LEFT_ADVANCE = {0, 200, 700, 200};
    private static final long[] RIGHT_ADVANCE = {0, 200, 700, 200, 700, 200};
    private static final long[] U_TURN_ADVANCE = {0, 200, 700, 200, 700, 200, 700, 200};

    private static final long[] LEFT_IMMINENT = {0, 200, 200, 200};
    private static final long[] RIGHT_IMMINENT = {0, 200, 200, 200, 200, 200};
    private static final long[] U_TURN_IMMINENT = {0, 150, 150, 150, 150, 150, 150, 150};

    private final Vibrator vibrator;

    Haptics(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = context.getSystemService(VibratorManager.class);
            vibrator = manager != null ? manager.getDefaultVibrator() : null;
        } else {
            vibrator = context.getSystemService(Vibrator.class);
        }
    }

    boolean hasVibrator() {
        return vibrator != null && vibrator.hasVibrator();
    }

    boolean hasAmplitudeControl() {
        return vibrator != null && vibrator.hasAmplitudeControl();
    }

    static long[] pattern(ManeuverParser.Direction direction, boolean imminent) {
        switch (direction) {
            case LEFT: return imminent ? LEFT_IMMINENT : LEFT_ADVANCE;
            case RIGHT: return imminent ? RIGHT_IMMINENT : RIGHT_ADVANCE;
            default: return imminent ? U_TURN_IMMINENT : U_TURN_ADVANCE;
        }
    }

    /** Plays a waveform (off, on, off, on, ... in ms), soft or strong. */
    void playTimings(long[] timings, boolean imminent) {
        if (vibrator == null) return;

        VibrationEffect effect;
        if (vibrator.hasAmplitudeControl()) {
            int amplitude = imminent ? IMMINENT_AMPLITUDE : ADVANCE_AMPLITUDE;
            int[] amplitudes = new int[timings.length];
            for (int i = 0; i < timings.length; i++) {
                amplitudes[i] = (i % 2 != 0) ? amplitude : 0;
            }
            effect = VibrationEffect.createWaveform(timings, amplitudes, -1);
        } else {
            effect = VibrationEffect.createWaveform(timings, -1);
        }

        // A newer cue always replaces one still playing. Alarm usage keeps the cue from being
        // dropped while the app is in the background or the phone is on silent.
        vibrator.cancel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        } else {
            vibrator.vibrate(effect, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build());
        }
    }
}
