package com.morsenav;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

/**
 * Body of the SMA navigation messages (NAVI_INFO 0x024d, GOOGLE_NAVI_INFO 0x0720), as encoded by
 * com.szabh.smable3.entity.BleNaviInfo / BleGoogleNaviInfo. Multi-byte values are big-endian.
 *
 * <pre>
 * state(1) time(6: year-2000, month, day, hour, minute, second) turnType(1)
 * remainDistance(4) remainTime(4) distance(4) speed(1) mode(1) nameLength(2) name(UTF-8, <= 232)
 * </pre>
 */
final class SmaNavi {

    static final int KEY_NAVI_INFO = 0x024d;
    static final int KEY_GOOGLE_NAVI_INFO = 0x0720;

    static final int STATE_START = 0;
    static final int STATE_GOING = 1;
    static final int STATE_END = 4;

    // A few of BleNaviInfo's TURN_* values.
    static final int TURN_BACK = 1;
    static final int TURN_DEST = 12;
    static final int TURN_LEFT = 20;
    static final int TURN_RIGHT = 35;

    static final int NAME_MAX_LENGTH = 232;

    private SmaNavi() {}

    static byte[] encode(int state, Calendar time, int turnType, int remainDistance, int remainTime,
                         int distance, int speed, int mode, String roadName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(state);
        out.write(time.get(Calendar.YEAR) - 2000);
        out.write(time.get(Calendar.MONTH) + 1);
        out.write(time.get(Calendar.DAY_OF_MONTH));
        out.write(time.get(Calendar.HOUR_OF_DAY));
        out.write(time.get(Calendar.MINUTE));
        out.write(time.get(Calendar.SECOND));
        out.write(turnType);
        int32(out, remainDistance);
        int32(out, remainTime);
        int32(out, distance);
        out.write(speed);
        out.write(mode);
        byte[] name = truncateUtf8(roadName, NAME_MAX_LENGTH);
        out.write(name.length >> 8);
        out.write(name.length);
        out.write(name, 0, name.length);
        return out.toByteArray();
    }

    private static void int32(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }

    /** UTF-8 bytes of {@code s}, cut to at most {@code max} bytes without splitting a character. */
    static byte[] truncateUtf8(String s, int max) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= max) return bytes;
        int end = max;
        while (end > 0 && (bytes[end] & 0xc0) == 0x80) end--;
        byte[] cut = new byte[end];
        System.arraycopy(bytes, 0, cut, 0, end);
        return cut;
    }
}
