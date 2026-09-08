package io.opencode.loopper.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Pure source-based assessment. An enum or a model-authored alternative never proves a missing business choice. */
final class PackageDesignSourceGapAssessment {
    private static final Pattern UNDECIDED = Pattern.compile("尚未(?:决定|确定|确认)|尚待(?:业务)?决定|待(?:业务方|用户)确认|未定|not yet decided", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHOICE = Pattern.compile("还是|或(?:者)?|两种|选择|采用|whether|either", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOLVED = Pattern.compile("(?:已|已经)(?:决定|确定|确认)|不(?:再|存在).{0,8}(?:未定|待决)");

    List<PackageDesignCompilation.Problem> problems(PackageDesignCompilation.Input input, PackageDesignV2Document candidate) {
        var sources = PackageRequirementSources.index(input.requirementText());
        List<PackageDesignCompilation.Problem> problems = new ArrayList<>();
        // Only explicit unresolved alternative behavior in the frozen source is a proven user decision.
        for (var source : sources.values()) {
            if (input.confirmedDecisions().containsKey(source.ref()) && java.util.Arrays.stream(source.text().split("[。；;\\n]"))
                    .filter(sentence -> UNDECIDED.matcher(sentence).find() && CHOICE.matcher(sentence).find()).count() == 1) continue;
            for (String sentence : source.text().split("[。；;\\n]")) {
                if (UNDECIDED.matcher(sentence).find() && CHOICE.matcher(sentence).find()
                        && !RESOLVED.matcher(sentence).find()) {
                    var evidence = new PackageDesignGapAssessment.Evidence(PackageDesignGapAssessment.EvidenceKind.USER_DECISION,
                            List.of(source.ref()), sentence);
                    problems.add(problem("/gapClaims", new PackageDesignGapAssessment().assess("SOURCE_BUSINESS_CHOICE", List.of(evidence))));
                }
            }
        }
        if (!problems.isEmpty()) return List.copyOf(problems);
        for (int i = 0; i < candidate.gapClaims().size(); i++) {
            var claim = candidate.gapClaims().get(i);
            var assessment = new PackageDesignGapAssessment().assess(claim.code(), evidence(input, claim));
            problems.add(problem("/gapClaims/" + i, assessment));
        }
        if ("NEEDS_INPUT".equals(candidate.outcome()) && problems.isEmpty()) {
            problems.add(problem("/gapClaims", new PackageDesignGapAssessment().assess("UNSUPPORTED_GAP", List.of())));
        }
        return List.copyOf(problems);
    }

    private List<PackageDesignGapAssessment.Evidence> evidence(PackageDesignCompilation.Input input, PackageDesignV2Document.GapClaim claim) {
        var evidence = new ArrayList<PackageDesignGapAssessment.Evidence>();
        if (input.repositoryEvidence() != null) for (var file : input.repositoryEvidence().files()) {
            if (claim.sourceRefs().contains(file.sourceRef()) && !"READ".equals(file.status())) {
                evidence.add(new PackageDesignGapAssessment.Evidence(PackageDesignGapAssessment.EvidenceKind.REPOSITORY_UNKNOWN,
                        List.of(file.sourceRef()), "有界证据未能确认 " + file.path() + "；状态=" + file.status() + "，不能据此断言能力不存在"));
            }
        }
        if (evidence.isEmpty() && claim.code().contains("VERIFICATION")
                && input.role().testPolicy() == io.opencode.loopper.domain.TestPolicy.REQUIRED
                && input.requirementText().matches("(?s).*(?:新增|补充|建设).{0,80}测试.*")) {
            evidence.add(new PackageDesignGapAssessment.Evidence(PackageDesignGapAssessment.EvidenceKind.PLANNED_TEST,
                    claim.sourceRefs(), "冻结测试策略要求测试，原文明确要求建设测试；在既有受管范围与原生框架内规划交付，不能把尚未编写测试当作业务缺口"));
        }
        return List.copyOf(evidence);
    }

    private PackageDesignCompilation.Problem problem(String pointer, PackageDesignGapAssessment.Assessment assessment) {
        return new PackageDesignCompilation.Problem("PACKAGE_GAP_" + assessment.category(), pointer,
                assessment.detail(), assessment.sourceRefs(), assessment.retryable()
                ? PackageDesignCompilation.ProblemClass.CORRECTABLE : PackageDesignCompilation.ProblemClass.HUMAN_REQUIRED,
                false, "依据冻结来源区分可修复表达与真实业务待决", "来源=" + assessment.sourceRefs(),
                "处理动作=" + assessment.action() + "；" + assessment.detail()
                        + (assessment.retryable() ? "。补充已有事实或规划允许的测试，重提完整候选；不得自行决定未知业务行为" : "。通过本地反馈明确对应来源的选择，例如 REQ-L001=最终行为；系统保留原文并记录用户补充"));
    }
}
