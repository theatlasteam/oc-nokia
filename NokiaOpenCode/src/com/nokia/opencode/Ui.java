package com.nokia.opencode;

import java.util.Vector;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Dark OpenCode theme + tiny drawing helpers for the Canvas UI.
 * CLDC 1.1 / Java 1.3 compatible (no generics, no StringBuilder).
 */
public final class Ui {

    private Ui() {}

    public static final int BG = 0x101014;
    public static final int PANEL = 0x1B1E26;
    public static final int ROW_SEL = 0x232A3A;
    public static final int ACCENT = 0x5AA9FF;
    public static final int TEXT = 0xF2F4F8;
    public static final int DIM = 0x9AA0AA;
    public static final int OK = 0x52D273;
    public static final int ERR = 0xFF6B6B;
    public static final int REC = 0xFF5252;
    public static final int TRACK = 0x2A2F3A;

    private static final Font F_SMALL =
        Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private static final Font F_MED =
        Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_MEDIUM);
    private static final Font F_MED_BOLD =
        Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
    private static final Font F_LARGE_BOLD =
        Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE);

    public static Font small() { return F_SMALL; }
    public static Font med() { return F_MED; }
    public static Font medBold() { return F_MED_BOLD; }
    public static Font largeBold() { return F_LARGE_BOLD; }

    /** Greedy word wrap. Returns array of lines fitting width. */
    public static String[] wrap(String text, Font f, int width) {
        Vector lines = new Vector();
        if (text == null || text.length() == 0) {
            return new String[0];
        }
        int start = 0;
        int len = text.length();
        while (start < len) {
            int nl = text.indexOf('\n', start);
            String para;
            if (nl < 0) {
                para = text.substring(start);
                start = len;
            } else {
                para = text.substring(start, nl);
                start = nl + 1;
            }
            if (para.length() == 0) {
                lines.addElement("");
                continue;
            }
            while (para.length() > 0) {
                if (f.stringWidth(para) <= width) {
                    lines.addElement(para);
                    break;
                }
                int cut = -1;
                int from = 0;
                while (true) {
                    int sp = para.indexOf(' ', from);
                    if (sp < 0) break;
                    String head = para.substring(0, sp);
                    if (f.stringWidth(head) <= width) {
                        cut = sp;
                        from = sp + 1;
                    } else {
                        break;
                    }
                }
                if (cut < 0) {
                    // Single long word: hard cut by width.
                    int n = 1;
                    while (n < para.length()
                            && f.stringWidth(para.substring(0, n + 1)) <= width) {
                        n++;
                    }
                    lines.addElement(para.substring(0, n));
                    para = para.substring(n);
                } else {
                    lines.addElement(para.substring(0, cut));
                    para = para.substring(cut + 1);
                }
            }
        }
        String[] out = new String[lines.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (String) lines.elementAt(i);
        }
        return out;
    }

    /** Truncate with "..." to fit width. */
    public static String ellipsize(String s, Font f, int width) {
        if (s == null) return "";
        if (f.stringWidth(s) <= width) return s;
        while (s.length() > 0 && f.stringWidth(s + "...") > width) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "...";
    }

    /** 30px header strip: small logo left, title, right status text. */
    public static void header(Graphics g, int w, Image logoSmall,
            String title, String right) {
        g.setColor(PANEL);
        g.fillRect(0, 0, w, 30);
        g.setColor(ACCENT);
        g.fillRect(0, 30, w, 2);
        int tx = 8;
        if (logoSmall != null && logoSmall.getWidth() + 16 < w) {
            g.drawImage(logoSmall, 6, (30 - logoSmall.getHeight()) / 2,
                    Graphics.TOP | Graphics.LEFT);
            tx = 6 + logoSmall.getWidth() + 8;
        } else {
            g.setColor(TEXT);
            g.setFont(F_MED_BOLD);
            g.drawString(title, tx, 6, Graphics.TOP | Graphics.LEFT);
            tx += F_MED_BOLD.stringWidth(title) + 8;
        }
        if (right != null) {
            g.setColor(DIM);
            g.setFont(F_SMALL);
            g.drawString(right, w - 6, 8,
                    Graphics.TOP | Graphics.RIGHT);
        }
    }

    /** Rounded progress bar with a moving highlight while active. */
    public static void bar(Graphics g, int x, int y, int w, int h,
            int pct, int phase, boolean active) {
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        g.setColor(TRACK);
        g.fillRoundRect(x, y, w, h, h, h);
        int fw = (w * pct) / 100;
        if (fw > 0) {
            g.setColor(ACCENT);
            g.fillRoundRect(x, y, fw, h, h, h);
            // Square off the right edge unless complete.
            if (pct < 100 && fw > h) {
                g.fillRect(x + fw - h, y, h, h);
            }
            if (active && fw > 6) {
                // Moving highlight stripes clipped to the fill.
                g.setColor(0x8FC2FF);
                int off = (phase * 7) % 16;
                for (int sx = x + 2 - 16 + off; sx < x + fw - 2; sx += 16) {
                    g.drawLine(sx, y + 2, sx + 5, y + h - 3);
                    g.drawLine(sx + 1, y + 2, sx + 6, y + h - 3);
                }
            }
        }
    }

    /** Small status dot. */
    public static void dot(Graphics g, int color, int cx, int cy, int r) {
        g.setColor(color);
        g.fillArc(cx - r, cy - r, r * 2, r * 2, 0, 360);
    }
}
