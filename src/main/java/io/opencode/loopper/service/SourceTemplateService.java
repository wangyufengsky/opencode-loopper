package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Resolve input and freeze confirmation parameters without starting a model, Task, queue or lease. */
@Service
public class SourceTemplateService {
    private final ProjectService projects;
    private final SourceTemplateMapper mapper;
    private final SourceTemplateAdmission admission;
    private final SourceTreeCapture capture;
    private final TemplateDocumentPaths documentPaths;
    private final LoopperProperties properties;
    private final ObjectMapper json;
    private final SourceTestProfileService testProfiles;
    public SourceTemplateService(ProjectService projects, SourceTemplateMapper mapper, SourceTemplateAdmission admission,
            SourceTreeCapture capture, TemplateDocumentPaths documentPaths, LoopperProperties properties, ObjectMapper json, SourceTestProfileService testProfiles) {
        this.projects = projects; this.mapper = mapper; this.admission = admission; this.capture = capture;
        this.documentPaths = documentPaths; this.properties = properties; this.json = json; this.testProfiles = testProfiles;
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Preview preview(SourceTemplateRequests.Create input) {
        var definition = validate(input, false);
        var parameters = parameters(input, definition);
        var captured = capture.capture(parameters, definition.development()); var manifest = captured.manifest();
        var targets = manifest.files().stream().filter(SourceManifest.File::target).toList();
        SourceTestProfile profile = null; String configurationProblem = null;
        if (definition.development()) {
            try { profile = testProfiles.preview(parameters, captured); }
            catch (BadRequestException unavailable) { configurationProblem = unavailable.getMessage(); }
        }
        long modules = profile == null ? targets.stream().filter(SourceManifest.File::processable)
                .map(f -> Objects.toString(Path.of(f.path()).getParent(), ".")).distinct().count() : profile.modules().size();
        return new Preview(parameters.sourcePath(), parameters.testOutputPath(), parameters.documentPath(),
                manifest.sha256(), manifest.targetCount(), targets.stream().filter(file -> file.exclusion() != null).count(),
                targets.stream().limit(100).toList(), targets.size() > 100, modules, profile, configurationProblem);
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SourceTemplateRunRow create(SourceTemplateRequests.Create input) {
        var definition = validate(input, true);
        String digest = SourceTreeCapture.hash(json.writeValueAsBytes(input));
        var previous = mapper.request(input.requestKey()).orElse(null);
        if (previous != null) return SourceTemplateAdmission.same(previous, digest);
        if (!definition.version().equals(input.templateVersion()))
            throw new ConflictException("TEMPLATE_VERSION_CHANGED", "模板已更新，请刷新后重新发起");
        var parameters = parameters(input, definition);
        String model = properties.getOpenCode().getModel();
        if (model == null || model.indexOf('/') <= 0 || model.endsWith("/"))
            throw new BadRequestException("TEMPLATE_MODEL_REQUIRED", "请先在设置中选择模型，再发起模板任务");
        var contract = new SourceTemplateContract(definition.version(), model, properties.isTimeoutEnabled(),
                properties.getMaxDuration().toSeconds(), properties.getAttemptTimeout().toSeconds(),
                properties.getMaxTaskAttempts(), properties.getMaxStageAttempts(), properties.getSessionErrorLimit(),
                definition.development() ? 1 : properties.getTemplateAnalysisConcurrency());
        String now = Instant.now().toString();
        var row = new SourceTemplateRunRow(UUID.randomUUID().toString(), input.requestKey(), digest, input.projectId(),
                definition.name(), definition.version(), definition.title() + " · " + projects.get(input.projectId()).name(),
                "PENDING_START", null, json.writeValueAsString(parameters), json.writeValueAsString(contract),
                null, null, null, null, null, null, 0, now, now, 0);
        try { return admission.create(row); }
        catch (DuplicateKeyException race) {
            return SourceTemplateAdmission.same(mapper.request(input.requestKey()).orElseThrow(() -> race), digest);
        }
    }
    private SourceTemplateParameters parameters(SourceTemplateRequests.Create input, SourceTemplateDefinition definition) {
        var project = projects.get(input.projectId());
        Path root = SourcePathPolicy.root(project.rootPath());
        String source = SourcePathPolicy.relative(root, SourcePathPolicy.resolve(root, input.sourcePath(), true));
        String output = null;
        if (definition.development() && input.testOutputPath() != null && !input.testOutputPath().isBlank()) {
            output = SourcePathPolicy.relative(root, SourcePathPolicy.resolve(root, input.testOutputPath(), false));
            if (output.equals(".") || SourcePathPolicy.exclusion(output, true) != null)
                throw new BadRequestException("SOURCE_TEST_OUTPUT_INVALID", "请选择项目内独立的测试目录");
        }
        String documents = definition.development() ? null : documentPaths.resolve(root.toString(),
                input.documentPath() == null || input.documentPath().isBlank() ? project.documentPath() : input.documentPath());
        return new SourceTemplateParameters(root.toString(), source, output, documents,
                input.requirements() == null ? "" : input.requirements().strip());
    }
    private static SourceTemplateDefinition validate(SourceTemplateRequests.Create input, boolean create) {
        if (input == null || input.projectId() == null || input.projectId().isBlank()
                || input.projectId().length() > 128 || input.requirements() != null && input.requirements().length() > 8000)
            throw new BadRequestException("SOURCE_PARAMETERS_INVALID", "请选择项目，补充要求最多 8000 字符");
        if (create && (input.requestKey() == null || !input.requestKey().matches("[A-Za-z0-9_-]{16,100}")))
            throw new BadRequestException("SOURCE_REQUEST_KEY_REQUIRED", "发起标识无效，请刷新后重试");
        SourceTemplateDefinition definition;
        try { definition = SourceTemplateDefinition.require(input.templateId()); }
        catch (RuntimeException invalid) {
            throw new BadRequestException("SOURCE_TEMPLATE_REQUIRED", "请选择单元测试开发或详细设计编写模板");
        }
        if (definition.development() && input.documentPath() != null && !input.documentPath().isBlank()
                || !definition.development() && input.testOutputPath() != null && !input.testOutputPath().isBlank())
            throw new BadRequestException("SOURCE_OUTPUT_UNEXPECTED", "输出目录与所选模板不匹配，请重新选择");
        return definition;
    }
    public record Preview(String sourcePath, String testOutputPath, String documentPath, String manifestSha256,
                          long targetCount, long excludedCount, List<SourceManifest.File> files, boolean truncated,
                          long moduleCount, SourceTestProfile testProfile, String configurationProblem) { }
}
