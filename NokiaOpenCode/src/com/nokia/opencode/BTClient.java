package com.nokia.opencode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;
import javax.bluetooth.DeviceClass;
import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.DiscoveryListener;
import javax.bluetooth.LocalDevice;
import javax.bluetooth.RemoteDevice;
import javax.bluetooth.ServiceRecord;
import javax.bluetooth.UUID;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.io.StreamConnectionNotifier;

/**
 * Bluetooth SPP (+ TCP fallback) transport.
 * Phone is the client: it discovers the PC service "OpenCode-Nokia"
 * (BlueZ adapter 0C:84:DC:50:0D:9C, channel 1) and opens btspp:// URL.
 * Inquiries are async under JSR-82; this class blocks with monitors so
 * the MIDlet stays simple and small.
 */
public class BTClient implements DiscoveryListener {

    /** Callback into the MIDlet. All calls come from the reader thread. */
    public interface Listener {
        void onLine(String line);
        void onDisconnect(String reason);
    }

    private Listener listener;
    private StreamConnection conn;
    private StreamConnectionNotifier pendingNotifier;
    private InputStream in;
    private OutputStream out;
    private Thread reader;
    private volatile boolean running;

    private final Object inquiryLock = new Object();
    private final Object searchLock = new Object();
    private Vector devices = new Vector();
    private String serviceURL;
    private int inquiryResult;

    public BTClient(Listener l) {
        listener = l;
    }

    public boolean isConnected() {
        return conn != null && running;
    }

    /**
     * Connect to the PC.
     * Tries direct dial first (no inquiry: btspp://PC:ch1), then falls
     * back to full device+service discovery. TCP if tcpHost is set.
     * @return display string of what was connected, for the status line.
     */
    public String connect(String tcpHost) throws Exception {
        closeQuiet();
        if (tcpHost != null && tcpHost.length() > 0) {
            String url = "socket://" + tcpHost + ":" + Protocol.TCP_PORT;
            conn = (StreamConnection) Connector.open(url);
            openStreams();
            sendLine(Protocol.HELLO_PREFIX + Protocol.VERSION);
            return "TCP " + tcpHost;
        }
        // 0. Direct dial: PC adapter + known SPP channel. No inquiry,
        // no discoverability window needed on either side.
        try {
            String direct = "btspp://" + Protocol.PC_BT_ADDR + ":"
                    + Protocol.PC_SPP_CHANNEL
                    + ";authenticate=false;encrypt=false;master=false";
            conn = (StreamConnection) Connector.open(direct);
            openStreams();
            sendLine(Protocol.HELLO_PREFIX + Protocol.VERSION);
            return "BT direct";
        } catch (Exception directFail) {
            closeQuiet();
            // fall through to discovery below
        }
        LocalDevice local;
        try {
            local = LocalDevice.getLocalDevice();
        } catch (Exception e) {
            throw new Exception("BT off? (" + e.getMessage() + ")");
        }
        local.setDiscoverable(javax.bluetooth.DiscoveryAgent.GIAC);
        DiscoveryAgent agent = local.getDiscoveryAgent();

        // 1. Device inquiry — look for the bonded 6300 peer (the PC).
        devices.removeAllElements();
        serviceURL = null;
        synchronized (inquiryLock) {
            if (!agent.startInquiry(DiscoveryAgent.GIAC, this)) {
                throw new Exception("inquiry busy");
            }
            try { inquiryLock.wait(60000); } catch (Exception e) {}
        }
        if (devices.size() == 0) {
            throw new Exception("no BT devices (proxy up?)");
        }
        // 2. Service search for SPP on every found device.
        UUID[] uuids = new UUID[] { new UUID(Protocol.SPP_UUID, false) };
        for (int i = 0; i < devices.size(); i++) {
            RemoteDevice dev = (RemoteDevice) devices.elementAt(i);
            synchronized (searchLock) {
                serviceURL = null;
                try {
                    agent.searchServices(null, uuids, dev, this);
                    try { searchLock.wait(30000); } catch (Exception e) {}
                } catch (Exception e) { continue; }
            }
            if (serviceURL != null) break;
        }
        if (serviceURL == null) {
            throw new Exception("no OpenCode svc (proxy up?)");
        }
        conn = (StreamConnection) Connector.open(serviceURL);
        openStreams();
        sendLine(Protocol.HELLO_PREFIX + Protocol.VERSION);
        return "BT SPP";
    }

    private void openStreams() throws IOException {
        in = conn.openInputStream();
        out = conn.openOutputStream();
        running = true;
        reader = new Thread(new Reader());
        reader.start();
    }

