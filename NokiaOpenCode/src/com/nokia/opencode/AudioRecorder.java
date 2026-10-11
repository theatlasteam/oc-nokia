package com.nokia.opencode;

import java.io.ByteArrayOutputStream;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.control.RecordControl;

/**
 * AMR-NB voice recorder via MMAPI (JSR-135).
 * Nokia 6300 supports capture://audio?encoding=amr.
 * Max MAX_VOICE_SECONDS keeps files ~45 KB (AMR 12.2 kbps).
 */
public class AudioRecorder {

    private Player player;
    private RecordControl rc;
    private ByteArrayOutputStream buf;

    /** Start recording. Throws Exception with a short reason on failure. */
    public void start() throws Exception {
        stopQuiet();
        buf = new ByteArrayOutputStream();
        try {
            player = Manager.createPlayer("capture://audio?encoding=amr");
        } catch (Exception e) {
            // Fallback locator some firmwares prefer.
            player = Manager.createPlayer("capture://audio");
        }
        if (player == null) {
            throw new Exception("no capture player");
        }
        player.realize();
        rc = (RecordControl) player.getControl("RecordControl");
        if (rc == null) {
            throw new Exception("no RecordControl");
        }
        rc.setRecordStream(buf);
        rc.startRecord();
        player.start();
    }

    /** Stop and return the recorded AMR bytes (may be empty). */
    public byte[] stop() {
        byte[] out = new byte[0];
        try {
            if (rc != null) {
                try { rc.commit(); } catch (Exception ignore) {}
            }
            if (player != null) {
                try { player.stop(); } catch (Exception ignore) {}
                try { player.close(); } catch (Exception ignore) {}
            }
            if (buf != null) {
                out = buf.toByteArray();
            }
        } catch (Exception ignore) {
        } finally {
            player = null;
            rc = null;
            buf = null;
        }
        return out;
    }

    private void stopQuiet() {
        try {
            if (player != null) { player.close(); }
        } catch (Exception ignore) {}
        player = null;
        rc = null;
        buf = null;
    }
}
