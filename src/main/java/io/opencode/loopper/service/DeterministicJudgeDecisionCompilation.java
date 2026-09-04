package io.opencode.loopper.service;

import static io.opencode.loopper.service.JudgeDecisionCompilation.ProblemClass.MECHANICAL;
import static io.opencode.loopper.service.JudgeDecisionCompilation.ProblemClass.SECURITY;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Closed Judge decision validation, evidence binding, rendering and hashing core. */
@Component
public final class DeterministicJudgeDecisionCompilation implements JudgeDecisionCompilation {
    private static final List<String> ROLES = List.of("REQUIREMENT", "RISK");
    private static final List<String> VERDICTS = List.of("PASS", "REVISE", "BLOCKED");
    private final JudgeDecisionCandidateCodec codec;

    public DeterministicJudgeDecisionCompilation(ObjectMapper json) {
        codec = new JudgeDecisionCandidateCodec(json);
    }

    @Override
    public Result compileCandidate(Input input, String candidateJson) {
        JudgeDecisionCandidateCodec.Decoded decoded = codec.decode(candidateJson);
        return decoded.valid() ? compile(input, decoded.candidate()) : rejected(decoded.problems());
    }

    @Override
    public Result compile(Input input, Candidate source) {
        Problems problems = new Problems();
        Map<String, EvidenceItem> evidence = validateCatalog(input.evidenceCatalog(), problems);
        if (!problems.empty()) return rejected(problems.values());
        if (input.role() == null || !ROLES.contains(input.role())) {
            problems.add(security("JUDGE_DECISION_INPUT_ROLE_INVALID", "/input/role",
                    "冻结 Judge role 不属于合同闭集"));
        }
        if (!problems.empty()) return rejected(problems.values());
        if (!CONTRACT_VERSION.equals(source.contractVersion())) {
            problems.add(security("JUDGE_DECISION_CONTRACT_VERSION_INVALID", "/contractVersion",
                    "Judge 候选合同版本漂移"));
        }
        if (!java.util.Objects.equals(input.role(), source.role())) {
            problems.add(security("JUDGE_DECISION_ROLE_MISMATCH", "/role",
                    "Judge 候选 role 必须等于冻结 owner role"));
        }
        if (source.verdict() == null || !VERDICTS.contains(source.verdict())) {
            problems.add(mechanical("JUDGE_DECISION_VERDICT_INVALID", "/verdict",
                    "verdict 必须来自闭集", VERDICTS));
        }
        String rawReason = source.reason();
        String reason = rawReason == null ? null : rawReason.strip();
        if (rawReason != null && rawReason.chars().anyMatch(value -> Character.isISOControl(value)
                && value != '\r' && value != '\n' && value != '\t')) {
            problems.add(security("JUDGE_DECISION_REASON_CONTROL_INVALID", "/reason",
                    "Judge reason 不得包含控制字符"));
        } else if (reason == null || reason.isBlank() || bytes(reason) > 4_000) {
            problems.add(mechanical("JUDGE_DECISION_REASON_INVALID", "/reason",
                    "reason 必须为 1..4000 UTF-8 字节", List.of()));
        } else if (rawReason.chars().anyMatch(value -> value == '\r' || value == '\n' || value == '\t')) {
            problems.add(mechanical("JUDGE_DECISION_REASON_LINE_BREAK_INVALID", "/reason",
                    "Judge reason 必须为不含 CR、LF 或 TAB 的单行文本；请将换行和制表符改为空格或分号，"
                            + "去掉标题和编号列表后重写完整 reason，不要原样重交；不要为通过格式校验而改变判定", List.of()));
        }
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        if (source.evidenceIds().isEmpty()) {
            problems.add(mechanical("JUDGE_DECISION_EVIDENCE_REQUIRED", "/evidenceIds",
                    "Judge 候选必须绑定至少一条冻结证据", List.copyOf(evidence.keySet())));
        }
        for (int index = 0; index < source.evidenceIds().size(); index++) {
            String id = source.evidenceIds().get(index);
            if (id == null || !evidence.containsKey(id) || !requested.add(id)) {
                problems.add(mechanical("JUDGE_DECISION_EVIDENCE_INVALID", "/evidenceIds/" + index,
                        "evidenceIds 必须唯一且全部命中冻结证据目录", List.copyOf(evidence.keySet())));
            }
        }
        if (!problems.empty()) return rejected(problems.values());

        List<String> evidenceIds = evidence.keySet().stream().filter(requested::contains).toList();
        Candidate candidate = new Candidate(CONTRACT_VERSION, input.role(), source.verdict(), reason, evidenceIds);
        List<EvidenceItem> selected = evidenceIds.stream().map(evidence::get).toList();
        String canonicalCandidate = codec.canonical(candidate);
        String canonicalEvidence = codec.canonical(selected);
        String deterministicReason = renderReason(reason, selected);
        String resultHash = sha256(canonicalCandidate + "\n" + deterministicReason + "\n" + canonicalEvidence);
        return new Result(candidate, canonicalCandidate, deterministicReason, selected,
                canonicalEvidence, resultHash, List.of());
    }

    private Map<String, EvidenceItem> validateCatalog(EvidenceCatalog catalog, Problems problems) {
        Map<String, EvidenceItem> evidence = new LinkedHashMap<>();
        if (!codec.validEvidenceCatalog(catalog)) {
            problems.add(security("JUDGE_DECISION_EVIDENCE_CATALOG_INVALID", "/evidenceCatalog",
                    "冻结 Judge 证据目录无效"));
            return evidence;
        }
        catalog.items().forEach(item -> evidence.put(item.id(), item));
        return evidence;
    }

    private static String renderReason(String reason, List<EvidenceItem> evidence) {
        StringBuilder value = new StringBuilder(reason).append("\n\n已验证证据：");
        evidence.forEach(item -> value.append("\n- [").append(item.kind()).append("] ").append(item.label()));
        return value.toString();
    }

    private Result rejected(List<Problem> problems) {
        return new Result(null, null, null, List.of(), null, null, List.copyOf(problems));
    }

    private static Problem mechanical(String code, String pointer, String detail, List<String> allowed) {
        return new Problem(code, pointer, detail, allowed, MECHANICAL);
    }

    private static Problem security(String code, String pointer, String detail) {
        return new Problem(code, pointer, detail, List.of(), SECURITY);
    }

    private static int bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Problems {
        private final List<Problem> values = new ArrayList<>();
        void add(Problem problem) { if (values.size() < 16) values.add(problem); }
        boolean empty() { return values.isEmpty(); }
        List<Problem> values() { return List.copyOf(values); }
    }
}