    /**
     * Reverse direction: open an SPP service on the phone and wait for
     * the PC to connect ("pair PC to Nokia"). Blocks until a client
     * connects or the notifier is closed. Runs on the calling thread —
     * call it from a worker thread, then use openStreams().
     */
    public String listen() throws Exception {
        closeQuiet();
        StreamConnectionNotifier notifier = null;
        try {
            notifier = (StreamConnectionNotifier) Connector.open(
                    "btspp://localhost:" + Protocol.SPP_UUID
                    + ";name=" + Protocol.SERVICE_NAME);
        } catch (Exception e) {
            throw new Exception("BT server busy? (" + e.getMessage() + ")");
        }
        pendingNotifier = notifier;
        // NOTE: keep the notifier OPEN for the life of the accepted
        // connection. Some S40 stacks drop the link if the notifier
        // is closed right after accept. It is closed in closeQuiet().
        conn = notifier.acceptAndOpen();
        if (conn == null) {
            throw new Exception("accept cancelled");
        }
        openStreams();
        sendLine(Protocol.HELLO_PREFIX + Protocol.VERSION);
        return "BT server";
    }

    /** Cancel a blocking listen(). */
    public void cancelListen() {
        try {
            if (pendingNotifier != null) pendingNotifier.close();
        } catch (Exception ignore) {}
    }

    public synchronized void sendLine(String line) throws IOException {
        if (out == null) throw new IOException("not connected");
        String s = line + "\n";
        byte[] b = s.getBytes("UTF-8");
        out.write(b);
        out.flush();
    }

    /** Send AMR bytes as VOICE:BEGIN/DATA/END base64 lines. */
    public void sendVoice(byte[] amr) throws IOException {
        sendLine(Protocol.VOICE_BEGIN + amr.length);
        int off = 0;
        // 384 raw bytes -> 512 b64 chars per line.
        while (off < amr.length) {
            int n = amr.length - off;
            if (n > 384) n = 384;
            sendLine(Protocol.VOICE_DATA
                    + Protocol.base64Encode(amr, off, n));
            off += n;
        }
        sendLine(Protocol.VOICE_END);
    }

    public void close() {
        try {
            if (running) sendLine(Protocol.BYE);
        } catch (Exception ignore) {}
        closeQuiet();
    }

    private void closeQuiet() {
        running = false;
        try { if (in != null) in.close(); } catch (Exception i) {}
        try { if (out != null) out.close(); } catch (Exception i) {}
        try { if (conn != null) conn.close(); } catch (Exception i) {}
        try {
            if (pendingNotifier != null) pendingNotifier.close();
        } catch (Exception i) {}
        in = null; out = null; conn = null;
        pendingNotifier = null;
    }

    // ---- DiscoveryListener ----

    public void deviceDiscovered(RemoteDevice btDevice, DeviceClass cod) {
        devices.addElement(btDevice);
    }

    public void inquiryCompleted(int discType) {
        synchronized (inquiryLock) { inquiryLock.notify(); }
    }

    public void servicesDiscovered(int transID, ServiceRecord[] servRecord) {
        if (servRecord != null) {
            for (int i = 0; i < servRecord.length; i++) {
                String url = servRecord[i].getConnectionURL(
                        ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
                if (url != null && url.startsWith("btspp://")) {
                    // Prefer a service literally named OpenCode-Nokia,
                    // else take the first SPP service (usually channel 1).
                    try {
                        javax.bluetooth.DataElement name =
                            servRecord[i].getAttributeValue(0x0100);
                        if (name != null
                                && Protocol.SERVICE_NAME.equals(name.getValue())) {
                            serviceURL = url;
                            break;
                        }
                    } catch (Exception ignore) {}
                    if (serviceURL == null) serviceURL = url;
                }
            }
        }
    }

    public void serviceSearchCompleted(int transID, int respCode) {
        synchronized (searchLock) { searchLock.notify(); }
    }

    private class Reader implements Runnable {
        public void run() {
            try {
                StringBuffer line = new StringBuffer();
                int ch;
                // Manual UTF-8 decode: InputStreamReader may not exist
                // on all S40 firmwares; decode bytes ourselves.
                byte[] mb = new byte[4];
                while (running && (ch = in.read()) != -1) {
                    if (ch == '\n') {
                        String s = line.toString();
                        line.setLength(0);
                        if (s.endsWith("\r")) {
                            s = s.substring(0, s.length() - 1);
                        }
                        listener.onLine(s);
                    } else if (ch == '\r') {
                        // ignore, handled at \n
                    } else if ((ch & 0x80) == 0) {
                        line.append((char) ch);
                    } else {
                        // multi-byte UTF-8 sequence
                        int need;
                        int cp;
                        if ((ch & 0xE0) == 0xC0) {
                            need = 1; cp = ch & 0x1F;
                        } else if ((ch & 0xF0) == 0xE0) {
                            need = 2; cp = ch & 0x0F;
                        } else {
                            need = 3; cp = ch & 0x07;
                        }
                        for (int k = 0; k < need; k++) {
                            int b = in.read();
                            if (b == -1) break;
                            cp = (cp << 6) | (b & 0x3F);
                        }
                        line.append((char) cp);
                    }
                }
            } catch (Exception e) {
                // fall through to disconnect
            }
            if (running) {
                running = false;
                listener.onDisconnect("link lost");
            }
        }
    }
}
