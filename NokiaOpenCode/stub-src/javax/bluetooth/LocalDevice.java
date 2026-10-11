package javax.bluetooth;
public class LocalDevice {
    public static LocalDevice getLocalDevice() throws BluetoothStateException { return new LocalDevice(); }
    public boolean setDiscoverable(int mode) throws BluetoothStateException { return true; }
    public DiscoveryAgent getDiscoveryAgent() { return new DiscoveryAgent(); }
}
