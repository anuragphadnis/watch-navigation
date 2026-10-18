package com.morsenav;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

/**
 * Body of the SMA phone-notification messages, as encoded by com.szabh.smable3.entity.BleNotification
 * (NOTIFICATION 0x0401) and BleNotification2 (NOTIFICATION2 0x040a). The watch buzzes and shows
 * these the way it does forwarded phone notifications.
 *
 * <pre>
 * category(1) time(6) package(32, zero-padded) title(<= 31 bytes, zero-padded to 32)
 * [v2 only: phone(32, zero-padded)] content(UTF-8, <= 250 bytes, cut with "…")
 * </pre>
 */
final class SmaNotification {

    static final int KEY_NOTIFICATION = 0x0401;
    static final int KEY_NOTIFICATION2 = 0x040a;

    static final int CATEGORY_INCOMING_CALL = 1;
    static final int CATEGORY_MESSAGE = 127;
    static final int FIELD_LENGTH = 32;
    static final int CONTENT_MAX_LENGTH = 250;

    private SmaNotification() {}

    static byte[] encode(boolean v2, Calendar time, String packageName, String title, String content) {
        return encode(v2, CATEGORY_MESSAGE, time, packageName, title, content);
    }

    static byte[] encode(boolean v2, int category, Calendar time, String packageName, String title, String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(category);
        out.write(time.get(Calendar.YEAR) - 2000);
        out.write(time.get(Calendar.MONTH) + 1);
        out.write(time.get(Calendar.DAY_OF_MONTH));
        out.write(time.get(Calendar.HOUR_OF_DAY));
        out.write(time.get(Calendar.MINUTE));
        out.write(time.get(Calendar.SECOND));
        fixed(out, packageName.getBytes(StandardCharsets.UTF_8));
        fixed(out, SmaNavi.truncateUtf8(title, FIELD_LENGTH - 1));
        if (v2) fixed(out, new byte[0]);
        byte[] body = limitWithEllipsis(content, CONTENT_MAX_LENGTH);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    /**
     * As the boAt app rings the watch for a call: category 1, no package, caller as the title,
     * "Incoming call" as the text, flag UPDATE.
     */
    static byte[] incomingCall(Calendar time, String caller) {
        return encode(false, CATEGORY_INCOMING_CALL, time, "", caller, "Incoming call");
    }

    /** As the boAt app ends it: category 1, time 0 (epoch, local time), empty fields, flag DELETE. */
    static byte[] callEnded() {
        Calendar epoch = Calendar.getInstance();
        epoch.setTimeInMillis(0);
        return encode(false, CATEGORY_INCOMING_CALL, epoch, "", "", "");
    }

    /** Writes {@code bytes} cut or zero-padded to exactly {@link #FIELD_LENGTH} bytes. */
    private static void fixed(ByteArrayOutputStream out, byte[] bytes) {
        int n = Math.min(bytes.length, FIELD_LENGTH);
        out.write(bytes, 0, n);
        for (int i = n; i < FIELD_LENGTH; i++) out.write(0);
    }

    /** Like the SDK: if too long, drop characters until text + "…" fits. */
    static byte[] limitWithEllipsis(String text, int limit) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) return bytes;
        for (int length = text.length() - 1; length >= 0; length--) {
            bytes = (text.substring(0, length) + '…').getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= limit) return bytes;
        }
        return new byte[0];
    }
}
