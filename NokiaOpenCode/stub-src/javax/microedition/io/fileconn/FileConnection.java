package javax.microedition.io.fileconn;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
public interface FileConnection extends javax.microedition.io.Connection {
    InputStream openInputStream() throws IOException;
    Enumeration list() throws IOException;
    Enumeration list(String filter, boolean includeHidden) throws IOException;
    boolean isDirectory() throws IOException;
    boolean exists() throws IOException;
    String getPath() throws IOException;
    String getName() throws IOException;
}
