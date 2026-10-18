package com.morsenav;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Phase 2 experiments: talk to the boAt Wave Fury directly over the GATT link the boAt app already
 * holds open. Android lets several apps share one connection, so this opens a second client on it.
 * All steps are logged to {@link DebugLog}.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT first.
@RequiresApi(33)
final class WatchLink {

    static final String NAME_PREFIX = "WAVEFURY";

    // Nordic UART service; confirmed on the Wave Fury 2026-10-18.
    static final UUID SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final int KEY_VIBRATION = 0x020b;
    static final int KEY_FLAG_READ = 0x10;

    /** Gap between frames of a test sequence, long enough to watch the screen react. */
    private static final long STEP_MS = 4000;
    /** Our client stays this long after the last frame to catch replies. */
    private static final long LINGER_MS = 6000;

    private WatchLink() {}

    static void sendFindWatch(Context context) {
        send(context, "find watch", frames(SmaFrame.request(SmaFrame.KEY_FIND_WATCH, SmaFrame.KEY_FLAG_UPDATE, new byte[0])));
    }

    /** Reads the VIBRATION value without changing it, to learn what the key means. */
    static void readVibration(Context context) {
        send(context, "read vibration", frames(SmaFrame.request(KEY_VIBRATION, KEY_FLAG_READ, new byte[0])));
    }

    /** START left 200 m, GOING left 50 m, GOING right 150 m, END: to see whether the watch shows navigation. */
    static void naviTest(Context context, int key) {
        String road = "Test Rd";
        send(context, String.format(Locale.ROOT, "navi test key=0x%04x", key), frames(
                navi(key, SmaNavi.STATE_START, SmaNavi.TURN_LEFT, 200, road),
                navi(key, SmaNavi.STATE_GOING, SmaNavi.TURN_LEFT, 50, road),
                navi(key, SmaNavi.STATE_GOING, SmaNavi.TURN_RIGHT, 150, "MG Road"),
                navi(key, SmaNavi.STATE_END, SmaNavi.TURN_DEST, 0, "")));
    }

    /** Pushes one phone-style notification, to see whether the watch buzzes and shows the turn. */
    static void notificationTest(Context context, boolean v2) {
        byte[] body = SmaNotification.encode(v2, uniqueStamp(),
                "com.google.android.apps.maps", "MorseNav", "\u2190 LEFT 200 m \u00b7 MG Road");
        int key = v2 ? SmaNotification.KEY_NOTIFICATION2 : SmaNotification.KEY_NOTIFICATION;
        send(context, v2 ? "notification v2" : "notification v1",
                frames(SmaFrame.request(key, SmaFrame.KEY_FLAG_UPDATE, body)));
    }

    private static long lastStampMs;

    /**
     * Now, or one second after the previous stamp if that is later. The watch ignores a notification
     * whose (1 s resolution) timestamp matches the previous one, so each must differ.
     */
    static synchronized Calendar uniqueStamp() {
        long now = System.currentTimeMillis() / 1000 * 1000;
        lastStampMs = Math.max(now, lastStampMs + 1000);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(lastStampMs);
        return c;
    }

    private static byte[] navi(int key, int state, int turn, int distance, String road) {
        byte[] body = SmaNavi.encode(state, Calendar.getInstance(), turn,
                1500, 300, distance, 20, 0, road);
        return SmaFrame.request(key, SmaFrame.KEY_FLAG_UPDATE, body);
    }

    /** Frames sent {@link #STEP_MS} apart. */
    private static List<Step> frames(byte[]... frames) {
        List<Step> list = new ArrayList<>();
        for (int i = 0; i < frames.length; i++) list.add(new Step(frames[i], STEP_MS * i));
        return list;
    }

    /**
     * A frame and when to send it, in ms after notifications are enabled. The frame is built at send
     * time: the watch treats notifications with an identical timestamp as one, and only buzzes once.
     */
    private static final class Step {
        final Supplier<byte[]> frame;
        final long atMs;
        /** If set, logged as "MARK <mark>" when sent, so a sound recording can be lined up with it. */
        final String mark;
        /**
         * If >= 0, {@link #atMs} is ignored: the step is sent this long after the watch ACKs the
         * step before it, so the two can't reach the watch bunched together.
         */
        final long afterAckMs;

