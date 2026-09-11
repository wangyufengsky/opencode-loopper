package io.opencode.loopper.service;

import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TemplateTaskMapper;
import io.opencode.loopper.template.ContributionScore;
import io.opencode.loopper.template.TemplateDateRange;
import io.opencode.loopper.template.TemplateTaskDefinition;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Public manual creation boundary. The immutable request key prevents double-clicks from creating extra runs. */
@Service
public class TemplateTaskService {
    private final ProjectBranchService branches;
    private final TemplateTaskContractFactory contracts;
    private final TemplateTaskAdmission admission;
    private final TemplateTaskMapper mapper;
    private final ObjectMapper json;

    public TemplateTaskService(ProjectBranchService branches, TemplateTaskContractFactory contracts,
                                  TemplateTaskAdmission admission, TemplateTaskMapper mapper, ObjectMapper json) {
        this.branches = branches; this.contracts = contracts; this.admission = admission; this.mapper = mapper; this.json = json;
    }

    public Catalog catalog() {
        var dates = TemplateDateRange.parse(null, null, Clock.systemUTC());
        return new Catalog(Arrays.stream(TemplateTaskDefinition.values()).map(TemplateTaskDefinition::view).toList(),
                TemplateDateRange.ZONE.getId(), dates.startDate().toString(), dates.endDate().toString(),
                ContributionScore.VERSION, ContributionScore.FORMULA, ContributionScore.DIMENSIONS);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TaskRow create(Request request, boolean bypassCache) {
        if (request == null || request.requestKey() == null || !request.requestKey().matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BadRequestException("TEMPLATE_REQUEST_KEY_REQUIRED", "发起标识无效，请刷新后重试");
        }
        String digest = TemplateGitEvidenceCollector.hash(json.writeValueAsString(request) + ":" + bypassCache);
        var existing = mapper.findRequest(request.requestKey()).orElse(null);
        if (existing != null) return admission.requireSameRequest(existing, digest);
        TemplateTaskDefinition definition;
        TemplateDateRange dates;
        try {
            definition = TemplateTaskDefinition.valueOf(request.templateId());
            dates = TemplateDateRange.parse(request.startDate(), request.endDate(), Clock.systemUTC());
        } catch (RuntimeException invalid) {
            throw new BadRequestException("TEMPLATE_PARAMETERS_INVALID", "请选择有效模板和日期范围，结束日期不能早于开始日期");
        }
        if (!TemplateTaskDefinition.VERSION.equals(request.templateVersion())) {
            throw new ConflictException("TEMPLATE_VERSION_CHANGED", "模板已更新，请刷新页面后重新发起");
        }
        StoryBindingConfiguration story = request.story() == null ? StoryBindingConfiguration.disabled() : request.story().normalized();
        var branch = branches.require(request.projectId(), request.branchId());
        var frozen = contracts.freeze(definition, request.projectId(), dates);
        return admission.create(new TemplateTaskAdmission.Command(request.requestKey(), digest, branch, dates, frozen, story, bypassCache));
    }

    public record Request(String requestKey, String templateId, String templateVersion, String projectId,
                           String branchId, String startDate, String endDate, StoryBindingConfiguration story) { }
    public record Catalog(List<TemplateTaskDefinition.View> templates, String timezone, String defaultStartDate,
                           String defaultEndDate, String scoringVersion, String scoreFormula, List<ContributionScore.Dimension> dimensions) { }
}
