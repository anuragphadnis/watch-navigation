package com.morsenav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;

import org.junit.Test;

public class SmaNotificationTest {

    private static final Calendar TIME = new GregorianCalendar(2026, Calendar.OCTOBER, 18, 15, 33, 10);

    @Test
    public void layoutV1() {
        byte[] b = SmaNotification.encode(false, TIME, "com.morsenav", "MorseNav", "LEFT 200 m");
        assertEquals(1 + 6 + 32 + 32 + 10, b.length);
        assertEquals(127, b[0] & 0xff);
        assertEquals("1a0a120f210a", SmaFrame.hex(Arrays.copyOfRange(b, 1, 7)));
        assertEquals("com.morsenav", new String(b, 7, 12, StandardCharsets.UTF_8));
        assertEquals(0, b[7 + 12]);
        assertEquals("MorseNav", new String(b, 39, 8, StandardCharsets.UTF_8));
        assertEquals("LEFT 200 m", new String(b, 71, 10, StandardCharsets.UTF_8));
    }

    @Test
    public void v2HasEmptyPhoneField() {
        byte[] b = SmaNotification.encode(true, TIME, "com.morsenav", "MorseNav", "LEFT 200 m");
        assertEquals(1 + 6 + 32 + 32 + 32 + 10, b.length);
        for (int i = 71; i < 103; i++) assertEquals(0, b[i]);
        assertEquals("LEFT 200 m", new String(b, 103, 10, StandardCharsets.UTF_8));
    }

    @Test
    public void titleKeepsATerminator() {
        byte[] b = SmaNotification.encode(false, TIME, "", "0123456789012345678901234567890123456789", "x");
        assertEquals(0, b[39 + 31]);
        assertEquals('0', b[39]);
    }

    @Test
    public void longContentGetsEllipsis() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append('a');
        byte[] c = SmaNotification.limitWithEllipsis(sb.toString(), 250);
        assertTrue(c.length <= 250);
        assertTrue(new String(c, StandardCharsets.UTF_8).endsWith("…"));
    }

    @Test
    public void framedNotificationValidates() {
        byte[] frame = SmaFrame.request(SmaNotification.KEY_NOTIFICATION, SmaFrame.KEY_FLAG_UPDATE,
                SmaNotification.encode(false, TIME, "com.morsenav", "MorseNav", "LEFT 200 m"));
        assertTrue(SmaFrame.isValid(frame));
        assertEquals(0x0401, SmaFrame.key(frame));
    }

    @Test
    public void incomingCallLikeTheBoatApp() {
        byte[] b = SmaNotification.incomingCall(TIME, "MorseNav");
        assertEquals(1, b[0]);
        for (int i = 7; i < 39; i++) assertEquals(0, b[i]);            // no package
        assertEquals("MorseNav", new String(b, 39, 8, StandardCharsets.UTF_8));
        assertEquals("Incoming call", new String(b, 71, b.length - 71, StandardCharsets.UTF_8));
    }

    @Test
    public void callEndedIsCategoryOneWithEmptyFields() {
        byte[] b = SmaNotification.callEnded();
        assertEquals(1 + 6 + 32 + 32, b.length);
        assertEquals(1, b[0]);
        assertEquals((byte) (1970 - 2000), b[1]);
        for (int i = 7; i < b.length; i++) assertEquals(0, b[i]);
    }
}
