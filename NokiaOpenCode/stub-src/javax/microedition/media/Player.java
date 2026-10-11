package javax.microedition.media;
import java.io.IOException;
public interface Player {
    void realize() throws MediaException;
    void start() throws MediaException;
    void stop() throws MediaException;
    void close();
    Control getControl(String controlType);
    void setLoopCount(int count) throws MediaException;
}
