package javax.bluetooth;
public class DiscoveryAgent {
    public static final int GIAC = 0x9E8B33;
    public boolean startInquiry(int accessCode, DiscoveryListener listener) throws BluetoothStateException { return false; }
    public int searchServices(int[] attrSet, UUID[] uuidSet, RemoteDevice btDevice, DiscoveryListener listener) throws BluetoothStateException { return 0; }
}
