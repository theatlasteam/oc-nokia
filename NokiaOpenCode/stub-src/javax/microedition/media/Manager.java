package javax.microedition.media;
import java.io.IOException;
import java.io.InputStream;
public class Manager {
    public static final int TONE_DEVICE_LOCATOR = 1;
    public static Player createPlayer(String locator) throws IOException, MediaException { return null; }
    public static Player createPlayer(InputStream stream, String type) throws IOException, MediaException { return null; }
    public static void playTone(int note, int duration, int volume) throws MediaException {}
}
