package javax.microedition.rms;
public class RecordStore {
    public static RecordStore openRecordStore(String name, boolean create) throws RecordStoreException { return new RecordStore(); }
    public static void deleteRecordStore(String name) throws RecordStoreException {}
    public int addRecord(byte[] data, int offset, int numBytes) throws RecordStoreException { return 1; }
    public byte[] getRecord(int recordId) throws RecordStoreException { return new byte[0]; }
    public void setRecord(int recordId, byte[] data, int offset, int numBytes) throws RecordStoreException {}
    public int getNumRecords() throws RecordStoreException { return 0; }
    public void closeRecordStore() throws RecordStoreException {}
}
