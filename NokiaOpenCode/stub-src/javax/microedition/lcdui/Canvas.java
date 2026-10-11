package javax.microedition.lcdui;
public abstract class Canvas extends Displayable {
    public static final int UP = 1, DOWN = 6, LEFT = 2, RIGHT = 5, FIRE = 8;
    public static final int KEY_NUM0 = 48, KEY_NUM1 = 49, KEY_NUM2 = 50;
    public static final int KEY_NUM3 = 51, KEY_NUM4 = 52, KEY_NUM5 = 53;
    public static final int KEY_NUM6 = 54, KEY_NUM7 = 55, KEY_NUM8 = 56, KEY_NUM9 = 57;
    public static final int KEY_STAR = 42, KEY_POUND = 35;
    protected Canvas() {}
    public int getWidth() { return 240; }
    public int getHeight() { return 320; }
    public void repaint() {}
    public void serviceRepaints() {}
    public void setFullScreenMode(boolean mode) {}
    protected abstract void paint(Graphics g);
    protected void keyPressed(int keyCode) {}
    protected void keyReleased(int keyCode) {}
    protected void keyRepeated(int keyCode) {}
    protected void showNotify() {}
    protected void hideNotify() {}
    public int getGameAction(int keyCode) { return 0; }
}
