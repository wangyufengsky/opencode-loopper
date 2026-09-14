import java.util.Map;

/** Synthetic caller: JSON serialization remains an external boundary with no implementation in this fixture. */
final class ReceiptFlow {
    Map<String, Object> receive(String businessCode, Map<String, Object> decodedJson) {
        Map<String, Object> notice = new SignUnPackMessageService().unpack(decodedJson);
        return "A38".equals(businessCode) ? new CaQRTDBSZReceiveNotice().BaseQRReceiveNotice(notice) : notice;
    }
}
