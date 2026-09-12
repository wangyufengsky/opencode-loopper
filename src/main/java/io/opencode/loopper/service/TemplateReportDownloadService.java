package io.opencode.loopper.service;

import io.opencode.loopper.persistence.TemplateReportBundleMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

/** Explicit bounded download of one immutable attempt; never reads source files or another task's artifacts. */
@Service
public final class TemplateReportDownloadService {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_FILES = 10_000;
    private final TemplateReportBundleMapper bundles;
    public TemplateReportDownloadService(TemplateReportBundleMapper bundles) { this.bundles = bundles; }

    public Download download(String taskId, String artifactId) {
        String attemptId = bundles.reportAttempt(taskId, artifactId).orElseThrow(() -> new NotFoundException("报告不存在"));
        var bundle = bundles.find(taskId, attemptId).orElseThrow(() -> new NotFoundException("旧版报告请使用单文件下载"));
        int count = bundles.reportCount(taskId, attemptId);
        if (count > MAX_FILES || bundles.reportBytes(taskId, attemptId) > MAX_BYTES) throw tooLarge();
        var reports = bundles.reports(taskId, attemptId);
        if (reports.size() != count || reports.stream().noneMatch(report -> report.name().equals(bundle.mainPath()))) {
            throw new ConflictException("TEMPLATE_REPORT_INCOMPLETE", "报告尚未完整生成，请稍后重试");
        }
        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            var paths = new HashSet<String>();
            long bytes = 0;
            for (var report : reports) {
                Path path = Path.of(report.name());
                if (path.isAbsolute() || !path.normalize().equals(path) || path.startsWith("..")
                        || report.name().contains("\\") || !paths.add(report.name())) {
                    throw new ConflictException("TEMPLATE_REPORT_PATH_INVALID", "报告路径无效，无法打包");
                }
                byte[] content = report.content().getBytes(StandardCharsets.UTF_8);
                bytes += content.length;
                if (bytes > MAX_BYTES) throw tooLarge();
                ZipEntry entry = new ZipEntry(bundle.folderName() + "/" + report.name());
                entry.setTime(0);
                zip.putNextEntry(entry); zip.write(content); zip.closeEntry();
            }
            zip.finish();
            return new Download(bundle.folderName() + ".zip", output.toByteArray());
        } catch (IOException failure) {
            throw new ConflictException("TEMPLATE_REPORT_DOWNLOAD_FAILED", "报告打包失败，请重试");
        }
    }
    private static ConflictException tooLarge() {
        return new ConflictException("TEMPLATE_REPORT_DOWNLOAD_TOO_LARGE", "报告超过整包下载上限（64 MiB 或 10000 个文件），请从报告保存目录读取");
    }
    public record Download(String filename, byte[] bytes) { }
}
