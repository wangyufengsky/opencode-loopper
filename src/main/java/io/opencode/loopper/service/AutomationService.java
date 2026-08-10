package io.opencode.loopper.service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.opencode.loopper.domain.AutomationApprovalMode;
import io.opencode.loopper.domain.AutomationTriggerType;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.AutomationRuleState;
import io.opencode.loopper.domain.AutomationRunState;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AutomationRuleRow;
import io.opencode.loopper.persistence.AutomationRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.ProcessResult;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Safe automation admission: triggers create normal drafts/tasks, never bypass the task state machine. */
@Service
public class AutomationService {
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(5);
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    private final LoopSpecTemplateService templates;
    private final ProjectService projects;
    private final LoopDraftService drafts;
    private final TaskService tasks;
    private final SafeProcessRunner runner;
    private final AutomationRunPersistence runPersistence;
    private final Map<String, WorkspacePreview> importPreviews = new ConcurrentHashMap<>();

    public AutomationService(LoopperMapper mapper, LifecycleTransitionService lifecycle,
                             ObjectMapper json, LoopSpecTemplateService templates, ProjectService projects,
                             LoopDraftService drafts, TaskService tasks, SafeProcessRunner runner,
                             AutomationRunPersistence runPersistence) {
        this.mapper = mapper; this.lifecycle = lifecycle; this.json = json; this.templates = templates; this.projects = projects;
        this.drafts = drafts; this.tasks = tasks; this.runner = runner; this.runPersistence = runPersistence;
    }

    /** Creation is deliberately inert even if a caller submits ENABLED or AUTO_START. */
    @Transactional
    public RuleMutation create(RuleInput input) {
        required(input.name(), "AUTOMATION_NAME_REQUIRED");
        ProjectRow project = projects.get(input.projectId());
        templates.version(input.templateVersionId());
        AutomationTriggerType trigger = requiredTrigger(input.triggerType());
        Map<String, Object> config = validateConfig(trigger, input.triggerConfig());
        String token = trigger == AutomationTriggerType.WEBHOOK ? newToken() : null;
        String now = now();
        AutomationRuleRow row = new AutomationRuleRow(UUID.randomUUID().toString(), input.name().trim(), project.id(), input.templateVersionId(),
                trigger.name(), AutomationRuleState.DISABLED.name(), AutomationApprovalMode.REVIEW_REQUIRED.name(), write(config),
                token == null ? null : sha256(token), null, now, now, 0);
        lifecycle.create(ruleSubject(row.id()), row.state(), Map.of(), () -> mapper.insertAutomationRule(row),
                () -> new ConflictException("AUTOMATION_RULE_CREATE_CONFLICT", "Automation rule could not be created"));
        return new RuleMutation(rule(row), token, token == null ? null : "/api/automations/webhooks/" + row.id() + "/{token}");
    }

    @Transactional
    public RuleView update(String id, RuleInput input, long version) {
        AutomationRuleRow old = getRule(id);
        required(input.name(), "AUTOMATION_NAME_REQUIRED");
        projects.get(old.projectId());
        templates.version(input.templateVersionId());
        AutomationTriggerType trigger = requiredTrigger(input.triggerType());
        Map<String, Object> config = validateConfig(trigger, input.triggerConfig());
        String state = AutomationRuleState.ENABLED.name().equals(input.state())
                ? AutomationRuleState.ENABLED.name() : AutomationRuleState.DISABLED.name();
        AutomationApprovalMode approval = input.approvalMode() == AutomationApprovalMode.AUTO_START
                ? AutomationApprovalMode.AUTO_START : AutomationApprovalMode.REVIEW_REQUIRED;
        if (approval == AutomationApprovalMode.AUTO_START) requireAutoStartApproved(input.templateVersionId());
        String tokenHash = trigger == AutomationTriggerType.WEBHOOK ? old.webhookTokenHash() : null;
        if (trigger == AutomationTriggerType.WEBHOOK && tokenHash == null) throw new ConflictException("WEBHOOK_TOKEN_ROTATION_REQUIRED", "Create a new webhook rule to issue a token");
        AutomationRuleRow changed = new AutomationRuleRow(old.id(), input.name().trim(), old.projectId(), input.templateVersionId(), trigger.name(), state,
                approval.name(), write(config), tokenHash, old.lastObservedHead(), old.createdAt(), now(), version);
        updateRule(old, changed);
        return rule(getRule(id));
    }

