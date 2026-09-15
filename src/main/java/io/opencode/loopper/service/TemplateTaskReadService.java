package io.opencode.loopper.service;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.TemplateTaskReadMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public final class TemplateTaskReadService {
    private final TemplateTaskReadMapper mapper;
    TemplateTaskReadService(TemplateTaskReadMapper mapper) { this.mapper = mapper; }
    public TemplateTaskReadMapper.ProjectChoice project(String id) {
        return mapper.project(id).orElseThrow(() -> new NotFoundException("项目不存在，请重新选择项目"));
    }

    public CursorPage<TemplateTaskReadMapper.ProjectChoice> projects(String query, String cursor, int limit) {
        var after = cursor(cursor); requireLimit(limit);
        if (query != null && query.length() > 128) throw new BadRequestException("TEMPLATE_SEARCH_TOO_LONG", "搜索词过长");
        var rows = mapper.projects(query == null ? "" : query.strip(), after[0], after[1], limit + 1);
        var page = rows.stream().limit(limit).toList();
        return new CursorPage<>(page, rows.size() > limit ? encode(page.getLast().createdAt(), page.getLast().id()) : null);
    }

    public CursorPage<TemplateTaskReadMapper.RunSummary> runs(String projectId, String cursor, int limit) {
        var after = cursor(cursor); requireLimit(limit);
        var rows = mapper.runs(projectId == null || projectId.isBlank() ? null : projectId, after[0], after[1], limit + 1);
        var page = rows.stream().limit(limit).toList();
        return new CursorPage<>(page, rows.size() > limit ? encode(page.getLast().createdAt(), page.getLast().id()) : null);
    }

    public CursorPage<TemplateTaskReadMapper.FailedBatch> failedBatches(String taskId, String cursor, int limit) {
        var after = cursor(cursor); requireLimit(limit);
        var rows = mapper.failedBatches(taskId, after[0], after[1], limit + 1);
        var page = rows.stream().limit(limit).toList();
        return new CursorPage<>(page, rows.size() > limit ? encode(page.getLast().createdAt(), page.getLast().id()) : null,
                java.util.Map.of("retrySelectionReady", mapper.retrySelectionReady(taskId) ? 1L : 0L));
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 100) throw new BadRequestException("PAGE_LIMIT_INVALID", "每页数量应为 1–100");
    }
    private static String encode(String time, String id) { return Base64.getUrlEncoder().withoutPadding().encodeToString((time + "\n" + id).getBytes(StandardCharsets.UTF_8)); }
    private static String[] cursor(String value) {
        if (value == null || value.isBlank()) return new String[]{null, null};
        try {
            if (value.length() > 512) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\n", -1);
            if (parts.length != 2 || parts[1].isBlank()) throw new IllegalArgumentException();
            Instant.parse(parts[0]); return parts;
        } catch (RuntimeException failure) { throw new BadRequestException("PAGE_CURSOR_INVALID", "分页位置无效，请刷新列表"); }
    }
}
