package com.morsenav;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.RequiresApi;

import java.util.Locale;

/**
 * Turn cues on the boAt Wave Fury during a ride. Holds a GATT client open on the link the boAt app
 * keeps to the watch, and plays each cue as a burst of SMA notifications in the {@link RhythmCode}:
 * one notification buzzes once (a dot), a chain of them 150 ms apart buzzes continuously (a dash).
 * The old count code (2/3/4 ticks) remains for the quiz. Each notification also shows the turn as text.
 *
 * <p>All state is touched on the main thread; GATT callbacks are posted there.
 */
@SuppressLint("MissingPermission") // start() checks BLUETOOTH_CONNECT.
@RequiresApi(33)
final class WatchCues {

    static final long ADVANCE_SPACING_MS = 1000;
    static final long IMMINENT_SPACING_MS = 500;
    /**
     * The watch takes a variable 0.1-1.3 s per notification and vibrates when it handles it (its ACK
     * time), so ticks are paced from each ACK, not from a fixed schedule. This is its typical ACK
     * latency when idle, subtracted so ACK-to-ACK gaps match the spacing.
     */
    static final long ACK_LATENCY_MS = 60;
    /** Send the next tick anyway if an ACK hasn't arrived this long after its intended time. */
    static final long ACK_TIMEOUT_MS = 2000;
    private static final long RETRY_MS = 10000;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Token for scheduled ticks, so a new cue can cancel the rest of the previous one. */
    private final Object ticks = new Object();

    private boolean running;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic write;
    private boolean ready;
    private boolean loggedMissing;

    WatchCues(Context context) {
        this.context = context.getApplicationContext();
    }

    void start() {
        if (running) return;
        running = true;
        loggedMissing = false;
        connect();
    }

    void stop() {
        running = false;
        pendingCue = null;
        handler.removeCallbacksAndMessages(null);
        closeGatt();
        DebugLog.add("WATCH cues stopped");
    }

    boolean isReady() {
        return ready;
    }

    static int ticks(ManeuverParser.Direction direction) {
        switch (direction) {
            case LEFT: return 2;
            case RIGHT: return 3;
            default: return 4;
        }
    }

    /**
     * Title shown on the watch. Its font is ASCII-only and only "<<"/">>" work as arrows, so the
     * arrow goes on the side of the turn: "<< LEFT 220 m", "RIGHT NOW >>", "<< U-TURN NOW".
     */
    static String title(ManeuverParser.Direction direction, boolean imminent, int distanceMeters) {
        String what = imminent ? "NOW" : distanceMeters >= 0 ? distanceMeters + " m" : "";
        switch (direction) {
            case LEFT: return ("<< LEFT " + what).trim();
            case RIGHT: return ("RIGHT " + what).trim() + " >>";
            default: return ("<< U-TURN " + what).trim();
        }
    }

