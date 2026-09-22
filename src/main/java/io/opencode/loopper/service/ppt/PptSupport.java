package io.opencode.loopper.service.ppt;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class PptSupport {
    private PptSupport() { }
    public static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    public static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String digest(Object value,tools.jackson.databind.ObjectMapper json) {
        return hash(json.writeValueAsString(canonical(json.valueToTree(value))));
    }
    private static Object canonical(tools.jackson.databind.JsonNode node) {
        if(node.isObject()) {var result=new java.util.TreeMap<String,Object>();node.properties().forEach(e->result.put(e.getKey(),canonical(e.getValue())));return result;}
        if(node.isArray())return node.valueStream().map(PptSupport::canonical).toList();
        if(node.isNull())return null;if(node.isBoolean())return node.asBoolean();if(node.isNumber())return node.decimalValue().stripTrailingZeros();return node.asText();
    }
    public static void key(String key) {
        if (key == null || !key.matches("[a-zA-Z0-9_-]{8,128}")) throw bad("PPT_REQUEST_INVALID", "请求标识无效，请刷新后重试");
    }
    public static BadRequestException bad(String code,String message) { return new BadRequestException(code,message); }
    public static ConflictException conflict(String message) { return new ConflictException("PPT_VERSION_CONFLICT",message); }
}
