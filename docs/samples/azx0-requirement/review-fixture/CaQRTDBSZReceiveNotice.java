import java.util.LinkedHashMap;
import java.util.Map;

/** Synthetic A38 receipt path. */
final class CaQRTDBSZReceiveNotice {
    Map<String, Object> BaseQRReceiveNotice(Map<String, Object> notice) {
        Map<String, Object> result = new LinkedHashMap<>(notice);
        Object comment = notice.get("payerComments");
        if (comment != null) result.put("RsrvFld1", comment);
        return result;
    }
}
