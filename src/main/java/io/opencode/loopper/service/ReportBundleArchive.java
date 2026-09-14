package io.opencode.loopper.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.*;

/** Shared deterministic, bounded UTF-8 archive transport for both generations of template reports. */
public final class ReportBundleArchive {
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    public static final int MAX_FILES = 10000;
    private ReportBundleArchive() { }
    public static byte[] zip(String directory, List<Entry> entries) {
        safe(directory);
        if (entries.size() > MAX_FILES) throw tooLarge();
        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            Set<String> seen = new HashSet<>(); long total = 0;
            for (var item : entries) {
                safe(item.name());
                if (!seen.add(item.name())) throw invalid();
                byte[] content = item.content().getBytes(StandardCharsets.UTF_8); total += content.length;
                if (total > MAX_BYTES) throw tooLarge();
                var entry = new ZipEntry(directory + "/" + item.name()); entry.setTime(0);
                zip.putNextEntry(entry); zip.write(content); zip.closeEntry();
            }
            zip.finish(); return output.toByteArray();
        } catch (IOException failure) { throw new ConflictException("TEMPLATE_REPORT_DOWNLOAD_FAILED", "报告打包失败，请重试"); }
    }
    private static void safe(String value) {
        if (value == null || value.isBlank() || value.contains("\\") || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        Path path = Path.of(value);
        if (path.isAbsolute() || !path.normalize().equals(path) || path.startsWith("..")) throw invalid();
    }
    private static ConflictException invalid() { return new ConflictException("TEMPLATE_REPORT_PATH_INVALID", "报告路径无效，无法打包"); }
    private static ConflictException tooLarge() { return new ConflictException("TEMPLATE_REPORT_DOWNLOAD_TOO_LARGE", "报告超过整包下载上限（64 MiB 或 10000 个文件），请使用分项报告"); }
    public record Entry(String name, String content) { }
}
