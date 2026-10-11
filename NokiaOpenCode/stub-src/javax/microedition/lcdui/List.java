package javax.microedition.lcdui;
public class List extends Screen {
    public static final Command SELECT_COMMAND = new Command("", Command.SCREEN, 0);
    public static final int IMPLICIT = 1, EXCLUSIVE = 2, MULTIPLE = 3;
    public List(String title, int type) {}
    public int append(String s, Image i) { return 0; }
    public int getSelectedIndex() { return 0; }
}
