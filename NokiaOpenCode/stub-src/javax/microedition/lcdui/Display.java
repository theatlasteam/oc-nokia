package javax.microedition.lcdui;
import javax.microedition.midlet.MIDlet;
public class Display {
    public static Display getDisplay(MIDlet m) { return new Display(); }
    public void setCurrent(Displayable d) {}
    public void setCurrent(Alert a, Displayable d) {}
    public Displayable getCurrent() { return null; }
    public void callSerially(Runnable r) {}
    public boolean vibrate(int duration) { return false; }
    public void flashBacklight(int duration) {}
}
