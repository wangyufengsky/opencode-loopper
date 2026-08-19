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
import io.opencode.loopper.verification.VerifierPathPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
            "JUNIT_XML", "BROWSER", "DATABASE_QUERY", "DOCUMENT_STRUCTURE", "TABULAR_DATA");
    private static final Set<String> BROWSER_ASSERTIONS = Set.of(
            "EXISTS", "VISIBLE", "TEXT_CONTAINS", "COUNT", "ATTRIBUTE_EQUALS");
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ProjectService projects;
    private final ObjectMapper json;
    private final TaskService tasks;
    private final Validator validator;
    private final LoopSpecAcceptanceService acceptance;
    public LoopDraftService(LoopperMapper mapper, LifecycleTransitionService lifecycle,
                            ProjectService projects, ObjectMapper json, TaskService tasks,
                            Validator validator, LoopSpecAcceptanceService acceptance) {
        this.mapper = mapper; this.lifecycle = lifecycle; this.projects = projects;
        this.json = json; this.tasks = tasks; this.validator = validator; this.acceptance = acceptance;
    }
    @Transactional
    public LoopDraftRow create(LoopSpec spec) {
        reject(assessment(spec, false, true).errors());
        return insert(spec);
    }

    /** Public/new-draft boundary: new contracts cannot opt back into legacy v1 semantics. */
    @Transactional
    public LoopDraftRow createNew(LoopSpec spec) {
        if (spec == null || !"v2".equals(spec.schemaVersion())) {
            throw new BadRequestException("LOOPSPEC_V2_REQUIRED", "New LoopSpecs must use schemaVersion v2");
        }
        reject(validator.validate(spec).stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage()).sorted().toList());
        return insert(spec);
    }

    private LoopDraftRow insert(LoopSpec spec) {
        projects.get(spec.projectId());
        String now = Instant.now().toString();
        LoopDraftRow row = new LoopDraftRow(UUID.randomUUID().toString(), spec.projectId(), spec.goal(), write(spec),
                LoopDraftStatus.DRAFT_READY.name(), now, now, 0);
        lifecycle.create(subject(row), row.status(), java.util.Map.of(), () -> mapper.insertDraft(row),
                () -> new ConflictException("DRAFT_CREATE_CONFLICT", "Loop draft could not be created"));
        return row;
    }
    public LoopDraftRow get(String id) { return mapper.findDraft(id).orElseThrow(() -> new NotFoundException("Loop draft not found: " + id)); }

    /** Copies immutable legacy content into an editable v2 shell; behavior mappings must then be completed explicitly. */
    @Transactional
    public LoopDraftRow copyAsV2(String id) {
        LoopSpec source = spec(get(id));
        if (!"v1".equals(source.schemaVersion())) {
            throw new BadRequestException("LOOPSPEC_COPY_V2_SOURCE_INVALID", "Only a persisted v1 draft can be copied to v2");
        }
        List<LoopSpec.StageSpec> stages = source.stages().stream().map(stage -> new LoopSpec.StageSpec(
                stage.objective(), stage.allowedPaths(), stage.forbiddenPaths(), stage.deliverables(),
                stage.verifiers(), List.of(), null, null)).toList();
        return insert(new LoopSpec("v2", source.projectId(), source.goal(), source.context(), stages,
                source.limits(), source.model(), source.sessionPolicy(), source.nextAttemptPromptTemplate()));
    }
    @Transactional
    public LoopDraftRow update(String id, LoopSpec spec) {
        return updateAtVersion(id, spec, null);
    }

    /** Compiler boundary: refuse to overwrite a draft edited after the design was frozen. */
    @Transactional
    public LoopDraftRow updateAtVersion(String id, LoopSpec spec, Long expectedVersion) {
        LoopDraftRow old = get(id);
        if (expectedVersion != null && old.version() != expectedVersion) {
            throw new ConflictException("DESIGNER_DRAFT_CHANGED",
                    "The bound LoopSpec draft changed after this design revision was frozen");
        }
        LoopSpec oldSpec = spec(old);
        if (!oldSpec.schemaVersion().equals(spec.schemaVersion())) {
            throw new BadRequestException("LOOPSPEC_SCHEMA_IMMUTABLE",
                    "Persisted drafts cannot change schemaVersion; copy the draft to upgrade it");
        }
        preserveAggregatedWorkPackageMapping(oldSpec, spec);
        reject(assessment(spec, true, true).errors());
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
    public io.opencode.loopper.persistence.TaskRow confirm(String id, String title) {
        return confirm(id, title, "MANUAL");
    }
    public io.opencode.loopper.persistence.TaskRow confirm(String id, String title, String admissionSource) {
        return confirm(id, title, admissionSource, null);
    }
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
        mapper.findLatestDesignerSessionByDraft(id).ifPresent(session -> {
            if (session.currentRequirementRevision() != null
                    && !java.util.Set.of(io.opencode.loopper.domain.DesignWorkflowPhase.FINAL_REVIEW.name(),
                    io.opencode.loopper.domain.DesignWorkflowPhase.COMPLETED.name())
                    .contains(session.workflowPhase())) {
                throw new ConflictException("DESIGN_WORKFLOW_NOT_COMPLETED",
                        "Review Gate cannot be confirmed until every work package is approved and aggregation is stable");
            }
            validateCompletedWorkPackageMapping(session.id(), spec(draft));
        });
        validateExecutionContract(spec(draft));
        return tasks.createAndConfirmFromDraft(draft, title, admissionSource, isolatedBaseline);
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
        return assessment(spec, requireExecutableAcceptance, true).errors();
    }

    public LoopSpecAcceptanceService.Assessment assessment(LoopSpec spec, boolean requireExecutableAcceptance,
                                                           boolean allowPersistedLegacy) {
        if (spec == null) throw new BadRequestException("LOOPSPEC_REQUIRED", "LoopSpec is required");
        List<String> errors = new ArrayList<>(validator.validate(spec).stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage()).sorted().toList());
        if (!Set.of("v1", "v2").contains(spec.schemaVersion())) {
            errors.add("schemaVersion: only persisted v1 and new v2 are supported");
        }
        for (int stageIndex = 0; stageIndex < spec.stages().size(); stageIndex++) {
            LoopSpec.StageSpec stage = spec.stages().get(stageIndex);
            String stagePath = "stages[" + stageIndex + "]";
            if ("v2".equals(spec.schemaVersion())) {
                errors.addAll(VerifierPathPolicy.validationErrors(stagePath,
                        stage.allowedPaths(), stage.forbiddenPaths()));
            }
            boolean hasAcceptanceVerifier = false;
            for (int verifierIndex = 0; verifierIndex < stage.verifiers().size(); verifierIndex++) {
                LoopSpec.VerifierSpec verifier = stage.verifiers().get(verifierIndex);
                String path = stagePath + ".verifiers[" + verifierIndex + "]";
                String type = verifier.type() == null ? "" : verifier.type();
                if (!SUPPORTED_VERIFIERS.contains(type)) errors.add(path + ".type: unsupported verifier " + type);
                if ("v2".equals(spec.schemaVersion()) && "GIT_DIFF".equals(type)) {
                    errors.addAll(VerifierPathPolicy.validationErrors(path,
                            verifier.allowedPaths(), verifier.forbiddenPaths()));
                }
                if ("v1".equals(spec.schemaVersion()) && !"GIT_DIFF".equals(type)) hasAcceptanceVerifier = true;
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
                        && ProcessCommandPolicy.isMavenWrapper(verifier.command().getFirst())) {
                    String osName = System.getProperty("os.name", "");
                    Path wrapper = ProcessCommandPolicy.platformMavenWrapper(
                            Path.of(projects.get(spec.projectId()).rootPath()), osName);
                    if (!Files.isRegularFile(wrapper)) {
                        errors.add(path + ".command[0]: the platform Maven Wrapper is not present in the registered project root (expected "
                                + wrapper.getFileName() + "); Maven Wrapper is optional, so use an evidenced repository command such as mvn instead");
                    } else if (!osName.toLowerCase(java.util.Locale.ROOT).contains("win") && !Files.isExecutable(wrapper)) {
                        errors.add(path + ".command[0]: " + wrapper.getFileName() + " exists but is not executable; preserve its executable bit "
                                + "or use another evidenced repository command such as mvn");
                    }
                }
                if (("FILE_EXISTS".equals(type) || "FILE_NOT_EXISTS".equals(type))
                        && (verifier.path() == null || verifier.path().isBlank())) {
                    errors.add(path + ".path: file verifier requires a relative path");
                }
                if (("FILE_CONTENT".equals(type) || "FILE_HASH".equals(type) || "JUNIT_XML".equals(type)
                        || "DOCUMENT_STRUCTURE".equals(type) || "TABULAR_DATA".equals(type)
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
                if ("DOCUMENT_STRUCTURE".equals(type) && verifier.documentAssertions().isEmpty()) {
                    errors.add(path + ".documentAssertions: DOCUMENT_STRUCTURE requires bounded assertions");
                }
                if ("TABULAR_DATA".equals(type) && verifier.tabularAssertions().isEmpty()) {
                    errors.add(path + ".tabularAssertions: TABULAR_DATA requires bounded assertions");
                }
                if (verifier.outputContains() != null && !"PROCESS".equals(type)) {
                    errors.add(path + ".outputContains: only PROCESS can assert command output");
                }
            }
            if ("v1".equals(spec.schemaVersion()) && requireExecutableAcceptance && !hasAcceptanceVerifier) {
                errors.add("stages[" + stageIndex + "].verifiers: GIT_DIFF only checks change scope; add a functional verifier for the Designer acceptance criteria");
            }
        }
        return acceptance.assess(spec, errors, allowPersistedLegacy);
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
                        stage.deliverables(), verifiers, stage.acceptanceCriteria(), stage.verificationRuntime(),
                        stage.implementationKind(), stage.workPackageId()));
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
                verifier.sql(), verifier.expectedRowCount(), verifier.assertions(), verifier.criterionIds(),
                verifier.processPurpose(), verifier.testTargets(), verifier.documentAssertions(),
                verifier.tabularAssertions());
    }

    private void preserveAggregatedWorkPackageMapping(LoopSpec oldSpec, LoopSpec updatedSpec) {
        List<String> oldMapping = oldSpec.stages().stream().map(LoopSpec.StageSpec::workPackageId).toList();
        if (oldMapping.stream().noneMatch(id -> !blank(id))) return;
        List<String> updatedMapping = updatedSpec.stages().stream().map(LoopSpec.StageSpec::workPackageId).toList();
        if (!oldMapping.equals(updatedMapping)) {
            throw new BadRequestException("WORK_PACKAGE_MAPPING_IMMUTABLE",
                    "Aggregated Stage workPackageId mapping cannot be removed or changed; restart package design to change package boundaries");
        }
    }

    private void validateCompletedWorkPackageMapping(String designerSessionId, LoopSpec spec) {
        mapper.findCurrentDesignRequirementRevision(designerSessionId).ifPresent(revision -> {
            List<io.opencode.loopper.persistence.DesignWorkPackageRow> packages =
                    mapper.listDesignWorkPackages(revision.id());
            if (packages.stream().anyMatch(packageRow ->
                    !io.opencode.loopper.domain.DesignWorkPackageState.APPROVED.name().equals(packageRow.state())
                            || packageRow.approvedDesignRevision() == null)) {
                throw new ConflictException("WORK_PACKAGE_APPROVAL_REQUIRED",
                        "Every work package must be explicitly approved before final confirmation");
            }
            List<String> expected = packages.stream()
                    .map(io.opencode.loopper.persistence.DesignWorkPackageRow::packageId).toList();
            if (expected.isEmpty()) return;
            Map<String, Integer> order = new LinkedHashMap<>();
            for (int index = 0; index < expected.size(); index++) order.put(expected.get(index), index);
            Set<String> represented = new LinkedHashSet<>();
            int previous = -1;
            for (LoopSpec.StageSpec stage : spec.stages()) {
                Integer current = order.get(stage.workPackageId());
                if (current == null || current < previous) {
                    throw invalidWorkPackageMapping();
                }
                previous = current;
                represented.add(stage.workPackageId());
            }
            if (!represented.equals(new LinkedHashSet<>(expected))) throw invalidWorkPackageMapping();
        });
    }

    private BadRequestException invalidWorkPackageMapping() {
        return new BadRequestException("WORK_PACKAGE_STAGE_MAPPING_INVALID",
                "Completed package design requires every Stage to retain its dependency-ordered workPackageId before confirmation");
    }

    private void reject(List<String> errors) {
        if (!errors.isEmpty()) throw new BadRequestException("LOOPSPEC_INVALID", String.join("; ", errors));
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
