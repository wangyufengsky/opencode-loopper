package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.StageKind;
import io.opencode.loopper.template.ContributionScore;
import io.opencode.loopper.template.TemplateDateRange;
import io.opencode.loopper.template.SnapshotReview;
import io.opencode.loopper.template.TemplateTaskDefinition;
import io.opencode.loopper.template.TemplateReportLayout;
import java.util.List;
import org.springframework.stereotype.Component;

/** Parameter instantiation of a built-in contract; no Designer, Router or executable user JSON. */
@Component
public final class TemplateTaskContractFactory {
    private final LoopperProperties properties;

    public TemplateTaskContractFactory(LoopperProperties properties) { this.properties = properties; }

    public Frozen freeze(TemplateTaskDefinition definition, String projectId, TemplateDateRange range) {
        return freeze(definition, projectId, range, null);
    }

    public Frozen freeze(TemplateTaskDefinition definition, String projectId, TemplateDateRange range, String documentPath) {
        LoopSpec.Limits limits = new LoopSpec.Limits(properties.getMaxStageAttempts(), properties.getMaxTaskAttempts(),
                properties.getSessionErrorLimit(), 2, properties.getMaxDuration().toSeconds(),
                properties.getAttemptTimeout().toSeconds(), properties.getVerifierTimeout().toSeconds(), properties.isTimeoutEnabled());
        String title = definition.title() + " · " + range.startDate() + " 至 " + range.endDate();
        String context = "使用任务独立的 Git 快照，按北京时间和 committer 时间完整覆盖选定范围；"
                + "程序采集证据，模型提交结构化分析，程序验证并渲染 Markdown。"
                + "完整分析通过程序校验并保存报告后完成任务，不启动独立 AI 双评审；发现代码问题或低贡献得分不代表报告执行失败。"
                + "禁止修改项目文件、分支或推送；无提交时输出空范围说明。";
        LoopSpec spec = new LoopSpec("v2", projectId, title, context,
                List.of(stage("冻结分支并采集完整 Git 证据", "SNAPSHOT"), stage("分析证据并生成可追溯报告", "REPORT")),
                limits, model(), new LoopSpec.SessionPolicy(false, true), "按原合同修复本轮报告的具体问题", LoopSpec.BudgetSpec.unlimited());
        return new Frozen(definition.view(), spec, ContributionScore.VERSION, ContributionScore.FORMULA,
                ContributionScore.DIMENSIONS, TemplateDateRange.ZONE.getId(), "COMMITTER_TIME", 2, definition == TemplateTaskDefinition.CODE_REVIEW ? TemplateReportLayout.freezeHistory() : TemplateReportLayout.freeze(), documentPath, properties.getTemplateAnalysisConcurrency(), null, TemplateBatchFailurePolicy.MAX_RETRIES);
    }

    public Frozen freezeSnapshot(String projectId, TemplateDateRange range, String documentPath, SnapshotReview.Mode mode) {
        var base = freeze(TemplateTaskDefinition.SNAPSHOT_CODE_REVIEW, projectId, range, documentPath);
        var spec = base.spec();
        var next = new LoopSpec(spec.schemaVersion(), projectId,
                "代码审查 · " + (mode == SnapshotReview.Mode.FULL ? "全面审查" : range.startDate() + " 至 " + range.endDate()),
                "冻结目标代码，仅通过受限 MCP 阅读；程序按容量分批，一轮分析，仅复核候选问题，不递归补充或运行项目测试。",
                List.of(snapshotStage("冻结代码证据", "SNAPSHOT"), snapshotStage("程序安排分析批次", "PLAN"),
                        snapshotStage("轻量分析目标版本", "ANALYSIS"), snapshotStage("问题复核与生成报告", "REPORT")),
                spec.limits(), spec.model(), spec.sessionPolicy(), spec.nextAttemptPromptTemplate(), spec.budget());
        return new Frozen(base.definition(), next, null, null, List.of(),
                base.timezone(), "FIRST_PARENT_COMMITTER_TIME", base.repairLimit(), base.reportTemplates(), documentPath,
                properties.getTemplateAnalysisConcurrency(), mode.name(), base.batchMaxRetries());
    }

