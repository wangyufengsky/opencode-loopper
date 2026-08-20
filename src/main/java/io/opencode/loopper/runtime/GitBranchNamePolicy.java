package io.opencode.loopper.runtime;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/** Deterministic, UTF-8-safe Git branch naming with no repository I/O. */
final class GitBranchNamePolicy {
    private static final String NAMESPACE = "loopper/";
    private static final int MAX_LEAF_BYTES = 180;

    String branchName(String taskName, String taskId, int occurrence) {
        String suffix = occurrence == 1 ? "" : "(第" + occurrence + "次)";
        int suffixBytes = suffix.getBytes(StandardCharsets.UTF_8).length;
        String leaf = validTruncatedLeaf(normalizedLeaf(taskName, taskId), MAX_LEAF_BYTES - suffixBytes);
        return NAMESPACE + leaf + suffix;
    }

    private String validTruncatedLeaf(String value, int maxBytes) {
        String leaf = trimInvalidEnding(truncateUtf8(value, maxBytes));
        if (leaf.equals("@") || leaf.isBlank()) leaf = "task";
        if (leaf.endsWith(".lock")) leaf = leaf.substring(0, leaf.length() - 5) + "-lock";
        leaf = trimInvalidEnding(truncateUtf8(leaf, maxBytes));
        return leaf.equals("@") || leaf.isBlank() ? "task" : leaf;
    }

    private String normalizedLeaf(String taskName, String taskId) {
        String source = taskName == null || taskName.isBlank() ? "task-" + taskId : taskName.trim();
        source = Normalizer.normalize(source, Normalizer.Form.NFKC);
        StringBuilder normalized = new StringBuilder();
        source.codePoints().forEach(codePoint -> {
            if (codePoint <= 0x20 || codePoint == 0x7f || "~^:?*[\\/".indexOf(codePoint) >= 0) {
                normalized.append('-');
            } else {
                normalized.appendCodePoint(codePoint);
            }
        });
        String value = normalized.toString()
                .replace("@{", "-")
                .replaceAll("\\.{2,}", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[.-]+", "");
        value = trimInvalidEnding(value);
        if (value.equals("@") || value.isBlank()) value = "task-" + taskId;
        if (value.endsWith(".lock")) value += "-branch";
        return value;
    }

    private String truncateUtf8(String value, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + characterBytes > maxBytes) break;
            result.append(character);
            bytes += characterBytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private String trimInvalidEnding(String value) {
        int end = value.length();
        while (end > 0) {
            char last = value.charAt(end - 1);
            if (last != '.' && last != '-' && last != '/') break;
            end--;
        }
        return value.substring(0, end);
    }
}
