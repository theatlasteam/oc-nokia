package javax.microedition.media.control;
import java.io.*;
import javax.microedition.media.Control;
import javax.microedition.media.MediaException;
public interface RecordControl extends Control {
    void setRecordStream(OutputStream stream) throws IOException;
    void startRecord() throws MediaException;
    void commit() throws IOException;
}