    public List<RuleView> rules() { return mapper.listAutomationRules().stream().map(this::rule).toList(); }
    public RuleView ruleById(String id) { return rule(getRule(id)); }
    public List<RunView> runs(String ruleId) { getRule(ruleId); return mapper.listAutomationRuns(ruleId).stream().map(this::run).toList(); }
    public RunFeed allRuns() { return new RunFeed(mapper.listAutomationRules().stream().flatMap(rule -> mapper.listAutomationRuns(rule.id()).stream()).map(this::run).sorted(java.util.Comparator.comparing(RunView::detectedAt).reversed()).toList(), now()); }

    /** Exports no webhook secret or hash; importing can only issue a fresh token on a new rule. */
    public WorkspaceExport exportWorkspace() {
        List<WorkspaceTemplate> exportedTemplates = templates.list().stream().map(template -> new WorkspaceTemplate(template.id(), template.name(),
                template.description(), template.state(), template.createdAt(), template.updatedAt(), template.version(),
                template.versions().stream().map(version -> new WorkspaceVersion(version.id(), version.templateId(), version.versionNumber(),
                        version.spec(), version.specSha256(), version.autoStartApproved(), version.createdAt())).toList())).toList();
        List<WorkspaceRule> exportedRules = mapper.listAutomationRules().stream().map(row -> new WorkspaceRule(row.id(), row.name(), row.projectId(),
                row.templateVersionId(), requiredTrigger(row.triggerType()), config(row))).toList();
        return new WorkspaceExport(1, exportedTemplates, exportedRules);
    }

    /** Preview validates every embedded LoopSpec and trigger without writing any template, rule, draft, or run row. */
    public WorkspacePreview previewWorkspace(String source) {
        if (source == null || source.isBlank()) throw new BadRequestException("AUTOMATION_IMPORT_REQUIRED", "Automation export JSON is required");
        WorkspaceExport exported;
        try { exported = json.readValue(source, WorkspaceExport.class); }
        catch (JacksonException invalid) { throw new BadRequestException("AUTOMATION_IMPORT_INVALID", "Automation export JSON is invalid"); }
        validateWorkspaceExport(exported);
        String id = UUID.randomUUID().toString();
        WorkspacePreview preview = new WorkspacePreview(id, exported.templates().size(), exported.rules().size(), exported, Instant.now().plus(10, ChronoUnit.MINUTES));
        importPreviews.put(id, preview);
        return preview;
    }

