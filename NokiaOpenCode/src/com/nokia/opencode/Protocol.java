package com.nokia.opencode;

/**
 * Wire protocol constants shared by the MIDlet and BTClient.
 *
 * Transport: Bluetooth SPP (btspp://) or TCP fallback (socket://),
 * UTF-8 text lines terminated by '\n'. Audio bytes are base64 chunks
 * so the stream never needs raw binary framing (InputStreamReader-safe).
 */
public final class Protocol {

    private Protocol() {}

    public static final String APP_NAME = "OpenCode Remote";
    public static final String VERSION = "1.0";

    // Service discovery
    public static final String SERVICE_NAME = "OpenCode-Nokia";
    // Standard SPP UUID 0x1101. The 6300 + BlueZ both advertise this
    // (verified: bluetoothctl info 00:22:66:C8:4C:07 lists 0x1101).
    public static final String SPP_UUID = "1101";
    // PC side of the link (verified: BlueZ adapter 0C:84:DC:50:0D:9C,
    // SPP on RFCOMM channel 1). Direct-dial needs no inquiry.
    public static final String PC_BT_ADDR = "0C84DC500D9C";
    public static final int PC_SPP_CHANNEL = 1;
    // TCP fallback for emulator / PAN testing.
    public static final int TCP_PORT = 19741;

    // Phone -> PC
    public static final String HELLO_PREFIX = "HELLO Nokia6300 ";
    public static final String VOICE_BEGIN = "VOICE:BEGIN ";
    public static final String VOICE_DATA = "VOICE:DATA ";
    public static final String VOICE_END = "VOICE:END";
    public static final String TEXT_PREFIX = "TEXT:";
    public static final String CANCEL = "CANCEL";
    public static final String BYE = "BYE";
    public static final String PING = "PING";
    public static final String PONG = "PONG";

    // PC -> Phone
    public static final String PC_HELLO_PREFIX = "HELLO ";
    public static final String STATUS_PREFIX = "STATUS ";
    public static final String PROGRESS_PREFIX = "PROGRESS ";
    public static final String PC_TEXT_PREFIX = "TEXT ";
    public static final String DONE = "DONE";
    public static final String ERROR_PREFIX = "ERROR ";

    // Progress stages sent inside PROGRESS <stage>|<pct>|<label>
    public static final String STAGE_RECEIVING = "RECEIVING";
    public static final String STAGE_TRANSCRIBING = "TRANSCRIBING";
    public static final String STAGE_SENDING = "SENDING";
    public static final String STAGE_WORKING = "WORKING";

    public static final int MAX_VOICE_SECONDS = 30;
    // Base64 line length: 512 chars = 384 audio bytes per line.
    public static final int B64_CHUNK_CHARS = 512;

    private static final String B64 =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /** Encode bytes to base64. CLDC 1.1: no java.util.Base64. */
    public static String base64Encode(byte[] data, int off, int len) {
        StringBuffer sb = new StringBuffer(((len + 2) / 3) * 4);
        int end = off + len;
        int i = off;
        while (i < end) {
            int b0 = data[i++] & 0xFF;
            int b1 = (i < end) ? (data[i++] & 0xFF) : -1;
            int b2 = (i < end) ? (data[i++] & 0xFF) : -1;
            sb.append(B64.charAt(b0 >> 2));
            sb.append(B64.charAt(((b0 & 0x3) << 4) | ((b1 >= 0 ? b1 : 0) >> 4)));
            if (b1 < 0) {
                sb.append('=');
                sb.append('=');
            } else if (b2 < 0) {
                sb.append(B64.charAt(((b1 & 0xF) << 2)));
                sb.append('=');
            } else {
                sb.append(B64.charAt(((b1 & 0xF) << 2) | (b2 >> 6)));
                sb.append(B64.charAt(b2 & 0x3F));
            }
        }
        return sb.toString();
    }
}
