package com.morsenav;

/**
 * Frames for the SMA watch protocol used by the boAt Wave Fury (the boAt app's szabh/smable3 SDK).
 *
 * <pre>
 * ab | flags | length (2, BE) | CRC-16/ARC of payload (2, BE) | payload
 * payload = command | key | key flag | data...
 * </pre>
 * Flags are 0x01 for a request and 0x11 for an acknowledgement. Keys are written as one 16-bit
 * value, command in the high byte (e.g. FIND_WATCH = 0x0234: command SET, key 0x34).
 */
final class SmaFrame {

    static final int MAGIC = 0xab;
    static final int FLAGS_REQUEST = 0x01;
    static final int FLAGS_ACK = 0x11;
    static final int HEADER_LENGTH = 6;

    // BleKey values, as defined in com.szabh.smable3.BleKey.
    static final int KEY_FIND_WATCH = 0x0234;

    // BleKeyFlag values.
    static final int KEY_FLAG_UPDATE = 0x00;

    private SmaFrame() {}

    static byte[] request(int key, int keyFlag, byte[] data) {
        byte[] payload = new byte[3 + data.length];
        payload[0] = (byte) (key >> 8);
        payload[1] = (byte) key;
        payload[2] = (byte) keyFlag;
        System.arraycopy(data, 0, payload, 3, data.length);
        return frame(FLAGS_REQUEST, payload);
    }

    static byte[] frame(int flags, byte[] payload) {
        int crc = crc16Arc(payload, 0, payload.length);
        byte[] out = new byte[HEADER_LENGTH + payload.length];
        out[0] = (byte) MAGIC;
        out[1] = (byte) flags;
        out[2] = (byte) (payload.length >> 8);
        out[3] = (byte) payload.length;
        out[4] = (byte) (crc >> 8);
        out[5] = (byte) crc;
        System.arraycopy(payload, 0, out, HEADER_LENGTH, payload.length);
        return out;
    }

    /** True if {@code frame} is complete and its CRC matches. */
    static boolean isValid(byte[] frame) {
        if (frame.length < HEADER_LENGTH || (frame[0] & 0xff) != MAGIC) return false;
        int length = ((frame[2] & 0xff) << 8) | (frame[3] & 0xff);
        if (frame.length != HEADER_LENGTH + length) return false;
        int crc = ((frame[4] & 0xff) << 8) | (frame[5] & 0xff);
        return crc == crc16Arc(frame, HEADER_LENGTH, length);
    }

    /** The 16-bit key of a frame, e.g. 0x0234. Assumes {@link #isValid}. */
    static int key(byte[] frame) {
        return ((frame[HEADER_LENGTH] & 0xff) << 8) | (frame[HEADER_LENGTH + 1] & 0xff);
    }

    static boolean isAck(byte[] frame) {
        return (frame[1] & 0xff) == FLAGS_ACK;
    }

    /** CRC-16/ARC: poly 0x8005 reflected (0xA001), init 0, no final xor. */
    static int crc16Arc(byte[] data, int offset, int length) {
        int crc = 0;
        for (int i = offset; i < offset + length; i++) {
            crc ^= data[i] & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
            }
        }
        return crc;
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
