package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.DocumentTemplateState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.persistence.DocumentTemplateMapper;
import io.opencode.loopper.persistence.DocumentTemplateRunRow;
import io.opencode.loopper.template.DocumentTemplateDefinition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Upload request orchestration; neither source writes nor model calls occur at admission. */
@Service
public class DocumentTemplateService {
    private final DocumentTemplateMapper mapper;
    private final DocumentTemplateAdmission admission;
    private final DocumentTemplateStorage storage;
    private final ProjectService projects;
    private final ProjectBranchService branches;
    private final LoopperProperties properties;
    private final ObjectMapper json;
    private final DocumentTemplatePreparation preparation;
    private final ConcurrentHashMap<String, Boolean> uploads = new ConcurrentHashMap<>();

    public DocumentTemplateService(DocumentTemplateMapper mapper, DocumentTemplateAdmission admission,
            DocumentTemplateStorage storage, ProjectService projects, ProjectBranchService branches,
            LoopperProperties properties, ObjectMapper json, DocumentTemplatePreparation preparation) {
        this.mapper = mapper; this.admission = admission; this.storage = storage; this.projects = projects;
        this.branches = branches; this.properties = properties; this.json = json; this.preparation = preparation;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DocumentTemplateRunRow create(Request request, List<DocumentTemplateStorage.Incoming> incoming) {
        validate(request);
        if (uploads.putIfAbsent(request.requestKey(), true) != null)
            throw new ConflictException("DOCUMENT_TEMPLATE_UPLOAD_BUSY", "本次上传正在处理，请稍后重试同一次请求");
        try { return createOnce(request, incoming); }
        finally { uploads.remove(request.requestKey()); }
    }

    private DocumentTemplateRunRow createOnce(Request request, List<DocumentTemplateStorage.Incoming> incoming) {
        var prepared = storage.prepare(incoming);
        String digest = hash(json.writeValueAsString(Map.of("request", request, "files", prepared.stream()
                .map(file -> Map.of("filename", file.filename(), "sha256", file.sha256())).toList())));
        var row = mapper.findRequest(request.requestKey()).orElse(null);
        if (row != null) row = DocumentTemplateAdmission.sameRequest(row, digest);
        else {
            var definition = DocumentTemplateDefinition.valueOf(request.templateId());
            var project = projects.get(request.projectId());
            String branch = definition.review() ? json.writeValueAsString(branches.require(project.id(), request.branchId())) : null;
            String model = properties.getOpenCode().getModel();
            if (model == null || model.indexOf('/') <= 0 || model.endsWith("/"))
                throw new BadRequestException("TEMPLATE_MODEL_REQUIRED", "请先在设置中选择模型，再发起模板任务");
            String contract = json.writeValueAsString(new Contract(request.templateVersion(), model,
                    properties.getMaxDuration().toSeconds(), properties.getAttemptTimeout().toSeconds(),
                    properties.getMaxTaskAttempts(), properties.getMaxStageAttempts(), properties.getSessionErrorLimit(),
                    !definition.review(), definition.review() ? "STATIC_ONLY" : "CURRENT_DIRECTORY", properties.isTimeoutEnabled()));
            String now = Instant.now().toString();
            var proposed = new DocumentTemplateRunRow(UUID.randomUUID().toString(), request.requestKey(), digest,
                    project.id(), definition.name(), request.templateVersion(), definition.title() + " · " + project.name(),
                    DocumentTemplateState.PREPARING.name(), null, branch, null, contract, null, null,
                    0, null, null, 0, now, now, 0);
            try { row = admission.create(proposed, prepared); }
            catch (DuplicateKeyException race) {
                row = DocumentTemplateAdmission.sameRequest(mapper.findRequest(request.requestKey()).orElseThrow(() -> race), digest);
            }
        }
        return preparation.finish(row, prepared);
    }

    private static void validate(Request request) {
        if (request == null || request.requestKey() == null || !request.requestKey().matches("[A-Za-z0-9_-]{16,100}")
                || request.projectId() == null || request.projectId().isBlank() || request.projectId().length() > 128
                || request.branchId() != null && request.branchId().length() > 2048)
            throw new BadRequestException("DOCUMENT_TEMPLATE_PARAMETERS", "请选择项目并使用有效的发起标识");
        DocumentTemplateDefinition definition;
        try { definition = DocumentTemplateDefinition.valueOf(request.templateId()); }
        catch (RuntimeException invalid) { throw new BadRequestException("DOCUMENT_TEMPLATE_REQUIRED", "请选择需求开发或需求代码评审模板"); }
        if (!DocumentTemplateDefinition.VERSION.equals(request.templateVersion()))
            throw new ConflictException("TEMPLATE_VERSION_CHANGED", "模板已更新，请刷新后重新发起");
        if (definition.review() && (request.branchId() == null || request.branchId().isBlank()))
            throw new BadRequestException("DOCUMENT_TEMPLATE_BRANCH_REQUIRED", "请选择要评审的分支");
        if (!definition.review() && request.branchId() != null)
            throw new BadRequestException("DOCUMENT_TEMPLATE_BRANCH_UNEXPECTED", "需求开发使用项目当前目录，请勿传入评审分支");
    }

    private static String hash(String value) { return DocumentTemplateStorage.hash(value.getBytes(StandardCharsets.UTF_8)); }
    public record Request(String requestKey, String templateId, String templateVersion, String projectId, String branchId) { }
    public record Contract(String version, String model, long maxDurationSeconds, long attemptTimeoutSeconds,
            int maxTaskAttempts, int maxStageAttempts, int sessionErrorLimit, boolean autoDevelopment, String executionPolicy, Boolean timeoutEnabled) {
        public Contract { timeoutEnabled = timeoutEnabled == null ? true : timeoutEnabled; }
        public Contract(String version,String model,long total,long attempt,int tasks,int stages,int errors,boolean auto,String policy) {
            this(version,model,total,attempt,tasks,stages,errors,auto,policy,true);
        }
    }
}