    private LoopSpec.ModelSpec model() {
        String configured = properties.getOpenCode().getModel();
        int slash = configured == null ? -1 : configured.indexOf('/');
        if (slash <= 0 || slash == configured.length() - 1) {
            throw new BadRequestException("TEMPLATE_MODEL_REQUIRED", "请先在设置中选择模型，再发起模板任务");
        }
        return new LoopSpec.ModelSpec(configured.substring(0, slash).strip(), configured.substring(slash + 1).strip(), null);
    }

    private static LoopSpec.StageSpec snapshotStage(String title, String criterion) {
        return new LoopSpec.StageSpec(title, List.of("reports/**"), List.of("repository.git/**"),
                List.of(title), List.of(), List.of(new LoopSpec.AcceptanceCriterion(criterion,
                "冻结版本不漂移；可读片段完整归属且排除项明确记录；候选问题独立复核，无问题结论不二次审查；证据校验、全部会话停止证明和报告保存通过。",
                "MACHINE", null, null)), null, ImplementationKind.NON_JAVA, null, StageKind.READ_ONLY_ANALYSIS, ExecutionStrategy.READ_ONLY_REPORT, null);
    }
    private static LoopSpec.StageSpec stage(String title, String criterion) {
        return new LoopSpec.StageSpec(title, List.of("reports/**"), List.of("repository.git/**"),
                List.of(criterion.equals("SNAPSHOT") ? "冻结 Git 证据" : "Markdown 报告"), List.of(),
                List.of(new LoopSpec.AcceptanceCriterion(criterion,
                        "全部提交均有证据记录；分析候选通过覆盖、引用与归属校验；评分按冻结公式计算；报告完整保存。",
                        "MACHINE", null, null)),
                null, ImplementationKind.NON_JAVA, null, StageKind.READ_ONLY_ANALYSIS, ExecutionStrategy.READ_ONLY_REPORT, null);
    }

    public record Frozen(TemplateTaskDefinition.View definition, LoopSpec spec, String scoringVersion, String scoreFormula,
                          List<ContributionScore.Dimension> dimensions, String timezone, String timePolicy, int repairLimit,
                          TemplateReportLayout.Frozen reportTemplates, String documentPath, Integer analysisConcurrency, String reviewMode,
                          Integer batchMaxRetries) {
        public Frozen {
            analysisConcurrency = (!SnapshotReview.applies(definition.id()) && !List.of("8", "9", "10").contains(definition.version())) || analysisConcurrency == null ? 1 : analysisConcurrency;
            batchMaxRetries = batchMaxRetries == null ? 0 : batchMaxRetries;
            if (batchMaxRetries < 0 || batchMaxRetries > 3) throw new IllegalArgumentException("批次自动重试次数无效");
        }
        public Frozen(TemplateTaskDefinition.View definition, LoopSpec spec, String scoringVersion, String scoreFormula,
                      List<ContributionScore.Dimension> dimensions, String timezone, String timePolicy, int repairLimit,
                      TemplateReportLayout.Frozen reportTemplates, String documentPath, Integer analysisConcurrency, String reviewMode) {
            this(definition, spec, scoringVersion, scoreFormula, dimensions, timezone, timePolicy, repairLimit,
                    reportTemplates, documentPath, analysisConcurrency, reviewMode, null);
        }
        public Frozen(TemplateTaskDefinition.View definition, LoopSpec spec, String scoringVersion, String scoreFormula,
                      List<ContributionScore.Dimension> scoringDimensions, String timezone, String timePolicy, int repairLimit,
                      TemplateReportLayout.Frozen reportTemplates, String documentPath, Integer analysisConcurrency) {
            this(definition, spec, scoringVersion, scoreFormula, scoringDimensions, timezone, timePolicy, repairLimit, reportTemplates, documentPath, analysisConcurrency, null);
        }
        public Frozen(TemplateTaskDefinition.View definition, LoopSpec spec, String scoringVersion, String scoreFormula,
                List<ContributionScore.Dimension> dimensions, String timezone, String timePolicy, int repairLimit,
                TemplateReportLayout.Frozen reportTemplates, String documentPath) {
            this(definition, spec, scoringVersion, scoreFormula, dimensions, timezone, timePolicy, repairLimit, reportTemplates, documentPath, 1);
        }
        public boolean requiresDualReview() { return !SnapshotReview.applies(definition.id()) && TemplateTaskDefinition.requiresDualReview(definition.version()); }
    }
}
