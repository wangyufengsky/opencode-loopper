package io.opencode.loopper.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.opencode.loopper.persistence.AnalysisReportRow;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.DesignerTaskProfileRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class AnalysisReportService {
    private static final Pattern LOCATION = Pattern.compile("(?m)(?<![A-Za-z0-9_./-])([A-Za-z0-9_./-]+\\.[A-Za-z0-9_-]{1,12}):(\\d{1,7})");
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final ObjectMapper json;

    public AnalysisReportService(LoopperMapper mapper, ProjectService projects, ObjectMapper json) {
        this.mapper = mapper; this.projects = projects; this.json = json;
    }

    /** Freezes the actual read-only Designer result as a report; it creates no Task or write lease. */
    public View generateFromDesignerSnapshot(String sessionId) {
        DesignerSessionRow session = session(sessionId);
        DesignerTaskProfileRow profile = mapper.findCurrentDesignerTaskProfile(sessionId)
                .orElseThrow(() -> new ConflictException("TASK_PROFILE_MISSING", "只读报告缺少冻结任务画像"));
        if (!"READ_ONLY_REPORT".equals(profile.executionStrategy()))
            throw new ConflictException("REPORT_PROFILE_INVALID", "当前任务画像不是只读报告流程");
        DesignerMessageRow source = mapper.listDesignerMessages(sessionId).stream()
                .filter(message -> "DESIGNER".equals(message.actor()) && "PERSISTED".equals(message.deliveryState()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new ConflictException("REPORT_CONTENT_MISSING", "只读评审器尚未形成可持久化报告"));
        Path root = Path.of(projects.get(session.projectId()).rootPath()).toAbsolutePath().normalize();
        List<Evidence> evidence = evidence(root, source.content());
        String now = Instant.now().toString();
        String contentHash = sha256(source.content().getBytes(StandardCharsets.UTF_8));
        String sourceHash = sha256(evidence.stream().map(item -> item.path() + ":" + item.sha256())
                .sorted().reduce("", (left, right) -> left + "\n" + right).getBytes(StandardCharsets.UTF_8));
        AnalysisReportRow row = new AnalysisReportRow(UUID.randomUUID().toString(), sessionId, profile.id(),
                "READY", reportTitle(source.content()), source.content(), write(evidence), contentHash, sourceHash,
                null, null, now, now, 0);
        if (mapper.insertAnalysisReport(row) != 1) throw new ConflictException("REPORT_CREATE_CONFLICT", "报告无法持久化");
        return view(session, row);
    }

    public View get(String sessionId, String reportId) {
        return view(session(sessionId), mapper.findAnalysisReport(sessionId, reportId)
                .orElseThrow(() -> new NotFoundException("Analysis report not found: " + reportId)));
    }
    public List<Summary> list(String sessionId) {
        DesignerSessionRow session = session(sessionId);
        return mapper.listAnalysisReports(sessionId).stream().map(row -> {
            View view = view(session, row);
            return new Summary(row.id(), row.state(), row.title(), row.contentSha256(), view.stale(), row.updatedAt());
        }).toList();
    }

    private View view(DesignerSessionRow session, AnalysisReportRow row) {
        List<Evidence> evidence = readEvidence(row.evidenceJson());
        Path root = Path.of(projects.get(session.projectId()).rootPath()).toAbsolutePath().normalize();
        List<EvidenceView> views = evidence.stream().map(item -> {
            boolean stale = true;
            try {
                Path file = safe(root, item.path());
                stale = !Files.isRegularFile(file) || !sha256(Files.readAllBytes(file)).equals(item.sha256());
            } catch (Exception ignored) { }
            return new EvidenceView(item.path(), item.line(), item.sha256(), stale);
        }).toList();
        return new View(row.id(), row.state(), row.title(), row.markdown(), row.contentSha256(),
                row.sourceSnapshotSha256(), views, views.stream().anyMatch(EvidenceView::stale),
                row.errorCode(), row.errorDetail(), row.createdAt(), row.updatedAt());
    }

    private List<Evidence> evidence(Path root, String markdown) {
        List<Evidence> result = new ArrayList<>(); Matcher matcher = LOCATION.matcher(markdown);
        while (matcher.find() && result.size() < 128) {
            try {
                String relative = matcher.group(1); Path file = safe(root, relative);
                if (!Files.isRegularFile(file) || Files.size(file) > 16_000_000) continue;
                int line = Integer.parseInt(matcher.group(2)); long lineCount;
                try (var lines = Files.lines(file, StandardCharsets.UTF_8)) { lineCount = lines.limit(1_000_001).count(); }
                if (line < 1 || line > lineCount) continue;
                result.add(new Evidence(relative.replace('\\','/'), line, sha256(Files.readAllBytes(file))));
            } catch (Exception ignored) { }
        }
        return List.copyOf(result);
    }
    private static Path safe(Path root, String relative) throws Exception {
        Path input = Path.of(relative); if (input.isAbsolute()) throw new IllegalArgumentException();
        Path file = root.resolve(input).normalize(); if (!file.startsWith(root) || Files.isSymbolicLink(file)) throw new IllegalArgumentException();
        Path parent = Files.exists(file) ? file.toRealPath() : file.getParent().toRealPath();
        if (!parent.startsWith(root.toRealPath())) throw new IllegalArgumentException(); return file;
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception failure) { throw new IllegalStateException(failure); } }
    private List<Evidence> readEvidence(String value) { try { return json.readValue(value, new TypeReference<>() { }); } catch (Exception ignored) { return List.of(); } }
    private DesignerSessionRow session(String id) { return mapper.findDesignerSession(id).orElseThrow(() -> new NotFoundException("Designer session not found: " + id)); }
    private static String reportTitle(String content) { return content.lines().map(String::strip).filter(line -> !line.isBlank()).findFirst().orElse("只读分析报告").replaceFirst("^#+\\s*", ""); }
    private static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception failure) { throw new IllegalStateException(failure); } }

    public record Evidence(String path, int line, String sha256) { }
    public record EvidenceView(String path, int line, String sha256, boolean stale) { }
    public record View(String id, String state, String title, String markdown, String contentSha256,
                       String sourceSnapshotSha256, List<EvidenceView> evidence, boolean stale,
                       String errorCode, String errorDetail, String createdAt, String updatedAt) { }
    public record Summary(String id, String state, String title, String contentSha256, boolean stale, String updatedAt) { }
}
