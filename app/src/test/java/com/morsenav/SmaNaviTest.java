package com.morsenav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.GregorianCalendar;

import org.junit.Test;

public class SmaNaviTest {

    private static final Calendar TIME = new GregorianCalendar(2026, Calendar.OCTOBER, 18, 15, 33, 10);

    @Test
    public void layout() {
        byte[] body = SmaNavi.encode(SmaNavi.STATE_START, TIME, SmaNavi.TURN_LEFT,
                1500, 300, 200, 20, 0, "Test Rd");
        assertEquals("00" + "1a0a120f210a" + "14" + "000005dc" + "0000012c" + "000000c8" + "14" + "00"
                + "0007" + "54657374205264", SmaFrame.hex(body));
    }

    @Test
    public void timeMatchesCapturedSetTimeFrame() {
        // The boAt app's TIME frame carried 1a0a120f210a for 2026-10-18 15:33:10.
        byte[] body = SmaNavi.encode(0, TIME, 0, 0, 0, 0, 0, 0, "");
        assertEquals("1a0a120f210a", SmaFrame.hex(body).substring(2, 14));
    }

    @Test
    public void nameIsLimitedWithoutSplittingCharacters() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("ಕ"); // 3 bytes each in UTF-8
        byte[] cut = SmaNavi.truncateUtf8(sb.toString(), SmaNavi.NAME_MAX_LENGTH);
        assertEquals(231, cut.length);
        assertTrue(new String(cut, java.nio.charset.StandardCharsets.UTF_8).chars().allMatch(c -> c == 'ಕ'));
    }

    @Test
    public void framedNaviMessageValidates() {
        byte[] frame = SmaFrame.request(SmaNavi.KEY_NAVI_INFO, SmaFrame.KEY_FLAG_UPDATE,
                SmaNavi.encode(SmaNavi.STATE_GOING, TIME, SmaNavi.TURN_RIGHT, 1000, 120, 50, 25, 0, "MG Road"));
        assertTrue(SmaFrame.isValid(frame));
        assertEquals(SmaNavi.KEY_NAVI_INFO, SmaFrame.key(frame));
    }
}
