package com.morsenav;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/** In-memory ring buffer of recent events, shown in the app so Maps' real wording can be captured. */
final class DebugLog {

    /** Also mirrored to logcat: {@code adb logcat -s MorseNav}. */
    private static final String TAG = "MorseNav";
    private static final int MAX_LINES = 2000;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    private DebugLog() {}

    static synchronized void add(String message) {
        Log.i(TAG, message);
        if (LINES.size() >= MAX_LINES) LINES.removeFirst();
        LINES.addLast(TIME.format(new Date()) + "  " + message);
    }

    /** Newest entries first. */
    static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = LINES.descendingIterator();
        while (it.hasNext()) sb.append(it.next()).append('\n');
        return sb.toString();
    }

    static synchronized void clear() {
        LINES.clear();
    }
}
