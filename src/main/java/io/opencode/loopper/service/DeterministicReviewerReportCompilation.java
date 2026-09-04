package io.opencode.loopper.service;

import static io.opencode.loopper.service.ReviewerReportCompilation.ProblemClass.MECHANICAL;
import static io.opencode.loopper.service.ReviewerReportCompilation.ProblemClass.SECURITY;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Strict REVIEWER_REPORT_V1 normalization, evidence binding, rendering and hashing core. */
public final class DeterministicReviewerReportCompilation implements ReviewerReportCompilation {
    private static final int MAX_REPORT_BYTES = 64 * 1024;
    private static final int MAX_FINDINGS = 128;
    private static final int MAX_PROBLEMS = 64;
    private static final List<String> SEVERITY_VALUES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

    private final ObjectMapper json;

    public DeterministicReviewerReportCompilation(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Result compile(Input input) {
        Candidate source = input.candidate();
        Problems problems = new Problems();
        Candidate candidate = normalize(source, problems);
        if (!problems.empty()) return rejected(problems.values());

        Map<String, SourceFile> manifest = new LinkedHashMap<>();
        for (SourceFile file : input.sourceFiles()) {
            if (file == null || file.path() == null || manifest.putIfAbsent(file.path(), file) != null) {
                return rejected(List.of(problem("REVIEWER_SOURCE_MANIFEST_INVALID", "/sourceFiles",
                        "冻结源码清单包含重复或无身份条目", List.of(), SECURITY)));
            }
        }

        List<Evidence> evidence = new ArrayList<>();
        for (int index = 0; index < candidate.findings().size(); index++) {
            Finding finding = candidate.findings().get(index);
            String pointer = "/findings/" + index;
            PathCheck path = path(finding.path());
            if (!path.accepted()) {
                problems.add(problem(path.security() ? "REVIEWER_EVIDENCE_PATH_UNSAFE" : "REVIEWER_EVIDENCE_PATH_INVALID",
                        pointer + "/path", path.detail(), List.of(), path.security() ? SECURITY : MECHANICAL));
                continue;
            }
            SourceFile file = manifest.get(path.canonical());
            if (file == null) {
                problems.add(problem("REVIEWER_EVIDENCE_PATH_MISSING", pointer + "/path",
                        "finding 必须引用冻结源码清单中的受管文件",
                        manifest.keySet().stream().limit(32).toList(), MECHANICAL));
                continue;
            }
            if (file.sizeBytes() < 0 || file.sizeBytes() > 16_000_000 || file.lineCount() < 1
                    || !isSha256(file.sha256())) {
                problems.add(problem("REVIEWER_SOURCE_MANIFEST_INVALID", pointer + "/path",
                        "冻结源码条目超限或摘要无效", List.of(), SECURITY));
                continue;
            }
            if (finding.line() < 1 || finding.line() > file.lineCount()) {
                problems.add(problem("REVIEWER_EVIDENCE_LINE_INVALID", pointer + "/line",
                        "finding 行号必须落在冻结源码文件范围内", List.of("1.." + file.lineCount()), MECHANICAL));
                continue;
            }
            evidence.add(new Evidence(path.canonical(), finding.line(), file.sha256()));
        }
        if (!problems.empty()) return rejected(problems.values());
        if (evidence.size() != candidate.findings().size()) {
            return rejected(List.of(problem("REVIEWER_EVIDENCE_CARDINALITY_INVALID", "/findings",
                    "每条 finding 必须绑定且只绑定一条冻结源码证据", List.of(), SECURITY)));
        }

        String markdown = render(candidate);
        int reportBytes = bytes(markdown);
        if (reportBytes < 1 || reportBytes > MAX_REPORT_BYTES) {
            return rejected(List.of(problem("REVIEWER_REPORT_CONTENT_INVALID", "/candidate",
                    "服务端渲染报告必须为 1-64 KiB Markdown", List.of(), MECHANICAL)));
        }
        String canonicalCandidate = write(candidate);
        String canonicalFindings = write(candidate.findings());
        String canonicalEvidence = write(evidence);
        String sourceHash = sha256(sourceIdentity(evidence));
        String contentHash = sha256(markdown);
        String canonicalResultHash = sha256(canonicalCandidate + "\n" + canonicalEvidence + "\n"
                + markdown + "\n" + sourceHash);
        return new Result(canonicalCandidate, canonicalFindings, markdown, evidence, contentHash, sourceHash,
                canonicalResultHash, List.of());
    }

    private Candidate normalize(Candidate source, Problems problems) {
        String title = text(source.title(), 200, true, "/title", "REVIEWER_TITLE_INVALID", problems);
        String summary = text(source.summary(), 8_000, false, "/summary", "REVIEWER_SUMMARY_INVALID", problems);
        List<Finding> findings = source.findings();
        if (findings.size() > MAX_FINDINGS) {
            problems.add(problem("REVIEWER_FINDINGS_SIZE_INVALID", "/findings",
                    "findings 最多包含 128 项", List.of("0..128"), MECHANICAL));
        }
        List<Finding> normalized = new ArrayList<>();
        for (int index = 0; index < findings.size() && index < MAX_FINDINGS; index++) {
            Finding finding = findings.get(index);
            String pointer = "/findings/" + index;
            if (finding == null) {
                problems.add(problem("REVIEWER_FINDING_INVALID", pointer,
                        "finding 必须是完整对象", List.of(), MECHANICAL));
                continue;
            }
            String severity = strip(finding.severity());
            if (!SEVERITY_VALUES.contains(severity)) {
                problems.add(problem("REVIEWER_SEVERITY_INVALID", pointer + "/severity",
                        "severity 必须来自闭集", SEVERITY_VALUES, MECHANICAL));
            }
            String findingTitle = text(finding.title(), 300, true, pointer + "/title",
                    "REVIEWER_FINDING_TITLE_INVALID", problems);
            String detail = text(finding.detail(), 4_000, false, pointer + "/detail",
                    "REVIEWER_FINDING_DETAIL_INVALID", problems);
            String path = text(finding.path(), 1_024, true, pointer + "/path",
                    "REVIEWER_EVIDENCE_PATH_INVALID", problems);
            String recommendation = text(finding.recommendation(), 4_000, false, pointer + "/recommendation",
                    "REVIEWER_RECOMMENDATION_INVALID", problems);
            if (finding.line() < 1 || finding.line() > 10_000_000) {
                problems.add(problem("REVIEWER_EVIDENCE_LINE_INVALID", pointer + "/line",
                        "line 必须是有界正整数", List.of(), MECHANICAL));
            }
            normalized.add(new Finding(severity, findingTitle, detail, path, finding.line(), recommendation));
        }
        List<String> limitations = source.limitations();
        if (limitations.size() > 32) {
            problems.add(problem("REVIEWER_LIMITATIONS_INVALID", "/limitations",
                    "limitations 最多 32 项", List.of(), MECHANICAL));
        }
        List<String> normalizedLimitations = new ArrayList<>();
        for (int index = 0; index < limitations.size() && index < 32; index++) {
            normalizedLimitations.add(text(limitations.get(index), 2_000, false,
                    "/limitations/" + index, "REVIEWER_LIMITATION_INVALID", problems));
        }
        return new Candidate(title, summary, normalized, normalizedLimitations);
    }

    private String render(Candidate candidate) {
        StringBuilder out = new StringBuilder("# ").append(heading(candidate.title())).append("\n\n")
                .append(candidate.summary()).append("\n\n## 已确认发现\n");
        if (candidate.findings().isEmpty()) out.append("\n无。\n");
        for (Finding finding : candidate.findings()) {
            out.append("\n### [").append(finding.severity()).append("] ").append(heading(finding.title()))
                    .append("\n\n证据：").append(code(finding.path() + ":" + finding.line())).append("\n\n")
                    .append(finding.detail()).append("\n\n建议：").append(finding.recommendation()).append("\n");
        }
        if (!candidate.limitations().isEmpty()) {
            out.append("\n## 限制\n");
            candidate.limitations().forEach(value -> out.append("\n- ").append(value));
            out.append("\n");
        }
        return out.toString();
    }

    private static PathCheck path(String value) {
        if (value == null || value.isBlank()) return PathCheck.mechanical("finding path 必须非空");
        if (value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0 || value.startsWith("./")
                || value.contains("//") || value.endsWith("/")) {
            return PathCheck.mechanical("finding path 必须是规范 POSIX 相对文件路径");
        }
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            return PathCheck.security("finding path 不得是绝对路径");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                return PathCheck.mechanical("finding path 必须是规范 POSIX 相对文件路径");
            }
            if ("..".equals(segment)) return PathCheck.security("finding path 不得包含路径穿越");
            if (".git".equals(segment) || ".env".equals(segment)
                    || (segment.startsWith(".env.") && !".env.example".equals(segment))) {
                return PathCheck.security("finding path 不得引用受保护的仓库或环境配置");
            }
            if (segment.chars().anyMatch(Character::isISOControl)) {
                return PathCheck.security("finding path 不得包含控制字符");
            }
        }
        return PathCheck.accepted(value);
    }

    private static String text(String value, int maxBytes, boolean singleLine, String pointer, String code,
                               Problems problems) {
        String normalized = strip(value);
        if (normalized == null || normalized.isBlank() || bytes(normalized) > maxBytes
                || (singleLine && (normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0))) {
            problems.add(problem(code, pointer, "字段必须非空、格式正确且不超过有界 UTF-8 长度",
                    List.of(), MECHANICAL));
        }
        return normalized;
    }

    private Result rejected(List<Problem> problems) {
        return new Result(null, null, null, List.of(), null, null, null, problems);
    }

    private static Problem problem(String code, String pointer, String detail, List<String> allowed,
                                   ProblemClass problemClass) {
        return new Problem(code, pointer, detail, allowed, problemClass);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static String sourceIdentity(List<Evidence> evidence) {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < evidence.size(); index++) {
            Evidence item = evidence.get(index);
            value.append(index).append(':').append(item.path()).append(':').append(item.line()).append(':')
                    .append(item.sha256()).append('\n');
        }
        return value.toString();
    }

    private static String code(String value) {
        int longest = 0;
        int current = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '`') longest = Math.max(longest, ++current);
            else current = 0;
        }
        String delimiter = "`".repeat(longest + 1);
        return delimiter + value + delimiter;
    }

    private static String heading(String value) {
        return value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
                .replace("#", "\\#");
    }

    private static String strip(String value) { return value == null ? null : value.strip(); }
    private static int bytes(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }
    private static boolean isSha256(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private record PathCheck(boolean accepted, boolean security, String canonical, String detail) {
        private static PathCheck accepted(String canonical) { return new PathCheck(true, false, canonical, null); }
        private static PathCheck mechanical(String detail) { return new PathCheck(false, false, null, detail); }
        private static PathCheck security(String detail) { return new PathCheck(false, true, null, detail); }
    }

    private static final class Problems {
        private final List<Problem> values = new ArrayList<>();
        private void add(Problem problem) { if (values.size() < MAX_PROBLEMS) values.add(problem); }
        private boolean empty() { return values.isEmpty(); }
        private List<Problem> values() { return List.copyOf(values); }
    }
}
