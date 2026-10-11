package javax.microedition.lcdui;
public class Graphics {
    public static final int HCENTER = 1, VCENTER = 2, LEFT = 4, RIGHT = 8;
    public static final int TOP = 16, BOTTOM = 32, BASELINE = 64;
    public static final int SOLID = 0, DOTTED = 1;
    public void setColor(int RGB) {}
    public void setColor(int red, int green, int blue) {}
    public int getColor() { return 0; }
    public void setFont(Font font) {}
    public Font getFont() { return null; }
    public void drawString(String str, int x, int y, int anchor) {}
    public void drawSubstring(String str, int offset, int len, int x, int y, int anchor) {}
    public void drawLine(int x1, int y1, int x2, int y2) {}
    public void fillRect(int x, int y, int width, int height) {}
    public void drawRect(int x, int y, int width, int height) {}
    public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {}
    public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {}
    public void drawImage(Image img, int x, int y, int anchor) {}
    public void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle) {}
    public void translate(int x, int y) {}
    public void setClip(int x, int y, int width, int height) {}
    public int getClipX() { return 0; }
    public int getClipY() { return 0; }
    public int getClipWidth() { return 240; }
    public int getClipHeight() { return 320; }
}
