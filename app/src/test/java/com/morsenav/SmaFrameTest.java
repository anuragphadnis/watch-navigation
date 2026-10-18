package com.morsenav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Frames captured from the boAt app talking to a boAt Wave Fury (btsnoop, 2026-10-18). */
public class SmaFrameTest {

    private static final String[] CAPTURED = {
            "ab010007b1b2030220ffffffff",
            "ab010004b62102020016",
            "ab010009bbef0201001a0a120f210a",
            "ab0100047c8002140001",
            "ab01000b7d49025000ffffffffffffffff",
            "ab01000300b7023400",
            "ab010004764002340000",
            "ab11000444b803022000",
            "ab11000300b7023400",
    };

    private static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    @Test
    public void findWatchMatchesCapture() {
        byte[] frame = SmaFrame.request(SmaFrame.KEY_FIND_WATCH, SmaFrame.KEY_FLAG_UPDATE, new byte[0]);
        assertEquals("ab01000300b7023400", SmaFrame.hex(frame));
    }

    @Test
    public void capturedFramesValidate() {
        for (String hex : CAPTURED) assertTrue(hex, SmaFrame.isValid(bytes(hex)));
    }

    @Test
    public void rebuildingCapturedFramesIsByteExact() {
        for (String hex : CAPTURED) {
            byte[] f = bytes(hex);
            byte[] payload = new byte[f.length - SmaFrame.HEADER_LENGTH];
            System.arraycopy(f, SmaFrame.HEADER_LENGTH, payload, 0, payload.length);
            assertEquals(hex, SmaFrame.hex(SmaFrame.frame(f[1] & 0xff, payload)));
        }
    }

    @Test
    public void corruptedFrameIsRejected() {
        byte[] f = bytes("ab01000300b7023400");
        f[8] = 1;
        assertFalse(SmaFrame.isValid(f));
        assertFalse(SmaFrame.isValid(bytes("ab01000300b70234")));
    }

    @Test
    public void keyAndAck() {
        byte[] ack = bytes("ab11000300b7023400");
        assertEquals(0x0234, SmaFrame.key(ack));
        assertTrue(SmaFrame.isAck(ack));
        assertEquals(0x0201, SmaFrame.key(bytes("ab010009bbef0201001a0a120f210a")));
    }
}
