import java.util.LinkedHashMap;
import java.util.Map;

/** Synthetic public JSON unpacking boundary, represented by an already decoded map. */
final class SignUnPackMessageService {
    Map<String, Object> unpack(Map<String, Object> decodedJson) {
        return new LinkedHashMap<>(decodedJson);
    }
}
