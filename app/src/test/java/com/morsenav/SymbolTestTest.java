package com.morsenav;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class SymbolTestTest {

    @Test
    public void symbolListFitsInOneNotification() {
        byte[] bytes = WatchLink.symbolList().getBytes(StandardCharsets.UTF_8);
        assertTrue("symbol list is " + bytes.length + " bytes", bytes.length <= SmaNotification.CONTENT_MAX_LENGTH);
    }

    @Test
    public void titleTestFitsTheTitleField() {
        assertTrue("← L  → R  ↶ U".getBytes(StandardCharsets.UTF_8).length <= SmaNotification.FIELD_LENGTH - 1);
    }
}