    @Transactional
    public WorkspaceImportResult confirmWorkspaceImport(String previewId) {
        WorkspacePreview preview = importPreviews.get(previewId);
        if (preview == null || preview.expiresAt().isBefore(Instant.now())) throw new ConflictException("AUTOMATION_IMPORT_PREVIEW_EXPIRED", "Import preview is missing or expired");
        // Revalidate the stored payload before the write path.  A preview is advisory, never an authorization token.
        validateWorkspaceExport(preview.exported());
        if (!importPreviews.remove(previewId, preview)) throw new ConflictException("AUTOMATION_IMPORT_PREVIEW_EXPIRED", "Import preview was already consumed");
        Map<String, String> versions = new LinkedHashMap<>();
        List<LoopSpecTemplateService.TemplateView> importedTemplates = new java.util.ArrayList<>();
        for (WorkspaceTemplate old : preview.exported().templates()) {
            WorkspaceVersion first = old.versions().stream().min(java.util.Comparator.comparingInt(WorkspaceVersion::versionNumber)).orElseThrow();
            LoopSpecTemplateService.TemplateView created = templates.create(old.name(), old.description());
            putImportedVersion(versions, first.id(), templates.createVersion(created.id(), first.spec(), first.autoStartApproved()).id());
            for (WorkspaceVersion next : old.versions().stream().filter(version -> version != first).sorted(java.util.Comparator.comparingInt(WorkspaceVersion::versionNumber)).toList()) {
                putImportedVersion(versions, next.id(), templates.createVersion(created.id(), next.spec(), next.autoStartApproved()).id());
            }
            importedTemplates.add(templates.get(created.id()));
        }
        List<RuleMutation> importedRules = new java.util.ArrayList<>();
        for (WorkspaceRule old : preview.exported().rules()) {
            String version = versions.get(old.templateVersionId());
            if (version == null) throw new BadRequestException("AUTOMATION_IMPORT_RULE_VERSION", "Imported rule references an unknown template version");
            importedRules.add(create(new RuleInput(old.name(), old.projectId(), version, old.triggerType(),
                    old.triggerConfig(), AutomationRuleState.DISABLED.name(), AutomationApprovalMode.REVIEW_REQUIRED)));
        }
        return new WorkspaceImportResult(importedTemplates, List.copyOf(importedRules));
    }

    public RunView manual(String ruleId) { return trigger(ruleId, AutomationTriggerType.MANUAL, "manual:" + UUID.randomUUID(), Map.of("source", "manual")); }

    public RunView webhook(String ruleId, String token, String remoteAddress, String requestBody, String deliveryId) {
        AutomationRuleRow rule = getRule(ruleId);
        if (requiredTrigger(rule.triggerType()) != AutomationTriggerType.WEBHOOK) throw new BadRequestException("WEBHOOK_TRIGGER_MISMATCH", "Rule is not a webhook trigger");
        if (!loopback(remoteAddress)) throw new BadRequestException("WEBHOOK_LOOPBACK_REQUIRED", "Webhook is available only from loopback");
        if (token == null || rule.webhookTokenHash() == null || !MessageDigest.isEqual(bytes(sha256(token)), bytes(rule.webhookTokenHash()))) {
            throw new BadRequestException("WEBHOOK_TOKEN_INVALID", "Webhook token is invalid");
        }
        String bodyHash = sha256(requestBody == null ? "" : requestBody);
        String delivery = deliveryId == null || deliveryId.isBlank() ? UUID.randomUUID().toString() : deliveryId.trim();
        return trigger(ruleId, AutomationTriggerType.WEBHOOK, "webhook:" + delivery, Map.of("source", "webhook", "bodySha256", bodyHash, "deliveryId", delivery));
    }

    public RunView confirmReview(String runId, String title) {
        AutomationRunRow run = mapper.findAutomationRun(runId).orElseThrow(() -> new NotFoundException("Automation run not found: " + runId));
        if (!AutomationRunState.REVIEW_REQUIRED.name().equals(run.state()) || run.draftId() == null) {
            throw new ConflictException("AUTOMATION_RUN_NOT_REVIEWABLE", "Run is not waiting for review confirmation");
        }
        String taskId = null;
        try {
            TaskRow task = drafts.confirm(run.draftId(), title, "AUTOMATION");
            taskId = task.id();
            TaskRow started = TaskState.READY.name().equals(task.state()) ? tasks.start(task.id()) : task;
            Map<String, Object> evidence = evidence(run);
            AutomationRunState state = stateFor(started);
            if (state == AutomationRunState.FAILED) evidence = taskFailureEvidence(evidence, started);
            AutomationRunRow changed = new AutomationRunRow(run.id(), run.ruleId(), run.triggerType(), run.idempotencyKey(), state.name(),
                    run.draftId(), started.id(), write(evidence), run.detectedAt(), now(), terminal(started) ? now() : null,
                    run.version());
            runPersistence.update(changed);
            return run(changed);
        } catch (RuntimeException failure) {
            Map<String, Object> failedEvidence = failureEvidence(evidence(run), failure);
            AutomationRunRow failed = new AutomationRunRow(run.id(), run.ruleId(), run.triggerType(), run.idempotencyKey(),
                    AutomationRunState.FAILED.name(),
                    run.draftId(), taskId, write(failedEvidence), run.detectedAt(), run.startedAt(), now(), run.version());
            runPersistence.update(failed);
            return run(failed);
        }
    }

