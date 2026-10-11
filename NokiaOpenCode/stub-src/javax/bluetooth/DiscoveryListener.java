package javax.bluetooth;
public interface DiscoveryListener {
    int INQUIRY_COMPLETED = 0;
    void deviceDiscovered(RemoteDevice btDevice, DeviceClass cod);
    void inquiryCompleted(int discType);
    void servicesDiscovered(int transID, ServiceRecord[] servRecord);
    void serviceSearchCompleted(int transID, int respCode);
}
