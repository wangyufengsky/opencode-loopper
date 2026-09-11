package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.StageKind;
import io.opencode.loopper.template.ContributionScore;
import io.opencode.loopper.template.TemplateDateRange;
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
        LoopSpec.Limits limits = new LoopSpec.Limits(properties.getMaxStageAttempts(), properties.getMaxTaskAttempts(),
                properties.getSessionErrorLimit(), 2, properties.getMaxDuration().toSeconds(),
                properties.getAttemptTimeout().toSeconds(), properties.getVerifierTimeout().toSeconds());
        String title = definition.title() + " · " + range.startDate() + " 至 " + range.endDate();
        String context = "使用任务独立的 Git 快照，按北京时间和 committer 时间完整覆盖选定范围；"
                + "程序采集证据，模型提交结构化分析，程序验证并渲染 Markdown。"
                + "评审对象是报告的证据、覆盖和评分准确性；发现代码问题或低贡献得分不代表报告执行失败。"
                + "禁止修改项目文件、分支或推送；无提交时输出空范围说明。";
        LoopSpec spec = new LoopSpec("v2", projectId, title, context,
                List.of(stage("冻结分支并采集完整 Git 证据", "SNAPSHOT"), stage("分析证据并生成可追溯报告", "REPORT")),
                limits, model(), new LoopSpec.SessionPolicy(false, true), "按原合同修复本轮报告的具体问题", LoopSpec.BudgetSpec.unlimited());
        return new Frozen(definition.view(), spec, ContributionScore.VERSION, ContributionScore.FORMULA,
                ContributionScore.DIMENSIONS, TemplateDateRange.ZONE.getId(), "COMMITTER_TIME", 2, TemplateReportLayout.freeze());
    }

    private LoopSpec.ModelSpec model() {
        String configured = properties.getOpenCode().getModel();
        int slash = configured == null ? -1 : configured.indexOf('/');
        if (slash <= 0 || slash == configured.length() - 1) {
            throw new BadRequestException("TEMPLATE_MODEL_REQUIRED", "请先在设置中选择模型，再发起模板任务");
        }
        return new LoopSpec.ModelSpec(configured.substring(0, slash).strip(), configured.substring(slash + 1).strip(), null);
    }

    private static LoopSpec.StageSpec stage(String title, String criterion) {
        return new LoopSpec.StageSpec(title, List.of("reports/**"), List.of("repository.git/**"),
                List.of(criterion.equals("SNAPSHOT") ? "冻结 Git 证据" : "Markdown 报告"), List.of(),
                List.of(new LoopSpec.AcceptanceCriterion(criterion, title, "JUDGE",
                        "全部提交均有证据记录；问题和贡献等级有对应提交与文件依据；报告覆盖完整且事实准确。"
                                + "报告发现缺陷或给出低分不构成验收失败。", "由模板专用确定性验证和独立双评审联合验证")),
                null, ImplementationKind.NON_JAVA, null, StageKind.READ_ONLY_ANALYSIS, ExecutionStrategy.READ_ONLY_REPORT, null);
    }

    public record Frozen(TemplateTaskDefinition.View definition, LoopSpec spec, String scoringVersion, String scoreFormula,
                          List<ContributionScore.Dimension> dimensions, String timezone, String timePolicy, int repairLimit,
                          TemplateReportLayout.Frozen reportTemplates) { }
}