    @Scheduled(fixedDelayString = "${loopper.automation-monitor-delay:15s}")
    public void poll() {
        for (AutomationRuleRow rule : mapper.listAutomationRules()) {
            try {
                reconcile(rule);
                if (!AutomationRuleState.ENABLED.name().equals(rule.state())) continue;
                AutomationTriggerType trigger = requiredTrigger(rule.triggerType());
                if (trigger == AutomationTriggerType.GIT_HEAD_CHANGED) pollGitHead(rule);
                else if (trigger == AutomationTriggerType.CRON) pollCron(rule);
            } catch (RuntimeException ignored) { /* durable run history is authoritative; a later poll retries safe detection */ }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() { poll(); }

    private void pollGitHead(AutomationRuleRow rule) {
        ProjectRow project = projects.get(rule.projectId());
        ProcessResult result = runner.run(Path.of(project.rootPath()), List.of("git", "rev-parse", "HEAD"), GIT_TIMEOUT);
        if (result.timedOut() || result.outputTruncated() || result.exitCode() != 0 || result.output().isBlank()) return;
        String head = result.output().trim();
        if (rule.lastObservedHead() == null || rule.lastObservedHead().isBlank()) { updateLastHead(rule, head); return; }
        if (head.equals(rule.lastObservedHead())) return;
        trigger(rule.id(), AutomationTriggerType.GIT_HEAD_CHANGED, "git-head:" + head, Map.of("head", head));
        updateLastHead(rule, head);
    }

    private void pollCron(AutomationRuleRow rule) {
        Map<String, Object> config = config(rule);
        String expression = string(config.get("expression"));
        if (expression == null) expression = string(config.get("cron")); // migration compatibility only
        if (expression == null) return;
        try {
            org.springframework.scheduling.support.CronExpression cron = org.springframework.scheduling.support.CronExpression.parse(normalizeCron(expression));
            java.time.ZoneId zone = java.time.ZoneId.of(string(config.get("timezone")) == null ? "UTC" : string(config.get("timezone")));
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(zone);
            java.time.ZonedDateTime scheduled = null;
            java.time.ZonedDateTime candidate = cron.next(now.minusMinutes(2));
            while (candidate != null && !candidate.isAfter(now)) { scheduled = candidate; candidate = cron.next(candidate); }
            if (scheduled == null) return;
            String bucket = scheduled.toInstant().toString();
            trigger(rule.id(), AutomationTriggerType.CRON, "cron:" + bucket, Map.of("expression", expression, "timezone", zone.getId(), "scheduledAt", bucket));
        }
        catch (RuntimeException invalid) { return; }
    }

    private RunView trigger(String ruleId, AutomationTriggerType actual, String scopedKey, Map<String, Object> evidence) {
        AutomationRuleRow rule = getRule(ruleId);
        if (!AutomationRuleState.ENABLED.name().equals(rule.state())) {
            throw new ConflictException("AUTOMATION_RULE_DISABLED", "Automation rule is disabled by default and must be explicitly enabled");
        }
        if (requiredTrigger(rule.triggerType()) != actual) throw new BadRequestException("AUTOMATION_TRIGGER_MISMATCH", "Trigger type does not match the rule");
        String key = rule.id() + ":" + scopedKey;
        if (mapper.listAutomationRuns(rule.id()).stream().anyMatch(run -> key.equals(run.idempotencyKey()))) {
            return mapper.listAutomationRuns(rule.id()).stream().filter(run -> key.equals(run.idempotencyKey())).findFirst().map(this::run).orElseThrow();
        }
        String detected = now();
        AutomationRunRow detectedRun = new AutomationRunRow(UUID.randomUUID().toString(), rule.id(), actual.name(), key,
                AutomationRunState.DETECTED.name(), null, null, write(evidence), detected, null, null, 0);
        try {
            runPersistence.insert(detectedRun);
        } catch (RuntimeException duplicate) {
            AutomationRunRow existing = mapper.listAutomationRuns(rule.id()).stream()
                    .filter(run -> key.equals(run.idempotencyKey())).findFirst().orElse(null);
            if (existing != null) return run(existing);
            throw duplicate;
        }
        String draftId = null;
        String taskId = null;
        try {
            LoopSpec original = templates.versionSpec(rule.templateVersionId());
            LoopSpec bound = new LoopSpec(original.schemaVersion(), rule.projectId(), original.goal(), original.context(), original.stages(),
                    original.limits(), original.model(), original.sessionPolicy(), original.nextAttemptPromptTemplate(), original.budget());
            var draft = drafts.create(bound);
            draftId = draft.id();
            if (AutomationApprovalMode.REVIEW_REQUIRED.name().equals(rule.approvalMode())) {
                AutomationRunRow review = new AutomationRunRow(detectedRun.id(), rule.id(), actual.name(), key,
                        AutomationRunState.REVIEW_REQUIRED.name(), draft.id(), null,
                        detectedRun.evidenceJson(), detected, null, null, detectedRun.version());
                runPersistence.update(review);
                return run(review);
            }
            requireAutoStartApproved(rule.templateVersionId());
            TaskRow task = drafts.confirm(draft.id(), "自动化 · " + rule.name(), "AUTOMATION");
            taskId = task.id();
            TaskRow started = TaskState.READY.name().equals(task.state()) ? tasks.start(task.id()) : task;
            AutomationRunState state = stateFor(started);
            Map<String, Object> finalEvidence = state == AutomationRunState.FAILED
                    ? taskFailureEvidence(evidence, started) : evidence;
            AutomationRunRow active = new AutomationRunRow(detectedRun.id(), rule.id(), actual.name(), key,
                    state.name(), draft.id(), started.id(),
                    write(finalEvidence), detected, now(), terminal(started) ? now() : null, detectedRun.version());
            runPersistence.update(active);
            return run(active);
        } catch (RuntimeException failure) {
            Map<String, Object> failedEvidence = failureEvidence(evidence, failure);
            AutomationRunRow failed = new AutomationRunRow(detectedRun.id(), rule.id(), actual.name(), key,
                    AutomationRunState.FAILED.name(), draftId, taskId,
                    write(failedEvidence), detected, null, now(), detectedRun.version());
            runPersistence.update(failed);
            return run(failed);
        }
    }

    private void reconcile(AutomationRuleRow rule) {
        for (AutomationRunRow run : mapper.listAutomationRuns(rule.id())) {
            if (run.taskId() == null || terminalRun(run.state())) continue;
            TaskRow task = tasks.get(run.taskId());
            AutomationRunState state = stateFor(task);
            if (!state.name().equals(run.state())) runPersistence.update(new AutomationRunRow(run.id(), run.ruleId(), run.triggerType(), run.idempotencyKey(), state.name(),
                    run.draftId(), run.taskId(), run.evidenceJson(), run.detectedAt(), run.startedAt(), terminal(task) ? now() : null,
                    run.version()));
        }
    }

    private void validateWorkspaceExport(WorkspaceExport exported) {
        if (exported == null || exported.formatVersion() != 1) throw new BadRequestException("AUTOMATION_IMPORT_VERSION", "Unsupported automation export version");
        java.util.Set<String> templateIds = new java.util.HashSet<>();
        java.util.Map<String, String> versionOwners = new LinkedHashMap<>();
        for (WorkspaceTemplate template : exported.templates()) {
            if (template.id() == null || template.id().isBlank() || !templateIds.add(template.id())) {
                throw new BadRequestException("AUTOMATION_IMPORT_TEMPLATE_DUPLICATE", "Imported template ids must be unique");
            }
            required(template.name(), "TEMPLATE_NAME_REQUIRED");
            if (template.versions().isEmpty()) throw new BadRequestException("AUTOMATION_IMPORT_TEMPLATE_EMPTY", "Imported template needs an immutable version");
            for (WorkspaceVersion version : template.versions()) {
                if (version.id() == null || version.id().isBlank() || versionOwners.putIfAbsent(version.id(), template.id()) != null) {
                    throw new BadRequestException("AUTOMATION_IMPORT_VERSION_DUPLICATE", "Imported template version ids must be unique");
                }
                if (!template.id().equals(version.templateId())) {
                    throw new BadRequestException("AUTOMATION_IMPORT_VERSION_OWNER", "Imported template version belongs to a different template");
                }
                LoopSpecTemplateService.ImportPreview parsed = templates.previewImport(writeSpec(version.spec()));
                if (version.specSha256() == null || !MessageDigest.isEqual(bytes(parsed.specSha256()), bytes(version.specSha256()))) {
                    throw new BadRequestException("AUTOMATION_IMPORT_HASH_MISMATCH", "Imported template version hash does not match its normalized LoopSpec");
                }
            }
        }
        for (WorkspaceRule rule : exported.rules()) {
            projects.get(rule.projectId());
            validateConfig(rule.triggerType(), rule.triggerConfig());
            if (rule.templateVersionId() == null || !versionOwners.containsKey(rule.templateVersionId())) {
                throw new BadRequestException("AUTOMATION_IMPORT_RULE_VERSION", "Imported rule references a version outside this export");
            }
        }
    }

    private void putImportedVersion(Map<String, String> versions, String sourceId, String importedId) {
        if (versions.putIfAbsent(sourceId, importedId) != null) {
            throw new BadRequestException("AUTOMATION_IMPORT_VERSION_DUPLICATE", "Imported template version ids must be unique");
        }
    }

    private Map<String, Object> failureEvidence(Map<String, Object> original, RuntimeException failure) {
        Map<String, Object> failed = new LinkedHashMap<>(original);
        failed.put("errorType", failure.getClass().getSimpleName());
        failed.put("errorCode", errorCode(failure));
        failed.put("error", failure.getMessage() == null ? "Automation admission failed" : failure.getMessage());
        return failed;
    }

    private Map<String, Object> taskFailureEvidence(Map<String, Object> original, TaskRow task) {
        Map<String, Object> failed = new LinkedHashMap<>(original);
        failed.put("errorType", "TaskFailure");
        tasks.errors(task.id()).stream().findFirst().ifPresentOrElse(error -> {
            failed.put("errorCode", error.code());
            failed.put("error", error.message());
        }, () -> {
            failed.put("errorCode", "TASK_FAILED");
            failed.put("error", "Task transitioned to FAILED during automation admission");
        });
        return failed;
    }

    private void updateLastHead(AutomationRuleRow old, String head) {
        AutomationRuleRow changed = new AutomationRuleRow(old.id(), old.name(), old.projectId(), old.templateVersionId(), old.triggerType(), old.state(),
                old.approvalMode(), old.triggerConfigJson(), old.webhookTokenHash(), head, old.createdAt(), now(), old.version());
        updateRule(old, changed);
    }
    private void updateRule(AutomationRuleRow old, AutomationRuleRow changed) {
        if (old.state().equals(changed.state())) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateAutomationRuleDetails(changed),
                    () -> new ConflictException("AUTOMATION_RULE_VERSION_CONFLICT", "Automation rule changed concurrently"));
        } else {
            lifecycle.transition(ruleSubject(old.id()), old.state(), changed.state(), null, Map.of(),
                    () -> mapper.updateAutomationRule(changed),
                    () -> new ConflictException("AUTOMATION_RULE_VERSION_CONFLICT", "Automation rule changed concurrently"));
        }
    }
    private LifecycleTransitionService.Subject ruleSubject(String ruleId) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.AUTOMATION_RULE, ruleId,
                LifecycleScopeType.AUTOMATION_RULE, ruleId);
    }
    private void requireAutoStartApproved(String versionId) {
        var version = templates.version(versionId);
        if (!version.immutable() || !version.autoStartApproved()) throw new ConflictException("AUTO_START_TEMPLATE_NOT_APPROVED", "AUTO_START requires an approved immutable template version");
    }
    private AutomationTriggerType requiredTrigger(AutomationTriggerType type) { if (type == null) throw new BadRequestException("AUTOMATION_TRIGGER_REQUIRED", "Automation trigger is required"); return type; }
    private AutomationTriggerType requiredTrigger(String type) { try { return AutomationTriggerType.valueOf(type); } catch (Exception invalid) { throw new ConflictException("AUTOMATION_TRIGGER_INVALID", "Stored automation trigger is invalid"); } }
    private Map<String, Object> validateConfig(AutomationTriggerType type, Map<String, Object> input) {
        Map<String, Object> config = input == null ? Map.of() : Map.copyOf(input);
        if (type == AutomationTriggerType.CRON) {
            String cron = string(config.get("expression"));
            if (cron == null) cron = string(config.get("cron"));
            if (cron == null) throw new BadRequestException("AUTOMATION_CRON_REQUIRED", "CRON requires a cron expression, never a command");
            try { org.springframework.scheduling.support.CronExpression.parse(normalizeCron(cron)); } catch (RuntimeException invalid) { throw new BadRequestException("AUTOMATION_CRON_INVALID", "Cron expression is invalid"); }
            String timezone = string(config.get("timezone"));
            try { java.time.ZoneId.of(timezone == null ? "UTC" : timezone); } catch (RuntimeException invalid) { throw new BadRequestException("AUTOMATION_TIMEZONE_INVALID", "Cron timezone is invalid"); }
            return Map.of("expression", cron, "timezone", timezone == null ? "UTC" : timezone);
        }
        if (type == AutomationTriggerType.GIT_HEAD_CHANGED || type == AutomationTriggerType.MANUAL || type == AutomationTriggerType.WEBHOOK) return Map.of();
        throw new BadRequestException("AUTOMATION_TRIGGER_INVALID", "Unsupported automation trigger");
    }
    private RuleView rule(AutomationRuleRow row) { return new RuleView(row.id(), row.name(), row.projectId(), row.templateVersionId(), requiredTrigger(row.triggerType()), config(row), row.state(), AutomationApprovalMode.valueOf(row.approvalMode()), row.updatedAt(), row.version()); }
    private RunView run(AutomationRunRow row) { return new RunView(row.id(), row.ruleId(), row.triggerType(), row.state(), row.draftId(), row.taskId(), evidence(row), row.detectedAt(), row.startedAt(), row.endedAt()); }
    private AutomationRuleRow getRule(String id) { return mapper.findAutomationRule(id).orElseThrow(() -> new NotFoundException("Automation rule not found: " + id)); }
    private Map<String, Object> config(AutomationRuleRow row) { try { return json.readValue(row.triggerConfigJson(), new TypeReference<>() {}); } catch (JacksonException invalid) { throw new ConflictException("AUTOMATION_CONFIG_INVALID", "Stored automation config is invalid"); } }
    private Map<String, Object> evidence(AutomationRunRow row) { try { return json.readValue(row.evidenceJson(), new TypeReference<>() {}); } catch (JacksonException invalid) { return Map.of("unreadable", true); } }
    private AutomationRunState stateFor(TaskRow task) {
        return switch (TaskState.valueOf(task.state())) {
            case SUCCEEDED -> AutomationRunState.SUCCEEDED;
            case FAILED, CANCELLED -> AutomationRunState.FAILED;
            case QUEUED -> AutomationRunState.QUEUED;
            default -> AutomationRunState.RUNNING;
        };
    }
    private boolean terminal(TaskRow task) { return TaskState.valueOf(task.state()).terminal(); }
    private boolean terminalRun(String state) { return AutomationRunState.valueOf(state).terminal(); }
    private boolean loopback(String remote) { try { return remote != null && InetAddress.getByName(remote).isLoopbackAddress(); } catch (Exception invalid) { return false; } }
    private String newToken() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value))); } catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); } }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private String write(Map<String, Object> value) { try { return json.writeValueAsString(value); } catch (JacksonException failure) { throw new BadRequestException("AUTOMATION_CONFIG_INVALID", "Automation config cannot be serialized"); } }
    private String required(String value, String code) { if (value == null || value.isBlank()) throw new BadRequestException(code, "Automation name is required"); return value.trim(); }
    private String string(Object value) { return value instanceof String text && !text.isBlank() ? text.trim() : null; }
    private String errorCode(RuntimeException failure) {
        if (failure instanceof ConflictException conflict) return conflict.code();
        if (failure instanceof BadRequestException badRequest) return badRequest.code();
        if (failure instanceof ServiceUnavailableException unavailable) return unavailable.code();
        return "AUTOMATION_ADMISSION_FAILED";
    }
    private String normalizeCron(String expression) { String[] fields = expression.trim().split("\\s+"); return fields.length == 5 ? "0 " + expression.trim() : expression.trim(); }
    private String now() { return Instant.now().toString(); }

    private String writeSpec(LoopSpec spec) { try { return json.writeValueAsString(spec); } catch (JacksonException failure) { throw new BadRequestException("AUTOMATION_IMPORT_INVALID", "Imported LoopSpec cannot be serialized"); } }

    public record RuleInput(String name, String projectId, String templateVersionId, AutomationTriggerType triggerType, Map<String, Object> triggerConfig, String state, AutomationApprovalMode approvalMode) { }
    public record RuleView(String id, String name, String projectId, String templateVersionId, AutomationTriggerType triggerType, Map<String, Object> triggerConfig, String state, AutomationApprovalMode approvalMode, String updatedAt, long version) { }
    public record RuleMutation(RuleView rule, String webhookToken, String webhookPath) { }
    public record RunView(String id, String ruleId, String triggerType, String state, String draftId, String taskId, Map<String, Object> evidence, String detectedAt, String startedAt, String endedAt) { }
    public record WorkspaceExport(int formatVersion, List<WorkspaceTemplate> templates, List<WorkspaceRule> rules) {
        public WorkspaceExport { templates = templates == null ? List.of() : List.copyOf(templates); rules = rules == null ? List.of() : List.copyOf(rules); }
    }
    public record WorkspaceTemplate(String id, String name, String description, String state, String createdAt,
                                    String updatedAt, long version, List<WorkspaceVersion> versions) {
        public WorkspaceTemplate { versions = versions == null ? List.of() : List.copyOf(versions); }
    }
    public record WorkspaceVersion(String id, String templateId, int versionNumber, LoopSpec spec,
                                   String specSha256, boolean autoStartApproved, String createdAt) { }
    public record WorkspaceRule(String id, String name, String projectId, String templateVersionId, AutomationTriggerType triggerType, Map<String, Object> triggerConfig) {
        public WorkspaceRule { triggerConfig = triggerConfig == null ? Map.of() : Map.copyOf(triggerConfig); }
    }
    public record WorkspacePreview(String previewId, int templateCount, int ruleCount, WorkspaceExport exported, Instant expiresAt) { }
    public record WorkspaceImportResult(List<LoopSpecTemplateService.TemplateView> templates, List<RuleMutation> rules) { }
    public record RunFeed(List<RunView> runs, String serverTime) { }
}