        Step(Supplier<byte[]> frame, long atMs, String mark, long afterAckMs) {
            this.frame = frame;
            this.atMs = atMs;
            this.mark = mark;
            this.afterAckMs = afterAckMs;
        }

        Step(Supplier<byte[]> frame, long atMs, String mark) {
            this(frame, atMs, mark, -1);
        }

        static Step afterAck(byte[] frame, long ms, String mark) {
            return new Step(() -> frame, 0, mark, ms);
        }

        Step(Supplier<byte[]> frame, long atMs) {
            this(frame, atMs, null);
        }

        Step(byte[] frame, long atMs) {
            this(() -> frame, atMs);
        }

        Step(byte[] frame, long atMs, String mark) {
            this(() -> frame, atMs, mark);
        }
    }

    // Vibration-related keys whose format we want to learn (com.szabh.smable3.BleKey).
    static final int KEY_VIBRATION_V2 = 0x0289;
    static final int KEY_RING_VIBRATION_SET = 0x0266;
    static final int KEY_VIBRATION_LIGHT_SET = 0x0637;

    /** Reads (never writes) each vibration-related key, so the replies show what the watch supports. */
    static void probeVibrationKeys(Context context) {
        int[] keys = {KEY_VIBRATION, KEY_VIBRATION_V2, KEY_RING_VIBRATION_SET, KEY_VIBRATION_LIGHT_SET};
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            steps.add(new Step(SmaFrame.request(keys[i], KEY_FLAG_READ, new byte[0]), 1500L * i));
        }
        send(context, "probe vibration keys (read only)", steps);
    }

    // LOVE_TAP (CONTROL 0x0608): a press-and-hold "tap" a friend sends; the watch should vibrate from
    // ACTION_DOWN until ACTION_UP, i.e. for a length we choose. Body (BleLoveTap): time int64 LE (ms),
    // id uint8 (a LOVE_TAP_USER entry), actionType uint8.
    static final int KEY_LOVE_TAP = 0x0608;
    static final int KEY_LOVE_TAP_USER = 0x023c;
    static final int LOVE_TAP_DOWN = 1;
    static final int LOVE_TAP_UP = 2;

    static byte[] loveTapFrame(int id, int action) {
        long t = System.currentTimeMillis();
        byte[] body = new byte[10];
        for (int i = 0; i < 8; i++) body[i] = (byte) (t >>> (8 * i));
        body[8] = (byte) id;
        body[9] = (byte) action;
        return SmaFrame.request(KEY_LOVE_TAP, SmaFrame.KEY_FLAG_UPDATE, body);
    }

    /** Reads the love-tap contacts, then holds the "tap" for 1 s, 0.2 s and 1.5 s. */
    static void loveTapTest(Context context) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(SmaFrame.request(KEY_LOVE_TAP_USER, KEY_FLAG_READ, new byte[0]), 0));
        long[][] holds = {{2000, 3000}, {6000, 6200}, {9000, 10500}};
        for (long[] h : holds) {
            steps.add(new Step(() -> loveTapFrame(0, LOVE_TAP_DOWN), h[0]));
            steps.add(new Step(() -> loveTapFrame(0, LOVE_TAP_UP), h[1]));
        }
        send(context, "love tap test (holds 1 s, 0.2 s, 1.5 s)", steps);
    }

    static final int KEY_FLAG_CREATE = 0x20;
    static final int KEY_FLAG_DELETE = 0x30;
    static final int KEY_FLAG_RESET = 0x40;
    static final int LOVE_TAP_CONTACT_ID = 1;

    /** BleLoveTapUser: id uint8, 3 zero bytes, name fixed 24 bytes UTF-8. */
    static byte[] loveTapUser(int id, String name) {
        byte[] body = new byte[28];
        body[0] = (byte) id;
        byte[] n = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(n, 0, body, 4, Math.min(n.length, 24));
        return body;
    }

    /** Creates love-tap contact 1, reads the list back, then holds taps to it for 1 s, 0.2 s and 1.5 s. */
    static void loveTapWithContactTest(Context context) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(SmaFrame.request(KEY_LOVE_TAP_USER, KEY_FLAG_CREATE,
                loveTapUser(LOVE_TAP_CONTACT_ID, "MorseNav")), 0));
        steps.add(new Step(SmaFrame.request(KEY_LOVE_TAP_USER, KEY_FLAG_READ, new byte[0]), 1500));
        long[][] holds = {{3500, 4500}, {7500, 7700}, {10500, 12000}};
        for (long[] h : holds) {
            steps.add(new Step(() -> loveTapFrame(LOVE_TAP_CONTACT_ID, LOVE_TAP_DOWN), h[0]));
            steps.add(new Step(() -> loveTapFrame(LOVE_TAP_CONTACT_ID, LOVE_TAP_UP), h[1]));
        }
        send(context, "love tap with contact", steps);
    }

    /** Undo: the list was empty before, so reset it (and delete id 1 in case reset isn't supported). */
    static void removeLoveTapContacts(Context context) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(SmaFrame.request(KEY_LOVE_TAP_USER, KEY_FLAG_RESET, new byte[0]), 0));
        steps.add(new Step(SmaFrame.request(KEY_LOVE_TAP_USER, KEY_FLAG_DELETE,
                new byte[]{(byte) LOVE_TAP_CONTACT_ID}), 1500));
        steps.add(new Step(SmaFrame.request(KEY_LOVE_TAP_USER, KEY_FLAG_READ, new byte[0]), 3000));
        send(context, "remove love-tap contacts", steps);
    }

    /**
     * Rings the watch as an incoming call and ends it, for 1 s, 0.3 s and 2.5 s: if the call alert
     * vibrates until the call ends, the vibration length is ours to choose.
     */
    static void fakeCallTest(Context context) {
        List<Step> steps = new ArrayList<>();
        long[][] calls = {{0, 1000}, {5000, 5300}, {9300, 11800}};
        for (long[] c : calls) {
            steps.add(new Step(() -> SmaFrame.request(SmaNotification.KEY_NOTIFICATION, SmaFrame.KEY_FLAG_UPDATE,
                    SmaNotification.incomingCall(uniqueStamp(), "MorseNav")), c[0]));
            steps.add(new Step(SmaFrame.request(SmaNotification.KEY_NOTIFICATION, KEY_FLAG_DELETE,
                    SmaNotification.callEnded()), c[1]));
        }
        send(context, "fake call test (1 s, 0.3 s, 2.5 s)", steps);
    }

    /** Values tried for VIBRATION (normally 1); the watch's own value is restored afterwards. */
    static final int[] VIBRATION_PROBE_VALUES = {0, 2, 3, 4, 5};
    static final int VIBRATION_DEFAULT = 1;

    /**
     * For each candidate VIBRATION value: write it, send one test tap titled with the value, so a
     * change in the tap's feel shows what the setting selects. Then writes 1 back and reads it.
     */
    static void vibrationModeProbe(Context context) {
        List<Step> steps = new ArrayList<>();
        long at = 0;
        for (int v : VIBRATION_PROBE_VALUES) {
            steps.add(new Step(SmaFrame.request(KEY_VIBRATION, SmaFrame.KEY_FLAG_UPDATE, new byte[]{(byte) v}), at));
            String title = "VIB " + v;
            steps.add(new Step(() -> notificationFrame(title, "Vibration mode test"), at + 1000));
            at += 5000;
        }
        steps.add(new Step(SmaFrame.request(KEY_VIBRATION, SmaFrame.KEY_FLAG_UPDATE,
                new byte[]{(byte) VIBRATION_DEFAULT}), at));
        steps.add(new Step(() -> notificationFrame("VIB 1 (restored)", "Vibration mode test"), at + 1000));
        steps.add(new Step(SmaFrame.request(KEY_VIBRATION, KEY_FLAG_READ, new byte[0]), at + 2500));
        send(context, "vibration mode probe 0/2/3/4/5 then restore 1", steps);
    }

    // SDK keys the boAt app never sends to this watch (com.szabh.smable3.BleKey).
    static final int KEY_FACTORY_TEST = 0x0609;
    static final int KEY_INCOMING_CALL_RING = 0x060c;
    static final int KEY_HANBAO_VIBRATION = 0x0521;
    /** BleFactoryTest body: FactoryTestType (MOTOR = 7), then an optional int8 "response". */
    static final int FACTORY_TEST_MOTOR = 7;

    /**
     * Tries every remaining way to make the motor run for a length we choose, one step every few
     * seconds, each logged as a MARK for tools/sound-scope.html:
     * overlapping notifications; FACTORY_TEST motor; HANBAO_VIBRATION (time, vibration, blood oxygen,
     * snore: an anti-snore band's buzz); INCOMING_CALL_RING; FIND_WATCH stopped after 300 ms with
     * DELETE, RESET or a second UPDATE; and last, FIND_WATCH left alone to show its natural length.
     */
    static void vibrationHunt(Context context) {
        int find = SmaFrame.KEY_FIND_WATCH;
        int update = SmaFrame.KEY_FLAG_UPDATE;
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(() -> notificationFrame("Hunt 1a", "overlap"), 0, "notif x2 @150ms"));
        steps.add(new Step(() -> notificationFrame("Hunt 1b", "overlap"), 150));
        steps.add(new Step(() -> notificationFrame("Hunt 2a", "overlap"), 5000, "notif x3 @100ms"));
        steps.add(new Step(() -> notificationFrame("Hunt 2b", "overlap"), 5100));
        steps.add(new Step(() -> notificationFrame("Hunt 2c", "overlap"), 5200));
        steps.add(new Step(SmaFrame.request(KEY_FACTORY_TEST, update, new byte[]{FACTORY_TEST_MOTOR}), 10000, "factory motor"));
        steps.add(new Step(SmaFrame.request(KEY_FACTORY_TEST, update, new byte[]{FACTORY_TEST_MOTOR, 1}), 14000, "factory motor on"));
        steps.add(new Step(SmaFrame.request(KEY_FACTORY_TEST, update, new byte[]{FACTORY_TEST_MOTOR, 0}), 15000, "factory motor off"));
        steps.add(new Step(SmaFrame.request(KEY_HANBAO_VIBRATION, update, new byte[]{2, 1, 0, 1}), 19000, "hanbao t2 v1"));
        steps.add(new Step(SmaFrame.request(KEY_HANBAO_VIBRATION, update, new byte[]{1, 3, 0, 1}), 24000, "hanbao t1 v3"));
        steps.add(new Step(SmaFrame.request(KEY_INCOMING_CALL_RING, update, new byte[0]), 29000, "call ring"));
        steps.add(new Step(SmaFrame.request(find, update, new byte[0]), 34000, "find, DELETE @300"));
        steps.add(new Step(SmaFrame.request(find, KEY_FLAG_DELETE, new byte[0]), 34300));
        steps.add(new Step(SmaFrame.request(find, update, new byte[0]), 40000, "find, RESET @300"));
        steps.add(new Step(SmaFrame.request(find, KEY_FLAG_RESET, new byte[0]), 40300));
        steps.add(new Step(SmaFrame.request(find, update, new byte[0]), 46000, "find, UPDATE @300"));
        steps.add(new Step(SmaFrame.request(find, update, new byte[0]), 46300));
        steps.add(new Step(SmaFrame.request(find, update, new byte[0]), 52000, "find alone"));
        steps.add(new Step(SmaFrame.request(find, update, new byte[0]), 62000, "end"));
        send(context, "vibration hunt", steps);
    }

    /** {count, spacing ms} of each chain in {@link #notificationChainTest}. */
    static final int[][] CHAINS = {{1, 0}, {2, 150}, {4, 150}, {8, 150}, {4, 300}, {4, 450}, {1, 0}};
    private static final long CHAIN_GAP_MS = 5000;

    /**
     * Notifications sent close enough together seem to restart the watch's buzz instead of adding
     * taps. Each chain here is marked for tools/sound-scope.html, so the recording shows whether the
     * buzz lasts "last notification + a fixed tail" (a dash of any length) and when a gap opens.
     */
    static void notificationChainTest(Context context) {
        List<Step> steps = new ArrayList<>();
        long at = 0;
        for (int[] chain : CHAINS) {
            int count = chain[0], spacing = chain[1];
            for (int i = 0; i < count; i++) {
                String title = "Chain " + count + "x" + spacing + " #" + (i + 1);
                steps.add(new Step(() -> notificationFrame(title, "chain test"), at + (long) i * spacing,
                        i == 0 ? "chain " + count + " @" + spacing + "ms" : null));
            }
            at += CHAIN_GAP_MS;
        }
        steps.add(new Step(SmaFrame.request(KEY_VIBRATION, KEY_FLAG_READ, new byte[0]), at, "end"));
        send(context, "notification chain test", steps);
    }

    /** How long after a notification each attempt to stop its buzz is sent. */
    static final long CUT_AFTER_MS = 150;

    /**
     * One notification's buzz lasts ~450 ms. Tries to stop it {@link #CUT_AFTER_MS} in, a different
     * way per step: DELETE with the same body, DELETE with an empty body, the boAt app's call-ended
     * DELETE, RESET, and VIBRATION = 0 (restored to 1 a second later). Plain notifications open and
     * close the run as the uncut reference. Each step is marked for tools/sound-scope.html.
     */
    static void buzzCutTest(Context context) {
        int n = SmaNotification.KEY_NOTIFICATION;
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(() -> notificationFrame("Cut 0", "uncut"), 0, "uncut"));

        byte[][] sameBody = new byte[1][];
        steps.add(new Step(() -> {
            sameBody[0] = SmaNotification.encode(false, uniqueStamp(), "com.google.android.apps.maps", "Cut 1", "same body");
            return SmaFrame.request(n, SmaFrame.KEY_FLAG_UPDATE, sameBody[0]);
        }, 4000, "DELETE same body"));
        steps.add(new Step(() -> SmaFrame.request(n, KEY_FLAG_DELETE, sameBody[0]), 4000 + CUT_AFTER_MS));

        steps.add(new Step(() -> notificationFrame("Cut 2", "empty"), 8000, "DELETE empty"));
        steps.add(new Step(SmaFrame.request(n, KEY_FLAG_DELETE, new byte[0]), 8000 + CUT_AFTER_MS));

        steps.add(new Step(() -> notificationFrame("Cut 3", "call ended"), 12000, "DELETE call-ended"));
        steps.add(new Step(SmaFrame.request(n, KEY_FLAG_DELETE, SmaNotification.callEnded()), 12000 + CUT_AFTER_MS));

        steps.add(new Step(() -> notificationFrame("Cut 4", "reset"), 16000, "RESET"));
        steps.add(new Step(SmaFrame.request(n, KEY_FLAG_RESET, new byte[0]), 16000 + CUT_AFTER_MS));

        steps.add(new Step(() -> notificationFrame("Cut 5", "vibration 0"), 20000, "VIBRATION 0"));
        steps.add(new Step(SmaFrame.request(KEY_VIBRATION, SmaFrame.KEY_FLAG_UPDATE, new byte[]{0}), 20000 + CUT_AFTER_MS));
        steps.add(new Step(SmaFrame.request(KEY_VIBRATION, SmaFrame.KEY_FLAG_UPDATE,
                new byte[]{(byte) VIBRATION_DEFAULT}), 21000));

        steps.add(new Step(() -> notificationFrame("Cut 6", "uncut"), 24000, "uncut again"));
        steps.add(new Step(SmaFrame.request(KEY_VIBRATION, KEY_FLAG_READ, new byte[0]), 28000, "end"));
        send(context, "buzz cut test", steps);
    }

    /** Delays after the notification's ACK at which {@link #vibrationOffCutTest} turns vibration off. */
    static final long[] OFF_AFTER_ACK_MS = {0, 100, 200, 300};

    /**
     * VIBRATION = 0 sent with a notification suppressed its buzz entirely. Here it is sent a set time
     * after the watch ACKs the notification, i.e. while the motor is already running, to see whether
     * the buzz stops there. VIBRATION goes back to 1 a second later; plain notifications open and close
     * the run as the uncut reference.
     */
    static void vibrationOffCutTest(Context context) {
        byte[] off = SmaFrame.request(KEY_VIBRATION, SmaFrame.KEY_FLAG_UPDATE, new byte[]{0});
        byte[] on = SmaFrame.request(KEY_VIBRATION, SmaFrame.KEY_FLAG_UPDATE, new byte[]{(byte) VIBRATION_DEFAULT});
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(() -> notificationFrame("Off 0", "uncut"), 0, "uncut"));
        long at = 4000;
        for (long delay : OFF_AFTER_ACK_MS) {
            String title = "Off @" + delay;
            steps.add(new Step(() -> notificationFrame(title, "vibration off"), at, "notif, off @ack+" + delay));
            steps.add(Step.afterAck(off, delay, "off @ack+" + delay));
            steps.add(new Step(on, at + 1500));
            at += 4000;
        }
        steps.add(new Step(() -> notificationFrame("Off end", "uncut"), at, "uncut again"));
        steps.add(new Step(SmaFrame.request(KEY_VIBRATION, KEY_FLAG_READ, new byte[0]), at + 3000, "end"));
        send(context, "vibration-off cut test", steps);
    }

    /** Candidate turn symbols, numbered so the rider can report which ones render. */
    static final String[] SYMBOLS = {
            "\u2190", "\u2192", "\u2191", "\u21b6", "\u21b7", "\u27f2", "\u25c0", "\u25b6",
            "\u2b05", "\u27a1", "\u00ab", "\u00bb", "\u21e6", "\u21e8", "\u21a9", "\u21aa",
            "\u2b05\ufe0f", "\u27a1\ufe0f", "\u21a9\ufe0f", "<<", ">>", "<-", "->",
    };

    static String symbolList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SYMBOLS.length; i++) {
            if (i > 0) sb.append("  ");
            sb.append(i + 1).append(':').append(SYMBOLS[i]);
        }
        return sb.toString();
    }

    /** Shows the candidate symbols in a notification's text, then arrows in a title. */
    static void symbolTest(Context context) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(() -> notificationFrame("Symbols 1-" + SYMBOLS.length, symbolList()), 0));
        steps.add(new Step(() -> notificationFrame("\u2190 L  \u2192 R  \u21b6 U", "Title test: arrows above?"), STEP_MS));
        send(context, "symbol test", steps);
    }

    private static byte[] notificationFrame(String title, String text) {
        return SmaFrame.request(SmaNotification.KEY_NOTIFICATION, SmaFrame.KEY_FLAG_UPDATE,
                SmaNotification.encode(false, uniqueStamp(), "com.google.android.apps.maps", title, text));
    }

    /** Spacings between ticks in a burst test; each group is three notifications. */
    static final long[] BURST_SPACINGS_MS = {500, 700, 1000};
    private static final long BURST_GROUP_GAP_MS = 5000;

    /**
     * Three notifications per spacing, groups 5 s apart, to find how close together the watch still
     * gives separate ticks. Each is stamped when sent; see {@link Step}.
     */
    static void tickBurstTest(Context context) {
        List<Step> steps = new ArrayList<>();
        long at = 0;
        for (long spacing : BURST_SPACINGS_MS) {
            for (int i = 0; i < 3; i++) {
                // Distinct text per notification, in case the watch suppresses duplicates.
                String text = "Tick " + (i + 1) + "/3 @" + spacing + " ms";
                steps.add(new Step(() -> SmaFrame.request(SmaNotification.KEY_NOTIFICATION, SmaFrame.KEY_FLAG_UPDATE,
                        SmaNotification.encode(false, uniqueStamp(),
                                "com.google.android.apps.maps", "MorseNav", text)), at));
                at += spacing;
            }
            at += BURST_GROUP_GAP_MS;
        }
        send(context, "tick burst 500/700/1000 ms", steps);
    }

    /** The watch, if the boAt app (or anything) currently has it connected over GATT. */
    static BluetoothDevice findConnectedWatch(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        for (BluetoothDevice d : manager.getConnectedDevices(BluetoothProfile.GATT)) {
            String name = d.getName();
            if (name != null && name.toUpperCase(Locale.ROOT).startsWith(NAME_PREFIX)) return d;
        }
        return null;
    }

    private static void send(Context context, String label, List<Step> frames) {
        BluetoothDevice watch = findConnectedWatch(context);
        if (watch == null) {
            DebugLog.add("WATCH no connected " + NAME_PREFIX + "* device; is the boAt app connected to it?");
            return;
        }
        DebugLog.add("WATCH [" + label + "] connecting to " + watch.getName() + " " + watch.getAddress());
        watch.connectGatt(context.getApplicationContext(), false, new Session(label, frames),
                BluetoothDevice.TRANSPORT_LE);
    }

    private static final class Session extends BluetoothGattCallback {
        private final String label;
        private final List<Step> frames;
        private final Handler handler = new Handler(Looper.getMainLooper());

        Session(String label, List<Step> frames) {
            this.label = label;
            this.frames = frames;
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            DebugLog.add("WATCH connection state=" + newState + " status=" + status);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                handler.postDelayed(() -> {
                    DebugLog.add("WATCH [" + label + "] done, closing our client");
                    gatt.disconnect();
                    gatt.close();
                }, frames.get(frames.size() - 1).atMs + LINGER_MS);
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService service = gatt.getService(SERVICE);
            if (service == null || service.getCharacteristic(WRITE) == null
                    || service.getCharacteristic(NOTIFY) == null) {
                DebugLog.add("WATCH Nordic UART service/characteristics not found; not sending");
                return;
            }
            // Short connection interval while we're on: frames sent ms apart otherwise reach the
            // watch bunched together (seen 2026-10-18: three frames 100 ms apart arrived 1.1 s late, together).
            boolean fast = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            DebugLog.add("WATCH high connection priority requested=" + fast);
            // Navigation frames exceed the default 20-byte write; ask for the large MTU the watch supports.
            gatt.requestMtu(517);
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            DebugLog.add("WATCH mtu=" + mtu + " status=" + status);
            BluetoothGattCharacteristic notify = gatt.getService(SERVICE).getCharacteristic(NOTIFY);
            gatt.setCharacteristicNotification(notify, true);
            BluetoothGattDescriptor cccd = notify.getDescriptor(CCCD);
            if (cccd != null) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                sendAll(gatt);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            sendAll(gatt);
        }

        /** Index and frame of the last step sent; main thread only. */
        private int lastSent = -1;
        private byte[] lastFrame;

        private void sendAll(BluetoothGatt gatt) {
            for (int i = 0; i < frames.size(); i++) {
                if (frames.get(i).afterAckMs >= 0) continue;  // sent from onCharacteristicChanged
                int index = i;
                handler.postDelayed(() -> sendStep(gatt, index), frames.get(i).atMs);
            }
        }

        private void sendStep(BluetoothGatt gatt, int index) {
            BluetoothGattCharacteristic write = gatt.getService(SERVICE).getCharacteristic(WRITE);
            Step s = frames.get(index);
            if (s.mark != null) DebugLog.add("MARK " + s.mark);
            byte[] frame = s.frame.get();
            int result = gatt.writeCharacteristic(write, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            DebugLog.add("WATCH [" + label + "] step " + (index + 1) + "/" + frames.size()
                    + " sent " + SmaFrame.hex(frame) + " result=" + result);
            lastSent = index;
            lastFrame = frame;
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            String info = SmaFrame.isValid(value)
                    ? String.format(Locale.ROOT, "key=0x%04x%s", SmaFrame.key(value), SmaFrame.isAck(value) ? " ACK" : "")
                    : "not a complete SMA frame";
            DebugLog.add("WATCH received " + SmaFrame.hex(value) + " (" + info + ")");
            if (!SmaFrame.isValid(value) || !SmaFrame.isAck(value)) return;
            handler.post(() -> {
                int next = lastSent + 1;
                if (lastFrame == null || next >= frames.size() || frames.get(next).afterAckMs < 0
                        || SmaFrame.key(value) != SmaFrame.key(lastFrame)) return;
                lastFrame = null;  // one ACK triggers the next step once
                handler.postDelayed(() -> sendStep(gatt, next), frames.get(next).afterAckMs);
            });
        }
    }
}
