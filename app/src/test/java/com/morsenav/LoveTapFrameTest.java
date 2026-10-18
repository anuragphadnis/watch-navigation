package com.morsenav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LoveTapFrameTest {

    @Test
    public void layout() {
        long before = System.currentTimeMillis();
        byte[] f = WatchLink.loveTapFrame(3, WatchLink.LOVE_TAP_DOWN);
        assertTrue(SmaFrame.isValid(f));
        assertEquals(0x0608, SmaFrame.key(f));
        assertEquals(6 + 3 + 10, f.length);
        long t = 0;
        for (int i = 0; i < 8; i++) t |= (f[9 + i] & 0xffL) << (8 * i);
        assertTrue(t >= before && t <= System.currentTimeMillis());
        assertEquals(3, f[17]);
        assertEquals(1, f[18]);
    }

    @Test
    public void contactLayout() {
        byte[] u = WatchLink.loveTapUser(1, "MorseNav");
        assertEquals(28, u.length);
        assertEquals(1, u[0]);
        assertEquals(0, u[1]);
        assertEquals(0, u[3]);
        assertEquals("MorseNav", new String(u, 4, 8, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0, u[12]);
        assertEquals(0, u[27]);
    }
}
