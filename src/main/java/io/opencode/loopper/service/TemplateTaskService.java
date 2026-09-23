package io.opencode.loopper.service;

import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TemplateTaskMapper;
import io.opencode.loopper.template.ContributionScore;
import io.opencode.loopper.template.TemplateDateRange;
import io.opencode.loopper.template.SnapshotReview;
import io.opencode.loopper.template.TemplateTaskDefinition;
import io.opencode.loopper.template.TemplateCatalogEntry;
import io.opencode.loopper.template.DocumentTemplateDefinition;
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
    private final ProjectService projects;
    private final TemplateDocumentPaths documentPaths;

    public TemplateTaskService(ProjectBranchService branches, TemplateTaskContractFactory contracts,
                                  TemplateTaskAdmission admission, TemplateTaskMapper mapper, ObjectMapper json,
                                  ProjectService projects, TemplateDocumentPaths documentPaths) {
        this.projects = projects; this.documentPaths = documentPaths;
        this.branches = branches; this.contracts = contracts; this.admission = admission; this.mapper = mapper; this.json = json;
    }

    public Catalog catalog() {
        var dates = TemplateDateRange.parse(null, null, Clock.systemUTC());
        var existing = java.util.stream.Stream.concat(
                Arrays.stream(DocumentTemplateDefinition.values()).map(TemplateCatalogEntry::document),
                Arrays.stream(TemplateTaskDefinition.values()).map(TemplateCatalogEntry::report));
        var entries = java.util.stream.Stream.concat(existing,
                Arrays.stream(io.opencode.loopper.template.SourceTemplateDefinition.values())
                        .map(io.opencode.loopper.template.SourceTemplateDefinition::view)).toList();
        return new Catalog(entries,
                TemplateDateRange.ZONE.getId(), dates.startDate().toString(), dates.endDate().toString(),
                ContributionScore.VERSION, ContributionScore.FORMULA, ContributionScore.DIMENSIONS);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TaskRow create(Request request, boolean bypassCache) {
        if (request == null || request.requestKey() == null || !request.requestKey().matches("[A-Za-z0-9_-]{16,100}")) {
            throw new BadRequestException("TEMPLATE_REQUEST_KEY_REQUIRED", "发起标识无效，请刷新后重试");
        }
        var requestJson = (tools.jackson.databind.node.ObjectNode) json.valueToTree(request);
        if (request.reviewMode() == null) requestJson.remove("reviewMode");
        if (request.documentPath() == null) requestJson.remove("documentPath");
        String digest = TemplateGitEvidenceCollector.hash(json.writeValueAsString(requestJson) + ":" + bypassCache);
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
        if (!definition.view().version().equals(request.templateVersion())) {
            throw new ConflictException("TEMPLATE_VERSION_CHANGED", "模板已更新，请刷新页面后重新发起");
        }
        if (request.story() != null && request.story().enabled()) {
            throw new BadRequestException("TEMPLATE_STORY_ACCOUNTING_DISABLED", "模板任务暂不支持故事统计，请刷新页面后重新发起");
        }
        var branch = branches.require(request.projectId(), request.branchId());
        var project = projects.get(request.projectId());
        String outputPath = documentPaths.resolve(project.rootPath(), request.documentPath() == null || request.documentPath().isBlank()
                ? project.documentPath() : request.documentPath());
        TemplateTaskContractFactory.Frozen frozen;
        if (definition == TemplateTaskDefinition.SNAPSHOT_CODE_REVIEW) {
            SnapshotReview.Mode mode;
            try { mode = SnapshotReview.Mode.valueOf(request.reviewMode() == null ? "DATE_INCREMENTAL" : request.reviewMode()); }
            catch (IllegalArgumentException invalid) { throw new BadRequestException("SNAPSHOT_MODE_INVALID", "请选择日期增量或全面审查"); }
            if (mode == SnapshotReview.Mode.FULL && (request.startDate() != null || request.endDate() != null))
                throw new BadRequestException("SNAPSHOT_DATES_UNEXPECTED", "全面审查不接受日期范围");
            if (mode == SnapshotReview.Mode.DATE_INCREMENTAL && (request.startDate() == null || request.endDate() == null))
                throw new BadRequestException("SNAPSHOT_DATES_REQUIRED", "日期增量审查需要开始日期和结束日期");
            frozen = contracts.freezeSnapshot(request.projectId(), dates, outputPath, mode);
        } else {
            if (request.reviewMode() != null) throw new BadRequestException("SNAPSHOT_MODE_UNEXPECTED", "该模板不接受代码审查模式");
            frozen = contracts.freeze(definition, request.projectId(), dates, outputPath);
        }
        return admission.create(new TemplateTaskAdmission.Command(request.requestKey(), digest, branch, dates, frozen, bypassCache));
    }

    /** Story remains decodable for legacy request digests; new tasks cannot enable it. */
    public record Request(String requestKey, String templateId, String templateVersion, String projectId,
                           String branchId, String startDate, String endDate, StoryBindingConfiguration story, String documentPath, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String reviewMode) {
        public Request(String requestKey, String templateId, String templateVersion, String projectId,
                       String branchId, String startDate, String endDate, StoryBindingConfiguration story, String documentPath) {
            this(requestKey, templateId, templateVersion, projectId, branchId, startDate, endDate, story, documentPath, null);
        }
        public Request(String requestKey, String templateId, String templateVersion, String projectId,
                       String branchId, String startDate, String endDate, StoryBindingConfiguration story) {
            this(requestKey, templateId, templateVersion, projectId, branchId, startDate, endDate, story, null);
        }
    }
    public record Catalog(List<TemplateCatalogEntry> templates, String timezone, String defaultStartDate,
                           String defaultEndDate, String scoringVersion, String scoreFormula, List<ContributionScore.Dimension> dimensions) { }
}
