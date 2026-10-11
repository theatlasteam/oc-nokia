package javax.microedition.io;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
public interface HttpConnection extends StreamConnection {
    public static final String POST = "POST";
    public static final String GET = "GET";
    public static final int HTTP_OK = 200;
    void setRequestMethod(String method) throws IOException;
    void setRequestProperty(String key, String value) throws IOException;
    int getResponseCode() throws IOException;
    InputStream openInputStream() throws IOException;
    OutputStream openOutputStream() throws IOException;
}
