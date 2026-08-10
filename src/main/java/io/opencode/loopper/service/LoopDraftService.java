package io.opencode.loopper.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.opencode.loopper.domain.LoopDraftStatus;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.verification.ProcessCommandPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoopDraftService {
    private static final Set<String> SUPPORTED_VERIFIERS = Set.of(
            "PROCESS", "FILE_EXISTS", "FILE_NOT_EXISTS", "GIT_DIFF",
            "HTTP_STATUS", "JSON_PATH", "FILE_CONTENT", "FILE_HASH",
            "JUNIT_XML", "BROWSER", "DATABASE_QUERY");
    private static final Set<String> BROWSER_ASSERTIONS = Set.of(
            "EXISTS", "VISIBLE", "TEXT_CONTAINS", "COUNT", "ATTRIBUTE_EQUALS");
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ProjectService projects;
    private final ObjectMapper json;
    private final TaskService tasks;
    private final Validator validator;
    public LoopDraftService(LoopperMapper mapper, LifecycleTransitionService lifecycle,
                            ProjectService projects, ObjectMapper json, TaskService tasks,
                            Validator validator) {
        this.mapper = mapper; this.lifecycle = lifecycle; this.projects = projects;
        this.json = json; this.tasks = tasks; this.validator = validator;
    }
    @Transactional
    public LoopDraftRow create(LoopSpec spec) {
        validate(spec);
        projects.get(spec.projectId());
        String now = Instant.now().toString();
        LoopDraftRow row = new LoopDraftRow(UUID.randomUUID().toString(), spec.projectId(), spec.goal(), write(spec),
                LoopDraftStatus.DRAFT_READY.name(), now, now, 0);
        lifecycle.create(subject(row), row.status(), java.util.Map.of(), () -> mapper.insertDraft(row),
                () -> new ConflictException("DRAFT_CREATE_CONFLICT", "Loop draft could not be created"));
        return row;
    }
    public LoopDraftRow get(String id) { return mapper.findDraft(id).orElseThrow(() -> new NotFoundException("Loop draft not found: " + id)); }
    @Transactional
    public LoopDraftRow update(String id, LoopSpec spec) {
        validateExecutionContract(spec);
        LoopDraftRow old = get(id);
        if (LoopDraftStatus.CONFIRMED.name().equals(old.status())) throw new ConflictException("DRAFT_CONFIRMED", "Confirmed LoopSpec is immutable; create a new draft");
        if (!old.projectId().equals(spec.projectId())) throw new BadRequestException("DRAFT_PROJECT_MISMATCH", "LoopSpec projectId cannot be changed");
        LoopDraftRow changed = new LoopDraftRow(old.id(), old.projectId(), spec.goal(), write(spec), LoopDraftStatus.DRAFT_READY.name(), old.createdAt(), Instant.now().toString(), old.version());
        if (old.status().equals(changed.status())) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateDraftContent(changed),
                    () -> new ConflictException("DRAFT_VERSION_CONFLICT", "Loop draft was updated concurrently"));
        } else {
            lifecycle.transition(subject(changed), old.status(), changed.status(), null, java.util.Map.of(),
                    () -> mapper.updateDraft(changed),
                    () -> new ConflictException("DRAFT_VERSION_CONFLICT", "Loop draft was updated concurrently"));
        }
        return get(id);
    }
    @Transactional
    public io.opencode.loopper.persistence.TaskRow confirm(String id, String title) {
        return confirm(id, title, "MANUAL");
    }
    @Transactional
    public io.opencode.loopper.persistence.TaskRow confirm(String id, String title, String admissionSource) {
        return confirm(id, title, admissionSource, null);
    }
    @Transactional
    public io.opencode.loopper.persistence.TaskRow confirmAtBaseline(String id, String title, String admissionSource,
                                                                     String isolatedBaseline) {
        if (isolatedBaseline == null || isolatedBaseline.isBlank()) {
            throw new BadRequestException("REWORK_BASELINE_MISSING", "Rework requires the parent task baseline commit");
        }
        return confirm(id, title, admissionSource, isolatedBaseline);
    }
    private io.opencode.loopper.persistence.TaskRow confirm(String id, String title, String admissionSource,
                                                             String isolatedBaseline) {
        LoopDraftRow draft = get(id);
        if (LoopDraftStatus.CONFIRMED.name().equals(draft.status())) return mapper.findTaskByDraft(id).orElseThrow(() -> new ConflictException("DRAFT_TASK_MISSING", "Confirmed draft has no associated task"));
        validateExecutionContract(spec(draft));
        io.opencode.loopper.persistence.TaskRow task = tasks.createFromDraft(draft, title, admissionSource, isolatedBaseline);
        LoopDraftRow confirmed = new LoopDraftRow(draft.id(), draft.projectId(), draft.goal(), draft.specJson(), LoopDraftStatus.CONFIRMED.name(), draft.createdAt(), Instant.now().toString(), draft.version());
        lifecycle.transition(subject(confirmed), draft.status(), confirmed.status(), null, java.util.Map.of(),
                () -> mapper.updateDraft(confirmed),
                () -> new ConflictException("DRAFT_VERSION_CONFLICT", "Loop draft was updated concurrently"));
        return task;
    }
    private LifecycleTransitionService.Subject subject(LoopDraftRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.LOOP_DRAFT, row.id(),
                LifecycleScopeType.PROJECT, row.projectId());
    }
    public LoopSpec spec(LoopDraftRow row) {
        try { return json.readValue(row.specJson(), LoopSpec.class); }
        catch (JacksonException e) { throw new BadRequestException("LOOPSPEC_INVALID", "Stored LoopSpec cannot be read: " + e.getMessage()); }
    }
    private String write(LoopSpec spec) {
        try { return json.writeValueAsString(normalizeProcessCommands(spec)); }
        catch (JacksonException e) { throw new BadRequestException("LOOPSPEC_INVALID", e.getMessage()); }
    }

    public void validate(LoopSpec spec) {
        reject(validationErrors(spec, false));
    }

    /** Confirmation-grade validation: prose acceptance must have a machine-enforced counterpart. */
    public void validateExecutionContract(LoopSpec spec) {
        reject(validationErrors(spec, true));
    }

    public List<String> validationErrors(LoopSpec spec, boolean requireExecutableAcceptance) {
        if (spec == null) throw new BadRequestException("LOOPSPEC_REQUIRED", "LoopSpec is required");
        List<String> errors = new ArrayList<>(validator.validate(spec).stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage()).sorted().toList());
        if (!"v1".equals(spec.schemaVersion())) errors.add("schemaVersion: only v1 is supported");
        for (int stageIndex = 0; stageIndex < spec.stages().size(); stageIndex++) {
            LoopSpec.StageSpec stage = spec.stages().get(stageIndex);
            boolean hasAcceptanceVerifier = false;
            for (int verifierIndex = 0; verifierIndex < stage.verifiers().size(); verifierIndex++) {
                LoopSpec.VerifierSpec verifier = stage.verifiers().get(verifierIndex);
                String path = "stages[" + stageIndex + "].verifiers[" + verifierIndex + "]";
                String type = verifier.type() == null ? "" : verifier.type();
                if (!SUPPORTED_VERIFIERS.contains(type)) errors.add(path + ".type: unsupported verifier " + type);
                if (!"GIT_DIFF".equals(type)) hasAcceptanceVerifier = true;
                if ("PROCESS".equals(type) && verifier.command().isEmpty()) {
                    errors.add(path + ".command: PROCESS requires a direct argv command");
                }
                if ("PROCESS".equals(type)) {
                    ProcessCommandPolicy.Normalization normalization =
                            ProcessCommandPolicy.normalizeMavenCommand(verifier.command());
                    if (normalization.failure() != null) {
                        errors.add(path + ".command[" + normalization.failure().index() + "]: "
                                + normalization.failure().message());
                    }
                }
                if (requireExecutableAcceptance && "PROCESS".equals(type) && !verifier.command().isEmpty()
                        && "./mvnw".equals(verifier.command().getFirst())) {
                    Path wrapper = Path.of(projects.get(spec.projectId()).rootPath()).resolve("mvnw");
                    if (!Files.isRegularFile(wrapper)) {
                        errors.add(path + ".command[0]: ./mvnw is not present in the registered project root; "
                                + "Maven Wrapper is optional, so use an evidenced repository command such as mvn instead");
                    } else if (!Files.isExecutable(wrapper)) {
                        errors.add(path + ".command[0]: ./mvnw exists but is not executable; preserve its executable bit "
                                + "or use another evidenced repository command such as mvn");
                    }
                }
                if (("FILE_EXISTS".equals(type) || "FILE_NOT_EXISTS".equals(type))
                        && (verifier.path() == null || verifier.path().isBlank())) {
                    errors.add(path + ".path: file verifier requires a relative path");
                }
                if (("FILE_CONTENT".equals(type) || "FILE_HASH".equals(type) || "JUNIT_XML".equals(type)
                        || "DATABASE_QUERY".equals(type)) && blank(verifier.path())) {
                    errors.add(path + ".path: " + type + " requires a relative path");
                }
                if (("HTTP_STATUS".equals(type) || "JSON_PATH".equals(type) || "BROWSER".equals(type))
                        && blank(verifier.url())) {
                    errors.add(path + ".url: " + type + " requires a loopback URL");
                }
                if ("HTTP_STATUS".equals(type) && verifier.expectedStatus() == null) {
                    errors.add(path + ".expectedStatus: HTTP_STATUS requires an expected status");
                }
                if ("JSON_PATH".equals(type) && blank(verifier.jsonPath())) {
                    errors.add(path + ".jsonPath: JSON_PATH requires a restricted JSON path");
                }
                if ("FILE_CONTENT".equals(type) && blank(verifier.expectedContent())) {
                    errors.add(path + ".expectedContent: FILE_CONTENT requires bounded expected content");
                }
                if ("FILE_HASH".equals(type) && blank(verifier.expectedSha256())) {
                    errors.add(path + ".expectedSha256: FILE_HASH requires SHA-256");
                }
                if ("DATABASE_QUERY".equals(type) && blank(verifier.sql())) {
                    errors.add(path + ".sql: DATABASE_QUERY requires one read-only SELECT/WITH statement");
                }
                if ("BROWSER".equals(type)) {
                    if (verifier.assertions().isEmpty()) errors.add(path + ".assertions: BROWSER requires at least one assertion");
                    for (int assertionIndex = 0; assertionIndex < verifier.assertions().size(); assertionIndex++) {
                        LoopSpec.BrowserAssertion assertion = verifier.assertions().get(assertionIndex);
                        if (assertion.type() != null && !BROWSER_ASSERTIONS.contains(assertion.type())) {
                            errors.add(path + ".assertions[" + assertionIndex + "].type: unsupported browser assertion " + assertion.type());
                        }
                    }
                }
                if (verifier.outputContains() != null && !"PROCESS".equals(type)) {
                    errors.add(path + ".outputContains: only PROCESS can assert command output");
                }
            }
            if (requireExecutableAcceptance && !hasAcceptanceVerifier) {
                errors.add("stages[" + stageIndex + "].verifiers: GIT_DIFF only checks change scope; add a functional verifier for the Designer acceptance criteria");
            }
        }
        return List.copyOf(errors);
    }

    private LoopSpec normalizeProcessCommands(LoopSpec spec) {
        boolean changed = false;
        List<LoopSpec.StageSpec> stages = new ArrayList<>();
        for (LoopSpec.StageSpec stage : spec.stages()) {
            List<LoopSpec.VerifierSpec> verifiers = new ArrayList<>();
            boolean stageChanged = false;
            for (LoopSpec.VerifierSpec verifier : stage.verifiers()) {
                ProcessCommandPolicy.Normalization normalization = "PROCESS".equals(verifier.type())
                        ? ProcessCommandPolicy.normalizeMavenCommand(verifier.command())
                        : new ProcessCommandPolicy.Normalization(verifier.command(), null, false);
                if (normalization.changed() && normalization.failure() == null) {
                    verifiers.add(withCommand(verifier, normalization.command()));
                    stageChanged = true;
                } else {
                    verifiers.add(verifier);
                }
            }
            if (stageChanged) {
                stages.add(new LoopSpec.StageSpec(stage.objective(), stage.allowedPaths(), stage.forbiddenPaths(),
                        stage.deliverables(), verifiers));
                changed = true;
            } else {
                stages.add(stage);
            }
        }
        if (!changed) return spec;
        return new LoopSpec(spec.schemaVersion(), spec.projectId(), spec.goal(), spec.context(), stages,
                spec.limits(), spec.model(), spec.sessionPolicy(), spec.nextAttemptPromptTemplate(), spec.budget());
    }

    private LoopSpec.VerifierSpec withCommand(LoopSpec.VerifierSpec verifier, List<String> command) {
        return new LoopSpec.VerifierSpec(verifier.type(), command, verifier.path(), verifier.requireChanges(),
                verifier.allowedPaths(), verifier.forbiddenPaths(), verifier.forbidDeletes(), verifier.outputContains(),
                verifier.url(), verifier.httpMethod(), verifier.expectedStatus(), verifier.jsonPath(),
                verifier.expectedValue(), verifier.matchMode(), verifier.expectedContent(), verifier.expectedSha256(),
                verifier.sql(), verifier.expectedRowCount(), verifier.assertions());
    }

    private void reject(List<String> errors) {
        if (!errors.isEmpty()) throw new BadRequestException("LOOPSPEC_INVALID", String.join("; ", errors));
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
