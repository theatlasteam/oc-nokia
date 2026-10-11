package com.nokia.opencode;

import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;

/**
 * OpenCode Remote for Nokia 6300 — dark Canvas UI with the OpenCode logo.
 *
 * Menu -> Record Voice: captures AMR via MMAPI, streams it over
 * Bluetooth SPP to the PC bridge, then shows a live animated progress
 * bar plus the streamed answer on the 240x320 display.
 */
public class OpenCodeMIDlet extends MIDlet
        implements CommandListener {

    private Display display;

    private Image logo;
    private Image logoSmall;

    private SplashCanvas splash;
    private MenuCanvas menu;
    private StatusCanvas statusScreen;
    private TextBox inputBox;

    private Command cmdSelect = new Command("Select", Command.OK, 1);
    private Command cmdSkip = new Command("Skip", Command.OK, 1);
    private Command cmdStop = new Command("Stop", Command.STOP, 1);
    private Command cmdSend = new Command("Send", Command.OK, 1);
    private Command cmdCancel = new Command("Cancel job", Command.STOP, 3);
    private Command cmdBack = new Command("Back", Command.BACK, 5);
    private Command cmdExit = new Command("Exit", Command.EXIT, 9);

    private BTClient bt;
    private AudioRecorder recorder;
    private boolean recording;
    private boolean quietListenCancel;
    private Timer recTimer;
    private int recSeconds;

    // Job state (written from the BT reader thread, read on UI thread).
    private Vector chatLines = new Vector();
    private String lastStatus = "Not connected";
    private String lastStage = "IDLE";
    private String lastLabel = "waiting for phone";
    private int lastPct;

    // Progress-stripe animation.
    private Timer animTimer;
    private int animPhase;

    // Cached wrapped chat for the status canvas.
    private String[] renderLines = new String[0];
    private char[] renderKinds = new char[0];
    private int renderWidth = -1;
    private boolean chatDirty = true;

    /** One chat row: Y = you, A = assistant, T = tool, S = system. */
    static class ChatLine {
        char kind;
        String text;
        ChatLine(char k, String t) { kind = k; text = t; }
    }

    private static final String[] MENU_TITLES = {
        "Connect", "Wait for PC", "Record voice note", "Type command",
        "Status / answer", "Cancel job", "Disconnect"
    };

    public OpenCodeMIDlet() {
        display = Display.getDisplay(this);
        bt = new BTClient(new PhoneListener());
        recorder = new AudioRecorder();
    }

    protected void startApp() {
        try {
            logo = Image.createImage("/logo.png");
        } catch (Exception e) {
            logo = null;
        }
        try {
            logoSmall = Image.createImage("/logo_small.png");
        } catch (Exception e) {
            logoSmall = null;
        }
        splash = new SplashCanvas();
        menu = new MenuCanvas();
        statusScreen = new StatusCanvas();
        inputBox = new TextBox("Command", "", 500, TextField.ANY);
        inputBox.addCommand(cmdSend);
        inputBox.addCommand(cmdBack);
        inputBox.setCommandListener(this);
        display.setCurrent(splash);
        // Auto-advance if the user just stares at it.
        Timer t = new Timer();
        t.schedule(new TimerTask() {
            public void run() {
                Display.getDisplay(OpenCodeMIDlet.this)
                        .callSerially(new Runnable() {
                    public void run() {
                        if (display.getCurrent() == splash) {
                            display.setCurrent(menu);
                        }
                    }
                });
            }
        }, 2600);
    }

    protected void pauseApp() {}

    protected void destroyApp(boolean unconditional) {
        try { bt.close(); } catch (Exception ignore) {}
        try { recorder.stop(); } catch (Exception ignore) {}
        if (animTimer != null) {
            try { animTimer.cancel(); } catch (Exception ignore) {}
            animTimer = null;
        }
    }

    // ---------- LCDUI commands (softkeys) ----------

    public void commandAction(Command c, Displayable d) {
        if (c == cmdExit) {
            destroyApp(true);
            notifyDestroyed();
            return;
        }
        if (d == splash) {
            display.setCurrent(menu);
            return;
        }
        if (d == menu) {
            if (c == cmdSelect) {
                handleMenu(menu.sel);
            }
            return;
        }
        if (d == inputBox) {
            if (c == cmdSend) {
                sendTyped();
            } else if (c == cmdBack) {
                display.setCurrent(menu);
            }
            return;
        }
        if (d == statusScreen) {
            if (c == cmdBack) {
                display.setCurrent(menu);
            } else if (c == cmdStop && recording) {
                stopRecordingAndSend();
            } else if (c == cmdCancel) {
                sendCancel();
            }
            return;
        }
    }

    private void handleMenu(int sel) {
        switch (sel) {
            case 0: doConnect(); break;
            case 1: doListen(); break;
            case 2:
                if (!bt.isConnected()) {
                    alert("Not connected", "Connect or Wait for PC first.",
                            AlertType.WARNING);
                } else {
                    startRecording();
                }
                break;
            case 3:
                if (!bt.isConnected()) {
                    alert("Not connected", "Connect or Wait for PC first.",
                            AlertType.WARNING);
                } else {
                    inputBox.setString("");
                    display.setCurrent(inputBox);
                }
                break;
            case 4:
                display.setCurrent(statusScreen);
                break;
            case 5: sendCancel(); break;
            case 6: doDisconnect(); break;
            default: break;
        }
    }

    private String menuDesc(int i) {
        switch (i) {
            case 0:
                return bt.isConnected() ? "linked: " + lastStatus
                        : "Bluetooth SPP";
            case 1: return "PC connects out";
            case 2: return "30 s max, auto-transcribed";
            case 3: return "keypad input";
            case 4: return lastStage + " " + lastPct + "%";
            case 5: return "stop the agent";
            case 6: return "close link";
            default: return "";
        }
    }

    // ---------- Bluetooth ----------

    private void doConnect() {
        // Exclusive mode: kill any stuck Wait-for-PC accept first.
        quietListenCancel = true;
        try { bt.cancelListen(); } catch (Exception ignore) {}
        try { bt.close(); } catch (Exception ignore) {}
        setStatus("Connecting...");
        display.setCurrent(statusScreen);
        new Thread(new Runnable() {
            public void run() {
                String where;
                try {
                    String tcp = getAppProperty("TcpHost");
                    if (tcp != null) tcp = tcp.trim();
                    if (tcp != null && tcp.length() == 0) tcp = null;
                    where = bt.connect(tcp);
                    setStatus("Connected (" + where + ")");
                    toast("Connected", "Link up: " + where);
                } catch (final Exception e) {
                    setStatus("Connect failed");
                    alert("Connect failed", e.getMessage(),
                            AlertType.ERROR);
                }
                showStatus();
                menu.repaint();
            }
        }).start();
    }

    private void doDisconnect() {
        quietListenCancel = true;
        try { bt.cancelListen(); } catch (Exception ignore) {}
        try { bt.close(); } catch (Exception ignore) {}
        stopRecordingCleanup();
        setStatus("Not connected");
        setProgress("IDLE", 0, "waiting for phone");
        display.setCurrent(menu);
        menu.repaint();
    }

    /** Reverse direction: advertise SPP on the phone, PC connects out. */
    private void doListen() {
        quietListenCancel = false;
        setStatus("Waiting for PC...");
        setProgress("WAIT", 0, "BT on, PC dials out");
        display.setCurrent(statusScreen);
        new Thread(new Runnable() {
            public void run() {
                String where;
                try {
                    where = bt.listen();
                    setStatus("Connected (" + where + ")");
                    toast("Connected", "PC connected: " + where);
                } catch (final Exception e) {
                    if (quietListenCancel) {
                        setStatus("Not connected");
                    } else {
                        setStatus("Listen failed");
                        alert("Listen failed", e.getMessage(),
                                AlertType.ERROR);
                    }
                }
                quietListenCancel = false;
                showStatus();
                menu.repaint();
            }
        }).start();
    }

    // ---------- Voice ----------

    private void startRecording() {
        try {
            recorder.start();
        } catch (Exception e) {
            alert("Mic failed", e.getMessage(), AlertType.ERROR);
            return;
        }
        recording = true;
        recSeconds = 0;
        clearChat();
        setProgress("REC", 0, "recording 0s / "
                + Protocol.MAX_VOICE_SECONDS + "s");
        showStatus();
        syncStatusCommands();
        statusScreen.stickToBottom = true;
        recTimer = new Timer();
        recTimer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                recSeconds++;
                if (recSeconds >= Protocol.MAX_VOICE_SECONDS) {
                    Display.getDisplay(OpenCodeMIDlet.this)
                            .callSerially(new Runnable() {
                        public void run() { stopRecordingAndSend(); }
                    });
                } else {
                    final int s = recSeconds;
                    Display.getDisplay(OpenCodeMIDlet.this)
                            .callSerially(new Runnable() {
                        public void run() {
                            setProgress("REC",
                                    (s * 100) / Protocol.MAX_VOICE_SECONDS,
                                    "recording " + s + "s / "
                                    + Protocol.MAX_VOICE_SECONDS
                                    + "s - Stop to send");
                            statusScreen.repaint();
                        }
                    });
                }
            }
        }, 1000, 1000);
    }

    private void stopRecordingAndSend() {
        if (!recording) return;
        recording = false;
        if (recTimer != null) {
            try { recTimer.cancel(); } catch (Exception ignore) {}
            recTimer = null;
        }
        syncStatusCommands();
        byte[] amr = recorder.stop();
        if (amr == null || amr.length == 0) {
            alert("Empty", "No audio captured.", AlertType.WARNING);
            return;
        }
        setProgress("RECEIVING", 100, "sending " + amr.length
                + " bytes...");
        final byte[] data = amr;
        new Thread(new Runnable() {
            public void run() {
                try {
                    bt.sendVoice(data);
                } catch (final Exception e) {
                    Display.getDisplay(OpenCodeMIDlet.this)
                            .callSerially(new Runnable() {
                        public void run() {
                            alert("Send failed", e.getMessage(),
                                    AlertType.ERROR);
                        }
                    });
                }
            }
        }).start();
    }

    private void stopRecordingCleanup() {
        recording = false;
        if (recTimer != null) {
            try { recTimer.cancel(); } catch (Exception ignore) {}
            recTimer = null;
        }
        try { recorder.stop(); } catch (Exception ignore) {}
        syncStatusCommands();
    }

    private void syncStatusCommands() {
        try {
            statusScreen.removeCommand(cmdStop);
            statusScreen.removeCommand(cmdCancel);
            if (recording) {
                statusScreen.addCommand(cmdStop);
            } else {
                statusScreen.addCommand(cmdCancel);
            }
        } catch (Exception ignore) {}
    }

    // ---------- Typed + cancel ----------

    private void sendTyped() {
        final String text = inputBox.getString().trim();
        if (text.length() == 0) return;
        clearChat();
        statusScreen.stickToBottom = true;
        display.setCurrent(statusScreen);
        new Thread(new Runnable() {
            public void run() {
                try {
                    bt.sendLine(Protocol.TEXT_PREFIX + text);
                    setProgressLater("SENDING", 100, "sent");
                } catch (final Exception e) {
                    alertLater("Send failed", e.getMessage());
                }
            }
        }).start();
    }

    private void sendCancel() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (bt.isConnected()) bt.sendLine(Protocol.CANCEL);
                    setProgressLater("WORKING", 0, "cancelled");
                } catch (Exception ignore) {}
            }
        }).start();
        toast("Cancel", "Interrupt sent.");
    }

    // ---------- Phone events (BT reader thread -> UI thread) ----------

    private class PhoneListener implements BTClient.Listener {
        public void onLine(final String line) {
            display.callSerially(new Runnable() {
                public void run() { handleLine(line); }
            });
        }

        public void onDisconnect(final String reason) {
            display.callSerially(new Runnable() {
                public void run() {
                    setStatus("Disconnected: " + reason);
                    showStatus();
                    menu.repaint();
                }
            });
        }
    }

    private void handleLine(String line) {
        if (line.startsWith(Protocol.PROGRESS_PREFIX)) {
            String rest = line.substring(Protocol.PROGRESS_PREFIX.length());
            int p1 = rest.indexOf('|');
            int p2 = rest.indexOf('|', p1 + 1);
            if (p1 > 0 && p2 > p1) {
                String stage = rest.substring(0, p1);
                int pct = 0;
                try {
                    pct = Integer.parseInt(rest.substring(p1 + 1, p2));
                } catch (Exception ignore) {}
                String label = rest.substring(p2 + 1);
                setProgress(stage, pct, label);
            }
        } else if (line.startsWith(Protocol.PC_TEXT_PREFIX)) {
            addChat('A', line.substring(Protocol.PC_TEXT_PREFIX.length()));
            statusScreen.repaint();
        } else if (line.startsWith("YOU ")) {
            addChat('Y', line.substring(4));
            statusScreen.repaint();
            statusScreen.stickToBottom = true;
        } else if (line.startsWith("TOOL ")) {
            addChat('T', line.substring(5));
            statusScreen.repaint();
        } else if (line.startsWith(Protocol.STATUS_PREFIX)) {
            setStatus(line.substring(Protocol.STATUS_PREFIX.length()));
        } else if (line.equals(Protocol.DONE)) {
            setProgress("DONE", 100, "done");
            addChat('S', "-- done --");
            statusScreen.repaint();
            toast("Done", "Job finished.");
        } else if (line.startsWith(Protocol.ERROR_PREFIX)) {
            String msg = line.substring(Protocol.ERROR_PREFIX.length());
            setProgress("ERROR", 0, msg);
            addChat('S', "!! " + msg);
            statusScreen.repaint();
            alert("Error", msg, AlertType.ERROR);
        } else if (line.equals(Protocol.PING)) {
            try { bt.sendLine(Protocol.PONG); } catch (Exception ignore) {}
        }
        if (display.getCurrent() != statusScreen
                && display.getCurrent() != inputBox
                && (line.startsWith(Protocol.PROGRESS_PREFIX)
                    || line.startsWith(Protocol.PC_TEXT_PREFIX)
                    || line.startsWith("YOU ")
                    || line.startsWith("TOOL ")
                    || line.equals(Protocol.DONE))) {
            display.setCurrent(statusScreen);
        }
        menu.repaint();
    }

    // ---------- State helpers (UI thread) ----------

    private void setStatus(String s) {
        lastStatus = s;
        statusScreen.repaint();
    }

    private void setProgress(String stage, int pct, String label) {
        lastStage = stage;
        lastLabel = cleanWire(label);
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        lastPct = pct;
        manageAnim(stage);
        statusScreen.repaint();
        menu.repaint();
    }

    private void setProgressLater(final String stage, final int pct,
            final String label) {
        display.callSerially(new Runnable() {
            public void run() { setProgress(stage, pct, label); }
        });
    }

    private boolean stageActive(String stage) {
        return stage.equals("REC")
                || stage.equals("WAIT")
                || stage.equals(Protocol.STAGE_RECEIVING)
                || stage.equals(Protocol.STAGE_TRANSCRIBING)
                || stage.equals(Protocol.STAGE_SENDING)
                || stage.equals(Protocol.STAGE_WORKING);
    }

    private void manageAnim(String stage) {
        // No animated widgets on the chat screen; make sure no timer runs.
        if (animTimer != null) {
            try { animTimer.cancel(); } catch (Exception ignore) {}
            animTimer = null;
        }
    }

    private void showStatus() {
        display.callSerially(new Runnable() {
            public void run() { display.setCurrent(statusScreen); }
        });
    }

    private void toast(String title, String msg) {
        Alert a = new Alert(title, msg, logoSmall, AlertType.INFO);
        a.setTimeout(2000);
        display.setCurrent(a, display.getCurrent());
    }

    private void alert(String title, String msg, AlertType type) {
        Alert a = new Alert(title, msg, logoSmall, type);
        a.setTimeout(Alert.FOREVER);
        display.setCurrent(a, display.getCurrent());
    }

    private void alertLater(final String title, final String msg) {
        display.callSerially(new Runnable() {
            public void run() {
                alert(title, msg, AlertType.ERROR);
            }
        });
    }

    /**
     * The 6300 system font lacks fancy punctuation (ellipsis, em-dash,
     * curly quotes). Map them to ASCII so nothing renders as boxes.
     */
    private String cleanWire(String s) {
        if (s == null) return "";
        boolean dirty = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\u2026' || ch == '\u2014' || ch == '\u2013'
                    || ch == '\u2018' || ch == '\u2019'
                    || ch == '\u201C' || ch == '\u201D') {
                dirty = true;
                break;
            }
        }
        if (!dirty) return s;
        StringBuffer sb = new StringBuffer(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\u2026') sb.append("...");
            else if (ch == '\u2014' || ch == '\u2013') sb.append('-');
            else if (ch == '\u2018' || ch == '\u2019') sb.append('\'');
            else if (ch == '\u201C' || ch == '\u201D') sb.append('"');
            else sb.append(ch);
        }
        return sb.toString();
    }

    /** Append a chat row (dedupe consecutive repeats from streams). */
    private void addChat(char kind, String text) {
        String clean = cleanWire(text);
        int n = chatLines.size();
        if (n > 0) {
            ChatLine last = (ChatLine) chatLines.elementAt(n - 1);
            if (last.kind == kind && last.text.equals(clean)) return;
        }
        chatLines.addElement(new ChatLine(kind, clean));
        while (chatLines.size() > 80) {
            chatLines.removeElementAt(0);
        }
        chatDirty = true;
    }

    private void clearChat() {
        chatLines.removeAllElements();
        chatDirty = true;
    }

    /**
     * Flatten chat into wrapped render rows. Prefixes: "> " you (accent),
     * "- " tool (dim), "--"/"!!" system (dim). Blank spacer on kind change.
     */
    private void renderChat(int width) {
        if (!chatDirty && width == renderWidth) return;
        Vector lines = new Vector();
        Vector kinds = new Vector();
        Font f = Ui.small();
        char prev = 0;
        boolean first = true;
        for (int i = 0; i < chatLines.size(); i++) {
            ChatLine cl = (ChatLine) chatLines.elementAt(i);
            if (!first && cl.kind != prev) {
                lines.addElement("");
                kinds.addElement(new Character(' '));
            }
            first = false;
            prev = cl.kind;
            String shown;
            if (cl.kind == 'Y') shown = "> " + cl.text;
            else if (cl.kind == 'T') shown = "- " + cl.text;
            else shown = cl.text;
            String[] w = Ui.wrap(shown, f, width);
            if (w.length == 0) {
                lines.addElement("");
                kinds.addElement(new Character(cl.kind));
            }
            for (int k = 0; k < w.length; k++) {
                lines.addElement(w[k]);
                kinds.addElement(new Character(cl.kind));
            }
        }
        renderLines = new String[lines.size()];
        renderKinds = new char[lines.size()];
        for (int i = 0; i < renderLines.length; i++) {
            renderLines[i] = (String) lines.elementAt(i);
            renderKinds[i] = ((Character) kinds.elementAt(i)).charValue();
        }
        renderWidth = width;
        chatDirty = false;
    }

    // ================= Splash =================

    private class SplashCanvas extends Canvas {
        SplashCanvas() {
            addCommand(cmdSkip);
            setCommandListener(OpenCodeMIDlet.this);
        }

        protected void paint(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            g.setColor(Ui.BG);
            g.fillRect(0, 0, w, h);
            if (logo != null) {
                int lw = logo.getWidth();
                int lh = logo.getHeight();
                if (lw > w - 16) {
                    // Should not happen (216px art on 240px screen).
                    g.drawImage(logo, w / 2, 110, Graphics.TOP | Graphics.HCENTER);
                } else {
                    g.drawImage(logo, (w - lw) / 2, 110, Graphics.TOP | Graphics.LEFT);
                }
            } else {
                g.setColor(Ui.TEXT);
                g.setFont(Ui.largeBold());
                g.drawString("OpenCode", w / 2, 110,
                        Graphics.TOP | Graphics.HCENTER);
            }
            g.setColor(Ui.ACCENT);
            g.setFont(Ui.medBold());
            g.drawString("V O I C E   R E M O T E", w / 2, 168,
                    Graphics.TOP | Graphics.HCENTER);
            g.setColor(Ui.DIM);
            g.setFont(Ui.small());
            g.drawString("Voice control for OpenCode", w / 2, 190,
                    Graphics.TOP | Graphics.HCENTER);
            g.drawString("v1.5 - Nokia 6300", w / 2, 206,
                    Graphics.TOP | Graphics.HCENTER);
            g.drawString("Press any key...", w / 2, h - 30,
                    Graphics.TOP | Graphics.HCENTER);
        }

        protected void keyPressed(int keyCode) {
            display.setCurrent(menu);
        }
    }

    // ================= Menu =================

    private class MenuCanvas extends Canvas {
        int sel;
        int top;

        MenuCanvas() {
            addCommand(cmdSelect);
            addCommand(cmdExit);
            setCommandListener(OpenCodeMIDlet.this);
        }

        protected void paint(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            g.setColor(Ui.BG);
            g.fillRect(0, 0, w, h);
            Ui.header(g, w, logoSmall, "OpenCode Remote", "v1.5");

            int y0 = 44;
            int rowH = 38;
            int maxRows = (h - y0) / rowH;
            if (sel < top) top = sel;
            if (sel >= top + maxRows) top = sel - maxRows + 1;

            for (int i = top; i < MENU_TITLES.length
                    && i < top + maxRows; i++) {
                int y = y0 + (i - top) * rowH;
                if (i == sel) {
                    g.setColor(Ui.ROW_SEL);
                    g.fillRoundRect(4, y, w - 8, rowH - 4, 8, 8);
                    g.setColor(Ui.ACCENT);
                    g.fillRect(4, y + 5, 3, rowH - 14);
                }
                g.setColor(i == sel ? Ui.TEXT : Ui.DIM);
                g.setFont(i == sel ? Ui.medBold() : Ui.med());
                g.drawString((i + 1) + ". " + MENU_TITLES[i], 14, y + 3,
                        Graphics.TOP | Graphics.LEFT);
                g.setColor(Ui.DIM);
                g.setFont(Ui.small());
                g.drawString(Ui.ellipsize(menuDesc(i), Ui.small(), w - 28),
                        14, y + 20, Graphics.TOP | Graphics.LEFT);
                // Link dot on the Connect row.
                if (i == 0) {
                    Ui.dot(g, bt.isConnected() ? Ui.OK : Ui.ERR,
                            w - 16, y + 12, 4);
                }
            }
            g.setColor(Ui.DIM);
            g.setFont(Ui.small());
            g.drawString("Fire: open   1-6: jump", w / 2, h - 16,
                    Graphics.TOP | Graphics.HCENTER);
        }

        protected void keyPressed(int keyCode) {
            int action = getGameAction(keyCode);
            if (action == UP) {
                sel = (sel + MENU_TITLES.length - 1) % MENU_TITLES.length;
                repaint();
            } else if (action == DOWN) {
                sel = (sel + 1) % MENU_TITLES.length;
                repaint();
            } else if (action == FIRE) {
                handleMenu(sel);
            } else if (keyCode >= Canvas.KEY_NUM1
                    && keyCode <= Canvas.KEY_NUM1 + MENU_TITLES.length - 1) {
                sel = keyCode - Canvas.KEY_NUM1;
                repaint();
                handleMenu(sel);
            }
        }

        protected void keyRepeated(int keyCode) {
            keyPressed(keyCode);
        }
    }

    // ================= Status =================

    private class StatusCanvas extends Canvas {
        int scroll;
        boolean stickToBottom = true;

        StatusCanvas() {
            addCommand(cmdBack);
            addCommand(cmdCancel);
            setCommandListener(OpenCodeMIDlet.this);
        }

        protected void paint(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            g.setColor(Ui.BG);
            g.fillRect(0, 0, w, h);
            Ui.header(g, w, logoSmall, "OpenCode Remote",
                    bt.isConnected() ? "linked" : "offline");

            // Link line with dot.
            g.setFont(Ui.small());
            int dotColor = bt.isConnected() ? Ui.OK : Ui.ERR;
            Ui.dot(g, dotColor, 14, 44, 4);
            g.setColor(Ui.DIM);
            g.drawString(Ui.ellipsize(lastStatus, Ui.small(), w - 28),
                    22, 37, Graphics.TOP | Graphics.LEFT);

            // Stage + percent.
            g.setColor(Ui.TEXT);
            g.setFont(Ui.medBold());
            String stageLine = recording
                    ? "REC " + recSeconds + "s" : lastStage + " " + lastPct + "%";
            g.drawString(Ui.ellipsize(stageLine, Ui.medBold(), w - 16),
                    8, 54, Graphics.TOP | Graphics.LEFT);
            if (recording) {
                Ui.dot(g, Ui.REC, w - 16, 62, 5);
            }

            // Label (2 lines max).
            g.setColor(Ui.DIM);
            g.setFont(Ui.small());
            String[] lab = Ui.wrap(lastLabel, Ui.small(), w - 16);
            for (int i = 0; i < lab.length && i < 2; i++) {
                g.drawString(lab[i], 8, 72 + i * 14,
                        Graphics.TOP | Graphics.LEFT);
            }

            // Chat area (no progress bar: the conversation IS the UI).
            int ay = 104;
            int aBottom = h - 8;
            renderChat(w - 16);
            String[] lines = renderLines;
            if (lines.length == 0) {
                g.setColor(Ui.DIM);
                g.setFont(Ui.small());
                g.drawString("Record a voice note", 8, ay,
                        Graphics.TOP | Graphics.LEFT);
                g.drawString("or type a command.", 8, ay + 14,
                        Graphics.TOP | Graphics.LEFT);
            } else {
                int lineH = Ui.small().getHeight() + 1;
                int maxVis = (aBottom - ay) / lineH;
                if (maxVis < 1) maxVis = 1;
                int maxScroll = lines.length - maxVis;
                if (maxScroll < 0) maxScroll = 0;
                if (stickToBottom) scroll = maxScroll;
                if (scroll > maxScroll) scroll = maxScroll;
                if (scroll < 0) scroll = 0;
                g.setFont(Ui.small());
                for (int i = 0; i < maxVis && scroll + i < lines.length; i++) {
                    char kind = renderKinds[scroll + i];
                    if (kind == 'Y') g.setColor(Ui.ACCENT);
                    else if (kind == 'T' || kind == 'S') g.setColor(Ui.DIM);
                    else g.setColor(Ui.TEXT);
                    g.drawString(lines[scroll + i], 8, ay + i * lineH,
                            Graphics.TOP | Graphics.LEFT);
                }
                if (maxScroll > 0 && !stickToBottom) {
                    g.setColor(Ui.DIM);
                    g.setFont(Ui.small());
                    String pos = (scroll + 1) + "/" + lines.length;
                    g.drawString(pos, w - 8, ay - 2,
                            Graphics.TOP | Graphics.RIGHT);
                }
            }
        }

        protected void keyPressed(int keyCode) {
            int action = getGameAction(keyCode);
            int lineH = Ui.small().getHeight() + 1;
            int maxVis = (getHeight() - 8 - 104) / lineH;
            if (maxVis < 1) maxVis = 1;
            if (action == UP) {
                stickToBottom = false;
                scroll--;
                if (scroll < 0) scroll = 0;
                repaint();
            } else if (action == DOWN) {
                renderChat(getWidth() - 16);
                int maxScroll = renderLines.length - maxVis;
                if (maxScroll < 0) maxScroll = 0;
                scroll++;
                if (scroll >= maxScroll) {
                    scroll = maxScroll;
                    stickToBottom = true;
                }
                repaint();
            } else if (action == FIRE) {
                display.setCurrent(menu);
            }
        }

        protected void keyRepeated(int keyCode) {
            int action = getGameAction(keyCode);
            if (action == UP || action == DOWN) keyPressed(keyCode);
        }
    }
}
