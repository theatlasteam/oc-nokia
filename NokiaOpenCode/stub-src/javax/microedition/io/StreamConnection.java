package javax.microedition.io;
import java.io.*;
public interface StreamConnection extends Connection {
    InputStream openInputStream() throws IOException;
    OutputStream openOutputStream() throws IOException;
}