    /** Drops characters the watch's ASCII-only font can't draw, e.g. regional scripts or "\u00b7". */
    static String ascii(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c >= 0x20 && c < 0x7f ? c : ' ');
        }
        return sb.toString().replaceAll(" {2,}", " ").trim();
    }

    /** A cue waiting for the link to come up; only test buttons use this. */
    private Runnable pendingCue;

    /** Runs {@code cue} now if connected, otherwise connects and runs it once ready. */
    void whenReady(Runnable cue) {
        if (ready) {
            cue.run();
            return;
        }
        pendingCue = cue;
        DebugLog.add("WATCH connecting for test cue");
        start();
    }

    boolean isRunning() {
        return running;
    }

    /**
     * Plays {@code cue}'s rhythm code (see {@link RhythmCode}); the title names {@code direction},
     * so a U-turn played as right-now still reads "U-TURN NOW".
     */
    void play(ManeuverParser.Direction direction, ManeuverParser.Direction cue, boolean imminent,
              int distanceMeters, String road) {
        if (!ready) {
            DebugLog.add("WATCH not ready, cue skipped");
            return;
        }
        playPattern(RhythmCode.watchGaps(cue, imminent), title(direction, imminent, distanceMeters), road);
    }

    /** The count code: 2/3/4 ticks, 1000 ms apart upcoming, 500 ms when imminent. */
    static long[] countGaps(ManeuverParser.Direction direction, boolean imminent) {
        long[] gaps = new long[ticks(direction) - 1];
        java.util.Arrays.fill(gaps, imminent ? IMMINENT_SPACING_MS : ADVANCE_SPACING_MS);
        return gaps;
    }

    /** Plays ticks separated by {@code gapsMs} (ticks = gaps + 1), each gap measured ACK to ACK. */
    void playPattern(long[] gapsMs, String title, String road) {
        if (!ready) {
            DebugLog.add("WATCH not ready, cue skipped");
            return;
        }
        handler.removeCallbacksAndMessages(ticks);
        cueStartMs = android.os.SystemClock.uptimeMillis();
        cueTitle = title;
        cueRoad = road;
        cueGapsMs = gapsMs;
        ticksSent = 0;
        DebugLog.add("WATCH cue " + title + " (" + (gapsMs.length + 1) + " ticks, gaps "
                + java.util.Arrays.toString(gapsMs) + " ms)");
        sendNextTick();
    }

    // The cue being played; ticks after the first are sent when the previous one is ACKed.
    private long cueStartMs;
    private String cueTitle;
    private String cueRoad;
    private long[] cueGapsMs = new long[0];
    private int ticksSent;

    private void sendNextTick() {
        handler.removeCallbacksAndMessages(ticks);
        int total = cueGapsMs.length + 1;
        if (!ready || ticksSent >= total) return;
        ticksSent++;
        sendNotification(cueTitle, cueRoad, ticksSent);
        if (ticksSent < total) {
            // Fallback if the ACK never comes: the intended gap plus the ACK timeout.
            handler.postAtTime(this::sendNextTick, ticks,
                    android.os.SystemClock.uptimeMillis() + cueGapsMs[ticksSent - 1] + ACK_TIMEOUT_MS);
        }
    }

    private void onTickAcked() {
        DebugLog.add("WATCH tick acked at +" + (android.os.SystemClock.uptimeMillis() - cueStartMs) + " ms");
        if (ticksSent == 0 || ticksSent > cueGapsMs.length) return;
        handler.removeCallbacksAndMessages(ticks);
        handler.postAtTime(this::sendNextTick, ticks,
                android.os.SystemClock.uptimeMillis() + Math.max(0, cueGapsMs[ticksSent - 1] - ACK_LATENCY_MS));
    }

    private void sendNotification(String title, String road, int tick) {
        if (!ready) return;
        DebugLog.add("WATCH tick " + tick + " sent at +" + (android.os.SystemClock.uptimeMillis() - cueStartMs) + " ms");
        String text = ascii(road);
        byte[] body = SmaNotification.encode(false, WatchLink.uniqueStamp(),
                MorseNavListenerService.MAPS_PACKAGE, title, text.isEmpty() ? title : text);
        byte[] frame = SmaFrame.request(SmaNotification.KEY_NOTIFICATION, SmaFrame.KEY_FLAG_UPDATE, body);
        int result = gatt.writeCharacteristic(write, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        if (result != BluetoothStatusCodes.SUCCESS) DebugLog.add("WATCH write failed: " + result);
    }

    private void connect() {
        if (!running) return;
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            DebugLog.add("WATCH Nearby devices permission not granted; watch cues off");
            return;
        }
        BluetoothDevice watch = WatchLink.findConnectedWatch(context);
        if (watch == null) {
            if (!loggedMissing) {
                DebugLog.add("WATCH not connected to the boAt app; retrying every " + RETRY_MS / 1000 + " s");
                loggedMissing = true;
            }
            handler.postDelayed(this::connect, RETRY_MS);
            return;
        }
        DebugLog.add("WATCH connecting to " + watch.getName());
        gatt = watch.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
    }

    private void closeGatt() {
        ready = false;
        write = null;
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
    }

    private void retryLater() {
        closeGatt();
        if (running) handler.postDelayed(this::connect, RETRY_MS);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            handler.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    DebugLog.add("WATCH disconnected (status " + status + ")");
                    retryLater();
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            handler.post(() -> {
                if (g != gatt) return;
                BluetoothGattService service = g.getService(WatchLink.SERVICE);
                if (service == null || service.getCharacteristic(WatchLink.WRITE) == null) {
                    DebugLog.add("WATCH Nordic UART service missing; retrying");
                    retryLater();
                    return;
                }
                write = service.getCharacteristic(WatchLink.WRITE);
                // Without a short connection interval, notifications 150 ms apart (a rhythm-code
                // dash) reach the watch bunched together and the dash comes out as one dot.
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                g.requestMtu(517);
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            handler.post(() -> {
                if (g != gatt) return;
                BluetoothGattCharacteristic notify = g.getService(WatchLink.SERVICE).getCharacteristic(WatchLink.NOTIFY);
                BluetoothGattDescriptor cccd = notify != null ? notify.getDescriptor(WatchLink.CCCD) : null;
                if (cccd == null) {
                    markReady(mtu);
                    return;
                }
                g.setCharacteristicNotification(notify, true);
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                pendingMtu = mtu;
            });
        }

        private int pendingMtu;

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (SmaFrame.isValid(value) && SmaFrame.isAck(value) && SmaFrame.key(value) == SmaNotification.KEY_NOTIFICATION) {
                handler.post(() -> {
                    if (g == gatt) onTickAcked();
                });
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            handler.post(() -> {
                if (g == gatt) markReady(pendingMtu);
            });
        }
    };

    private void markReady(int mtu) {
        ready = true;
        DebugLog.add(String.format(Locale.ROOT, "WATCH ready (mtu %d)", mtu));
        Runnable cue = pendingCue;
        pendingCue = null;
        if (cue != null) cue.run();
    }
}
