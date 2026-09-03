package io.opencode.loopper.runtime;

import java.nio.file.Path;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import io.opencode.loopper.domain.SessionFailure;
import tools.jackson.databind.ObjectMapper;

/** Deterministic local implementation used in tests and development without a provider. */
public class FakeOpenCodeClient implements OpenCodeClient {
    private static final String FAKE_ENDPOINT_FINGERPRINT = OpenCodeSessionConnectionGuard
            .endpointFingerprint(URI.create("http://127.0.0.1/fake-opencode"));
    private final OpenCodeSessionRuntimeBindings runtimeBindings;
    private final ConcurrentHashMap<String, String> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> readOnly = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> judgeRoleBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> judgeOutputByRole = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> promptBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PromptRequest> promptRequestBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionProfile> profileBySession = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PromptCall> promptHistory = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, OpenCodeModel> modelBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Path> worktreeBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> titleBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionAttestation> attestationBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> detailBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingQuestion> pendingQuestionBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.List<java.util.List<String>>> answersByQuestion = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> rejectedQuestions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<PendingPermission>> pendingPermissionsBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PermissionReplyCall> permissionRepliesByRequest = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<SessionTodo>> todosBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<UsageRecord>> usageBySession = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ForkCall> forkCalls = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RevertCall> revertCalls = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SummarizeCall> summarizeCalls = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, AtomicInteger> failedReadOnlySessionsByRole = new ConcurrentHashMap<>();
    private final AtomicInteger failedReadOnlySessions = new AtomicInteger();
    private final AtomicInteger failedReadOnlySessionCreations = new AtomicInteger();
    private final AtomicInteger failedPrompts = new AtomicInteger();
    private final AtomicInteger failedStructuredPrompts = new AtomicInteger();
    private final AtomicInteger failedAborts = new AtomicInteger();
    private final ConcurrentHashMap<SessionProfile, AtomicInteger> failedAbortsByProfile = new ConcurrentHashMap<>();
    private final AtomicInteger toolLoopStatusFailures = new AtomicInteger();
    private final CopyOnWriteArrayList<String> abortedSessionIds = new CopyOnWriteArrayList<>();
    private final AtomicInteger createSessionCalls = new AtomicInteger();
    private final AtomicInteger createReadOnlySessionCalls = new AtomicInteger();
    private final AtomicInteger promptCalls = new AtomicInteger();
    private final java.util.Set<SessionProfile> heldProfiles = ConcurrentHashMap.newKeySet();
    private final FakeOpenCodeResponseFactory responses = new FakeOpenCodeResponseFactory();
    private volatile String judgeOutput = "{\"verdict\":\"PASS\",\"reason\":\"确定性证据满足评审要求。\"}";
    private volatile String taskRouterOutputOverride;
    private volatile boolean healthy = true;
    private volatile ToolCapabilityProbe toolCapability = new ToolCapabilityProbe(CapabilityState.AVAILABLE,
            List.of("read", "glob", "grep", "question", "todowrite"), null);
    private volatile StructuredOutputCapability structuredCapability = new StructuredOutputCapability(
            CapabilityState.AVAILABLE, CapabilityState.AVAILABLE, null);
    private volatile String managedGeneration;
    private volatile String managedInternalMcpServer;

    public FakeOpenCodeClient() {
        this(OpenCodeSessionRuntimeBindings.untracked());
    }

    FakeOpenCodeClient(OpenCodeSessionRuntimeBindings runtimeBindings) {
        this.runtimeBindings = runtimeBindings == null
                ? OpenCodeSessionRuntimeBindings.untracked() : runtimeBindings;
    }
    @Override public boolean healthy() { return healthy; }
    @Override public OpenCodeSession createSession(Path worktree, String title, OpenCodeModel model) {
        createSessionCalls.incrementAndGet();
        String id = "fake-" + UUID.randomUUID();
        states.put(id, "RUNNING");
        worktreeBySession.put(id, worktree);
        titleBySession.put(id, title == null ? "" : title);
        profileBySession.put(id, SessionProfile.IMPLEMENTATION);
        if (model != null) modelBySession.put(id, model);
        return session(id, worktree);
    }
    @Override public OpenCodeSession createReadOnlySession(Path worktree, String title, OpenCodeModel model) {
        createReadOnlySessionCalls.incrementAndGet();
        if (failedReadOnlySessionCreations.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new SessionFailure("OPENCODE_SESSION_CREATE_FAILED", "Deterministic read-only Session creation failure");
        }
        String id = "fake-judge-" + UUID.randomUUID();
        readOnly.put(id, Boolean.TRUE);
        worktreeBySession.put(id, worktree);
        titleBySession.put(id, title == null ? "" : title);
        profileBySession.put(id, SessionProfile.GENERAL_READ_ONLY);
        String normalizedTitle = title == null ? "" : title.toUpperCase();
        String packageId = java.util.regex.Pattern.compile("\\bWP-\\d+\\b").matcher(normalizedTitle).results()
                .map(java.util.regex.MatchResult::group).findFirst().orElse(null);
        String role = normalizedTitle.contains("TASK ROUTER") ? "ROUTER"
                : normalizedTitle.contains("REVIEWER") ? "REVIEWER"
                : normalizedTitle.contains("TASK DECOMPOSER") ? "DECOMPOSER"
                : normalizedTitle.contains("ACCEPTANCE CLOSED-CHOICE") ? "ACCEPTANCE_CANDIDATE"
                : title != null && title.toUpperCase().contains("COMMIT MESSAGE") ? "COMMIT"
                : normalizedTitle.contains("LOOPSPEC COMPILER") ? "COMPILER" + (packageId == null ? "" : ":" + packageId)
                : normalizedTitle.contains("DESIGNER") ? "DESIGNER" + (packageId == null ? "" : ":" + packageId)
                : title != null && title.toUpperCase().contains("RISK") ? "RISK" : "REQUIREMENT";
        judgeRoleBySession.put(id, role);
        // Null means "let the runtime choose its default model". ConcurrentHashMap
        // rejects null values, so absence must be represented by no entry.
        if (model != null) modelBySession.put(id, model);
        AtomicInteger roleFailures = failedReadOnlySessionsByRole.get(role);
        boolean shouldFail = roleFailures != null && roleFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0;
        if (!shouldFail) shouldFail = failedReadOnlySessions.getAndUpdate(value -> Math.max(0, value - 1)) > 0;
        states.put(id, shouldFail ? "FAILED" : "RUNNING");
        return session(id, worktree);
    }
    @Override public OpenCodeSession createSession(Path worktree, String title, OpenCodeModel model,
                                                    SessionProfile profile) {
        OpenCodeSession session = profile == null || profile == SessionProfile.IMPLEMENTATION
                ? createSession(worktree, title, model) : createReadOnlySession(worktree, title, model);
        profileBySession.put(session.id(), profile == null ? SessionProfile.IMPLEMENTATION : profile);
        return session;
    }
    @Override public SessionCreationPlan prepareSessionCreation(Path worktree, String baseTitle,
            OpenCodeModel model, SessionProfile profile, String creationCredential) {
        try {
            Path canonical = worktree.toRealPath();
            SessionProfile effectiveProfile = profile == null ? SessionProfile.IMPLEMENTATION : profile;
            if (candidateProfile(effectiveProfile)
                    && (managedGeneration == null || managedGeneration.isBlank()
                    || managedInternalMcpServer == null || managedInternalMcpServer.isBlank())) {
                throw new SessionFailure("CANDIDATE_MANAGED_RUNTIME_REQUIRED",
                        "Candidate sessions require the current managed OpenCode generation");
            }
            boolean managed = managedGeneration != null && !managedGeneration.isBlank();
            String generation = managed ? managedGeneration : "external-" + FAKE_ENDPOINT_FINGERPRINT;
            List<SessionPermissionRule> permissions = permissionRules(effectiveProfile,
                    managed ? managedInternalMcpServer : null);
            String permissionDigest = OpenCodeClient.permissionPolicyDigest(permissions);
            String exactTitle = OpenCodeClient.recoveryTitle(baseTitle, creationCredential);
            String requestDigest = creationRequestDigest(canonical, exactTitle, generation, managed,
                    managed ? managedInternalMcpServer : null, model, effectiveProfile,
                    permissionDigest, creationCredential);
            return new SessionCreationPlan(canonical, exactTitle, generation, managed,
                    managed ? managedInternalMcpServer : null, FAKE_ENDPOINT_FINGERPRINT, model,
                    effectiveProfile, permissions, permissionDigest, creationCredential, requestDigest);
        } catch (SessionFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SessionFailure("OPENCODE_SESSION_RECOVERY_PLAN_FAILED", failure.getMessage());
        }
    }
    @Override public SessionCreationPlan prepareCandidateSessionCreationLocally(
            Path worktree, String baseTitle, OpenCodeModel model,
            SessionProfile profile, String creationCredential) {
        return prepareSessionCreation(worktree, baseTitle, model, profile, creationCredential);
    }
    @Override public void requireCandidateSessionReady(SessionCreationPlan persistedPlan) {
        requireCurrentPlan(persistedPlan);
    }
    @Override public SessionAttestation createSession(SessionCreationPlan plan) {
        requireCurrentPlan(plan);
        OpenCodeSession session = createSession(plan.canonicalDirectory(), plan.exactTitle(),
                plan.model(), plan.profile());
        SessionAttestation attestation = attestation(session.id(), plan);
        attestationBySession.put(session.id(), attestation);
        return attestation;
    }
    @Override public SessionLookup findSessionsByExactTitle(SessionCreationPlan plan) {
        requireCurrentPlan(plan);
        List<SessionAttestation> matches = titleBySession.entrySet().stream()
                .filter(entry -> java.util.Objects.equals(plan.exactTitle(), entry.getValue()))
                .filter(entry -> java.util.Objects.equals(plan.canonicalDirectory(), worktreeBySession.get(entry.getKey())))
                .filter(entry -> !"ABORTED".equals(states.get(entry.getKey())))
                .map(entry -> recoveredAttestation(entry.getKey(), plan))
                .filter(attestation -> attestation.plan().equals(plan))
                .toList();
        return new SessionLookup(true, matches);
    }
    @Override public SessionLookup findSessionsByExactTitle(Path worktree, String exactTitle,
            OpenCodeModel model, SessionProfile profile) {
        return new SessionLookup(false, List.of());
    }
    @Override public void promptAsync(OpenCodeSession session, String prompt) {
        submitPrompt(session, PromptRequest.text(prompt));
    }
    @Override public void promptAsync(OpenCodeSession session, PromptRequest prompt) {
        if (prompt != null && prompt.responseFormat() instanceof ResponseFormat.JsonSchema
                && failedStructuredPrompts.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new SessionFailure("OPENCODE_STRUCTURED_FORMAT_UNSUPPORTED",
                    "Deterministic structured format rejection");
        }
        submitPrompt(session, prompt == null ? PromptRequest.text("") : prompt);
    }
    private void submitPrompt(OpenCodeSession session, PromptRequest prompt) {
        if (prompt.messageId() != null && prompt.messageId().startsWith("msg_loopper_design_")) {
            var pkg = java.util.regex.Pattern.compile("Current package (WP-\\d+)").matcher(prompt.text());
            judgeRoleBySession.put(session.id(), pkg.find() ? "DESIGNER:" + pkg.group(1) : "DESIGNER");
        }
        promptCalls.incrementAndGet();
        promptBySession.put(session.id(), prompt.text());
        promptRequestBySession.put(session.id(), prompt);
        promptHistory.add(new PromptCall(session.id(), prompt.text()));
        if (failedPrompts.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new SessionFailure("OPENCODE_PROMPT_FAILED", "Deterministic Designer prompt transport failure");
        }
        states.computeIfPresent(session.id(), (id, state) -> "FAILED".equals(state) || heldProfiles.contains(
                profileBySession.get(id)) ? state : "COMPLETED");
    }
    @Override public MessageLookup findPromptMessage(OpenCodeSession session, String messageId) {
        return new MessageLookup(false, false, null);
    }
    @Override public MessageLookup findPromptMessage(OpenCodeSession session, PromptRequest expectedRequest,
            String persistedRequestSha256) {
        if (expectedRequest == null || expectedRequest.messageId() == null) {
            throw new SessionFailure("OPENCODE_PROMPT_LOOKUP_INVALID_REQUEST",
                    "Exact prompt recovery requires a deterministic message id");
        }
        String calculated = OpenCodeClient.promptRequestSha256(expectedRequest);
        if (!java.util.Objects.equals(calculated, persistedRequestSha256)) {
            throw new SessionFailure("OPENCODE_PROMPT_REQUEST_HASH_MISMATCH",
                    "Persisted prompt request hash does not match the exact request");
        }
        PromptRequest found = promptRequestBySession.get(session.id());
        if (found == null || !java.util.Objects.equals(expectedRequest.messageId(), found.messageId())) {
            return new MessageLookup(true, false, null);
        }
        if (!found.equals(expectedRequest)) {
            throw new SessionFailure("OPENCODE_PROMPT_LOOKUP_INVALID_RESPONSE",
                    "The remote prompt content does not match the persisted request hash");
        }
        return new MessageLookup(true, true, calculated);
    }
    @Override public SessionStatus sessionStatus(OpenCodeSession session) {
        if (toolLoopStatusFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new SessionFailure("OPENCODE_MACHINE_TOOL_LOOP",
                    "Detected 3 consecutive identical read tool calls (signature deterministic)");
        }
        return new SessionStatus(states.getOrDefault(session.id(), "FAILED"), detailBySession.get(session.id()));
    }
    @Override public String sessionOutput(OpenCodeSession session) {
        String role = judgeRoleBySession.get(session.id());
        String prompt = promptBySession.getOrDefault(session.id(), "");
        if (prompt.contains("TASK_PROFILE_ROUTER_INPUT")) return taskRouterOutput(prompt);
        if (prompt.contains("TASK_DECOMPOSITION_PLAN_JSON_START")) {
            String explicitPlan = judgeOutputByRole.get("DECOMPOSER_PLAN");
            return explicitPlan == null ? responses.decompositionPlanningOutput(outputForRole(role)) : explicitPlan;
        }
        if (prompt.contains("LOOPSPEC_COMPILATION_PLAN_JSON_START")) {
            String planRole = role == null ? "COMPILER_PLAN" : role.replaceFirst("^COMPILER", "COMPILER_PLAN");
            String explicitPlan = judgeOutputByRole.get(planRole);
            return explicitPlan == null ? responses.packageCompilationPlanningOutput(outputForRole(role)) : explicitPlan;
        }
        if ("REVIEWER".equals(role) && !judgeOutputByRole.containsKey("REVIEWER")) {
            try (var paths = java.nio.file.Files.walk(session.worktree(), 3)) {
                String relative = paths.filter(java.nio.file.Files::isRegularFile).findFirst()
                        .map(path -> session.worktree().relativize(path).toString().replace('\\', '/')).orElse("README.md");
                return "<!-- REVIEWER_REPORT_JSON_START -->\n"
                        + "{\"title\":\"只读评审报告\",\"summary\":\"已按冻结范围完成只读检查。\","
                        + "\"findings\":[{\"severity\":\"INFO\",\"title\":\"基线文件已检查\","
                        + "\"detail\":\"已读取并核对受管项目中的基线文件。\",\"path\":\"" + relative
                        + "\",\"line\":1,\"recommendation\":\"保留该证据并在修改前重新检查。\"}],\"limitations\":[]}\n"
                        + "<!-- REVIEWER_REPORT_JSON_END -->";
            } catch (Exception ignored) {
                return "<!-- REVIEWER_REPORT_JSON_START -->\n"
                        + "{\"title\":\"只读评审报告\",\"summary\":\"完成只读检查。\","
                        + "\"findings\":[{\"severity\":\"INFO\",\"title\":\"README 已检查\","
                        + "\"detail\":\"已检查基线。\",\"path\":\"README.md\",\"line\":1,"
                        + "\"recommendation\":\"修改前重新检查。\"}],\"limitations\":[]}\n"
                        + "<!-- REVIEWER_REPORT_JSON_END -->";
            }
        }
        return outputForRole(role);
    }

    private String taskRouterOutput(String prompt) {
        if (taskRouterOutputOverride != null) return taskRouterOutputOverride;
        String text = prompt == null ? "" : prompt.toLowerCase(java.util.Locale.ROOT);
        int requirementStart = text.indexOf("requirement:");
        int outputStart = text.indexOf("return only the marker-wrapped object");
        if (requirementStart >= 0 && outputStart > requirementStart) {
            text = text.substring(requirementStart + "requirement:".length(), outputStart);
        }
        String intent = "SOFTWARE_CHANGE";
        String artifacts = "[\"SOURCE_CODE\"]";
        String complexity = text.contains("大型") || text.contains("多章节") ? "PACKAGED" : "SIMPLE";
        if ((text.contains("xlsx") || text.contains("excel") || text.contains("csv") || text.contains("tsv"))
                && (text.contains("一次性") || text.contains("转成") || text.contains("转换成"))
                && !text.contains("脚本") && !text.contains("工具")) {
            intent = "DATA_CONVERSION"; artifacts = "[\"MARKDOWN\"]";
        } else if (text.contains("评审") || text.contains("只读") || text.contains("review")) {
            intent = "READ_ONLY_REVIEW"; artifacts = "[\"ANALYSIS_REPORT\"]";
        } else if ((text.contains("docx") || text.contains("markdown") || text.contains(".md")
                || text.contains("文档") || text.contains("手册"))
                && !text.contains("代码") && !text.contains("脚本")) {
            intent = "DOCUMENT_AUTHORING"; artifacts = text.contains("docx") ? "[\"DOCX\"]" : "[\"MARKDOWN\"]";
        } else if (text.contains("调研") || text.contains("research")) {
            intent = "RESEARCH"; artifacts = "[\"ANALYSIS_REPORT\"]";
        } else if (text.contains("配置") || text.contains("维护") || text.contains("依赖升级")) {
            intent = "LOCAL_MAINTENANCE"; artifacts = "[\"CONFIGURATION\"]";
        }
        if ((text.contains("python") || text.contains("py脚本")) && intent.equals("SOFTWARE_CHANGE")) {
            artifacts = "[\"PYTHON_SCRIPT\"]";
        }
        return "{\"intent\":\"" + intent + "\",\"artifactKinds\":" + artifacts
                + ",\"complexity\":\"" + complexity + "\"}";
    }
    @Override public SessionResult sessionResult(OpenCodeSession session) {
        String output = sessionOutput(session);
        PromptRequest request = promptRequestBySession.get(session.id());
        if (request == null || !(request.responseFormat() instanceof ResponseFormat.JsonSchema)) {
            return new SessionResult(output, Map.of(), null, null, 0);
        }
        try {
            String candidate = output == null ? "" : output.trim();
            int start = candidate.indexOf('{');
            int end = candidate.lastIndexOf('}');
            if (start < 0 || end < start) return new SessionResult(output, Map.of(), null, null, 0);
            @SuppressWarnings("unchecked")
            Map<String, Object> structured = new ObjectMapper().readValue(candidate.substring(start, end + 1), Map.class);
            return new SessionResult(output, structured, null, null, 0);
        } catch (Exception failure) {
            return new SessionResult(output, Map.of(), "StructuredOutputError", failure.getMessage(), 0);
        }
    }
    private String outputForRole(String role) {
        String exact = judgeOutputByRole.get(role);
        if (exact != null) return exact;
        int separator = role == null ? -1 : role.indexOf(':');
        return judgeOutputByRole.getOrDefault(separator > 0 ? role.substring(0, separator) : role, judgeOutput);
    }
    @Override public String sessionLiveOutput(OpenCodeSession session) { return sessionOutput(session); }
    @Override public SessionTranscript sessionTranscript(OpenCodeSession session) {
        String output = sessionOutput(session);
        return new SessionTranscript(output == null || output.isBlank() ? java.util.List.of() : java.util.List.of(
                new SessionPart("fake-output", "OUTPUT", "模型输出", output, states.get(session.id()))),
                usageBySession.getOrDefault(session.id(), List.of()));
    }
    @Override public List<SessionMessageRef> sessionMessageRefs(OpenCodeSession session) {
        return List.of(new SessionMessageRef("fake-message", "assistant", null, null));
    }
    @Override public java.util.List<PendingQuestion> pendingQuestions(OpenCodeSession session) {
        PendingQuestion pending = pendingQuestionBySession.get(session.id());
        return pending == null ? java.util.List.of() : java.util.List.of(pending);
    }
    @Override public void replyQuestion(OpenCodeSession session, String requestId, java.util.List<java.util.List<String>> answers) {
        answersByQuestion.put(requestId, java.util.List.copyOf(answers));
        pendingQuestionBySession.computeIfPresent(session.id(), (id, pending) -> requestId.equals(pending.id()) ? null : pending);
        states.put(session.id(), "RUNNING");
    }
    @Override public void rejectQuestion(OpenCodeSession session, String requestId) {
        rejectedQuestions.put(requestId, Boolean.TRUE);
        pendingQuestionBySession.computeIfPresent(session.id(), (id, pending) -> requestId.equals(pending.id()) ? null : pending);
        states.put(session.id(), "RUNNING");
    }
    @Override public List<PendingPermission> pendingPermissions(OpenCodeSession session) {
        return List.copyOf(pendingPermissionsBySession.getOrDefault(session.id(), List.of()));
    }
    @Override public void replyPermission(OpenCodeSession session, String requestId, PermissionReply reply, String message) {
        permissionRepliesByRequest.put(requestId, new PermissionReplyCall(session.id(), requestId, reply, message));
        pendingPermissionsBySession.computeIfPresent(session.id(), (id, requests) -> requests.stream()
                .filter(request -> !requestId.equals(request.id())).toList());
        states.put(session.id(), "RUNNING");
    }
    @Override public List<SessionTodo> sessionTodos(OpenCodeSession session) {
        return List.copyOf(todosBySession.getOrDefault(session.id(), List.of()));
    }
    @Override public SessionTodoSnapshot sessionTodoSnapshot(OpenCodeSession session) {
        List<SessionTodo> todos = sessionTodos(session);
        boolean truncated = todos.stream().anyMatch(todo -> Boolean.TRUE.equals(todo.metadata().get("projectionTruncated")));
        return new SessionTodoSnapshot(todos, truncated, truncated ? "Deterministic Todo projection truncated" : null);
    }
    @Override public ToolCapabilityProbe toolCapabilities(Path worktree) { return toolCapability; }
    @Override public List<AgentInfo> agents() {
        return List.of(new AgentInfo("build", "primary", "Implementation agent"),
                new AgentInfo("plan", "primary", "Planning agent"));
    }
    @Override public StructuredOutputCapability structuredOutputCapability(OpenCodeModel model) {
        return structuredCapability;
    }
    @Override public OpenCodeSession forkSession(OpenCodeSession session, String messageId) {
        String childId = "fake-fork-" + UUID.randomUUID();
        states.put(childId, "IDLE");
        todosBySession.put(childId, List.copyOf(todosBySession.getOrDefault(session.id(), List.of())));
        forkCalls.add(new ForkCall(session.id(), childId, messageId));
        return session(childId, session.worktree());
    }
    @Override public void revertSession(OpenCodeSession session, String messageId, String partId) {
        revertCalls.add(new RevertCall(session.id(), messageId, partId));
    }
    @Override public void summarizeSession(OpenCodeSession session, OpenCodeModel model, boolean automatic) {
        summarizeCalls.add(new SummarizeCall(session.id(), model, automatic));
    }
    @Override public List<UsageRecord> sessionUsage(OpenCodeSession session) {
        return List.copyOf(usageBySession.getOrDefault(session.id(), List.of()));
    }
    @Override public String diff(OpenCodeSession session) { return "[]"; }
    @Override public void abort(OpenCodeSession session) {
        AtomicInteger profileFailures = failedAbortsByProfile.get(profileBySession.get(session.id()));
        if ((profileFailures != null && profileFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0)
                || failedAborts.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new SessionFailure("OPENCODE_ABORT_FAILED", "Deterministic abort transport failure");
        }
        abortedSessionIds.add(session.id());
        states.put(session.id(), "ABORTED");
    }
    public boolean isReadOnlySession(String id) { return Boolean.TRUE.equals(readOnly.get(id)); }
    public SessionProfile profileForSession(String id) { return profileBySession.get(id); }
    public int createSessionCalls() { return createSessionCalls.get(); }
    public int createReadOnlySessionCalls() { return createReadOnlySessionCalls.get(); }
    public int promptCalls() { return promptCalls.get(); }
    public void setJudgeOutput(String output) { judgeOutput = output; }
    public void setJudgeOutput(String role, String output) {
        if (output == null) judgeOutputByRole.remove(role.toUpperCase());
        else judgeOutputByRole.put(role.toUpperCase(), output);
    }
    public void setDesignerOutput(String output) {
        setJudgeOutput("DESIGNER", responses.designerMarkdown(output));
        String compatibilityCompilation = responses.compatibilityCompilation(output);
        if (compatibilityCompilation != null) {
            setCompilerOutput(compatibilityCompilation);
            setDecomposerOutput(responses.directDecomposition(output));
        }
    }
    public void setTaskRouterOutput(String output) { taskRouterOutputOverride = output; }
    public void setDecomposerOutput(String output) { setJudgeOutput("DECOMPOSER", output); }
    public void setDecomposerPlanningOutput(String output) { setJudgeOutput("DECOMPOSER_PLAN", output); }
    public void setCompilerOutput(String output) { setJudgeOutput("COMPILER", output); }
    public void setPackageDesignerOutput(String packageId, String output) { setJudgeOutput("DESIGNER:" + packageId, output); }
    public void setPackageCompilerOutput(String packageId, String output) { setJudgeOutput("COMPILER:" + packageId, output); }
    public void setPackageCompilerPlanningOutput(String packageId, String output) {
        setJudgeOutput("COMPILER_PLAN:" + packageId, output);
    }
    public void setHealthy(boolean value) { healthy = value; }
    public OpenCodeModel modelForSession(String id) { return modelBySession.get(id); }
    public String promptForSession(String id) { return promptBySession.get(id); }
    public PromptRequest promptRequestForSession(String id) { return promptRequestBySession.get(id); }
    public List<PromptCall> promptHistory() { return List.copyOf(promptHistory); }
    public Path sessionWorktree(String sessionId) { return worktreeBySession.get(sessionId); }
    public void failNextPrompts(int count) { failedPrompts.set(Math.max(0, count)); }
    public void failNextStructuredPrompts(int count) { failedStructuredPrompts.set(Math.max(0, count)); }
    public void failNextAborts(int count) { failedAborts.set(Math.max(0, count)); }
    public void failNextAborts(SessionProfile profile, int count) {
        failedAbortsByProfile.put(profile, new AtomicInteger(Math.max(0, count)));
    }
    public void failNextStatusesWithToolLoop(int count) { toolLoopStatusFailures.set(Math.max(0, count)); }
    public List<String> abortedSessionIds() { return List.copyOf(abortedSessionIds); }
    public void setSessionState(String id, String state) { states.put(id, state); detailBySession.remove(id); }
    public void setSessionStatus(String id, String state, String detail) {
        states.put(id, state);
        if (detail == null || detail.isBlank()) detailBySession.remove(id); else detailBySession.put(id, detail);
    }
    public void setPendingQuestion(String sessionId, PendingQuestion pending) {
        pendingQuestionBySession.put(sessionId, pending);
        states.put(sessionId, "RUNNING");
    }
    public java.util.List<java.util.List<String>> answersForQuestion(String questionId) { return answersByQuestion.get(questionId); }
    public boolean wasQuestionRejected(String questionId) { return Boolean.TRUE.equals(rejectedQuestions.get(questionId)); }
    public void setPendingPermission(String sessionId, PendingPermission permission) {
        setPendingPermissions(sessionId, permission == null ? List.of() : List.of(permission));
    }
    public void setPendingPermissions(String sessionId, List<PendingPermission> permissions) {
        pendingPermissionsBySession.put(sessionId, permissions == null ? List.of() : List.copyOf(permissions));
        states.put(sessionId, "RUNNING");
    }
    public PermissionReplyCall permissionReplyForRequest(String requestId) { return permissionRepliesByRequest.get(requestId); }
    public void setSessionTodos(String sessionId, List<SessionTodo> todos) {
        todosBySession.put(sessionId, todos == null ? List.of() : List.copyOf(todos));
    }
    public void setToolCapability(ToolCapabilityProbe capability) {
        toolCapability = capability == null
                ? new ToolCapabilityProbe(CapabilityState.UNKNOWN, List.of(), null) : capability;
    }
    public void setStructuredCapability(StructuredOutputCapability capability) {
        structuredCapability = capability == null
                ? new StructuredOutputCapability(CapabilityState.UNKNOWN, CapabilityState.UNKNOWN, null) : capability;
    }
    public void setManagedRuntime(String generation, String internalMcpServer) {
        managedGeneration = generation;
        managedInternalMcpServer = internalMcpServer;
    }
    public void holdProfileOpen(SessionProfile profile, boolean hold) {
        if (hold) heldProfiles.add(profile); else heldProfiles.remove(profile);
    }
    public void setSessionUsage(String sessionId, List<UsageRecord> usage) {
        usageBySession.put(sessionId, usage == null ? List.of() : List.copyOf(usage));
    }
    public List<ForkCall> forkCalls() { return List.copyOf(forkCalls); }
    public List<RevertCall> revertCalls() { return List.copyOf(revertCalls); }
    public List<SummarizeCall> summarizeCalls() { return List.copyOf(summarizeCalls); }
    public void failNextReadOnlySessions(int count) { failedReadOnlySessions.set(Math.max(0, count)); }
    public void failNextReadOnlySessionCreations(int count) { failedReadOnlySessionCreations.set(Math.max(0, count)); }
    public void failNextReadOnlySessions(String role, int count) { failedReadOnlySessionsByRole.put(role.toUpperCase(), new AtomicInteger(Math.max(0, count))); }
    public void reset() { states.clear(); readOnly.clear(); judgeRoleBySession.clear(); judgeOutputByRole.clear(); promptBySession.clear(); promptRequestBySession.clear(); profileBySession.clear(); promptHistory.clear(); modelBySession.clear(); worktreeBySession.clear(); titleBySession.clear(); attestationBySession.clear(); detailBySession.clear(); pendingQuestionBySession.clear(); answersByQuestion.clear(); rejectedQuestions.clear(); pendingPermissionsBySession.clear(); permissionRepliesByRequest.clear(); todosBySession.clear(); usageBySession.clear(); forkCalls.clear(); revertCalls.clear(); summarizeCalls.clear(); abortedSessionIds.clear(); failedReadOnlySessionsByRole.clear(); failedReadOnlySessions.set(0); failedReadOnlySessionCreations.set(0); failedPrompts.set(0); failedStructuredPrompts.set(0); failedAborts.set(0); failedAbortsByProfile.clear(); toolLoopStatusFailures.set(0); createSessionCalls.set(0); createReadOnlySessionCalls.set(0); promptCalls.set(0); heldProfiles.clear(); managedGeneration = null; managedInternalMcpServer = null; judgeOutput = "{\"verdict\":\"PASS\",\"reason\":\"确定性证据满足评审要求。\"}"; taskRouterOutputOverride = null; healthy = true; toolCapability = new ToolCapabilityProbe(CapabilityState.AVAILABLE, List.of("read", "glob", "grep", "question", "todowrite"), null); structuredCapability = new StructuredOutputCapability(CapabilityState.AVAILABLE, CapabilityState.AVAILABLE, null); }

    private void requireCurrentPlan(SessionCreationPlan plan) {
        if (plan == null) throw new SessionFailure("OPENCODE_SESSION_CREATION_PLAN_INVALID",
                "Session creation plan is required");
        boolean managed = managedGeneration != null && !managedGeneration.isBlank();
        String generation = managed ? managedGeneration : "external-" + FAKE_ENDPOINT_FINGERPRINT;
        if (plan.managed() != managed
                || !java.util.Objects.equals(plan.runtimeGenerationId(), generation)
                || !java.util.Objects.equals(plan.endpointFingerprint(), FAKE_ENDPOINT_FINGERPRINT)
                || !java.util.Objects.equals(plan.internalMcpServer(), managed ? managedInternalMcpServer : null)) {
            throw new SessionFailure("OPENCODE_SESSION_CREATION_PLAN_STALE",
                    "The frozen session creation endpoint or runtime generation has changed");
        }
        List<SessionPermissionRule> current = permissionRules(plan.profile(),
                managed ? managedInternalMcpServer : null);
        if (!current.equals(plan.permissionPolicy())
                || !OpenCodeClient.permissionPolicyDigest(current).equals(plan.permissionPolicyDigest())
                || !OpenCodeClient.sessionCreationRequestSha256(plan).equals(plan.createRequestSha256())) {
            throw new SessionFailure("OPENCODE_SESSION_CREATION_PLAN_STALE",
                    "The frozen session creation model, profile, or permission request has changed");
        }
    }

    private static List<SessionPermissionRule> permissionRules(SessionProfile profile, String internalMcpServer) {
        return OpenCodePermissionPolicy.rules(profile, List.of(), internalMcpServer).stream()
                .map(rule -> new SessionPermissionRule(rule.get("permission"), rule.get("pattern"), rule.get("action")))
                .toList();
    }

    private static String creationRequestDigest(Path canonical, String exactTitle, String generation,
            boolean managed, String internalMcpServer, OpenCodeModel model, SessionProfile profile,
            String permissionDigest, String credential) {
        return OpenCodeClient.sessionCreationRequestSha256(canonical, exactTitle, generation, managed,
                internalMcpServer, FAKE_ENDPOINT_FINGERPRINT, model, profile, permissionDigest, credential);
    }

    private static SessionAttestation attestation(String sessionId, SessionCreationPlan plan) {
        return new SessionAttestation(sessionId, plan.canonicalDirectory(), plan.exactTitle(),
                plan.runtimeGenerationId(), plan.managed(), plan.internalMcpServer(),
                plan.endpointFingerprint(), plan.model(), plan.profile(), plan.permissionPolicy(),
                plan.permissionPolicyDigest(), plan.creationCredential(), plan.createRequestSha256(),
                SessionAttestationKind.LOCAL_REQUEST_ATTESTED);
    }

    private SessionAttestation recoveredAttestation(String sessionId, SessionCreationPlan plan) {
        SessionAttestation existing = attestationBySession.get(sessionId);
        if (existing != null) return existing;
        if (!java.util.Objects.equals(modelBySession.get(sessionId), plan.model())
                || !java.util.Objects.equals(profileBySession.get(sessionId), plan.profile())) {
            throw new SessionFailure("OPENCODE_SESSION_LOOKUP_INVALID_RESPONSE",
                    "Exact-title fake session does not match the frozen model and profile request");
        }
        SessionAttestation recovered = attestation(sessionId, plan);
        attestationBySession.putIfAbsent(sessionId, recovered);
        return attestationBySession.get(sessionId);
    }

    private static boolean candidateProfile(SessionProfile profile) {
        return profile == SessionProfile.DECOMPOSER_CANDIDATE_READ_ONLY
                || profile == SessionProfile.PACKAGE_DESIGN_CANDIDATE_READ_ONLY
                || profile == SessionProfile.PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY
                || profile == SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS
                || profile == SessionProfile.ROLLING_PACKAGE_CANDIDATE_READ_ONLY
                || profile == SessionProfile.REVIEWER_CANDIDATE_READ_ONLY
                || profile == SessionProfile.PROJECT_CONVENTION_CANDIDATE_READ_ONLY
                || profile == SessionProfile.JUDGE_CANDIDATE_READ_ONLY;
    }
    private OpenCodeSession session(String id, Path worktree) {
        if (managedGeneration != null && !managedGeneration.isBlank()
                && managedInternalMcpServer != null && !managedInternalMcpServer.isBlank()) {
            runtimeBindings.register(new OpenCodeSessionRuntimeBindings.Binding(
                    id, managedGeneration, OpenCodeSessionRuntimeBindings.OwnershipMode.MANAGED,
                    FAKE_ENDPOINT_FINGERPRINT, managedInternalMcpServer));
        } else {
            runtimeBindings.register(new OpenCodeSessionRuntimeBindings.Binding(
                    id, "external-" + FAKE_ENDPOINT_FINGERPRINT,
                    OpenCodeSessionRuntimeBindings.OwnershipMode.EXTERNAL,
                    FAKE_ENDPOINT_FINGERPRINT, null));
        }
        return new OpenCodeSession(id, worktree, managedGeneration, managedInternalMcpServer);
    }
    public record PermissionReplyCall(String sessionId, String requestId, PermissionReply reply, String message) { }
    public record PromptCall(String sessionId, String prompt) { }
    public record ForkCall(String parentSessionId, String childSessionId, String messageId) { }
    public record RevertCall(String sessionId, String messageId, String partId) { }
    public record SummarizeCall(String sessionId, OpenCodeModel model, boolean automatic) { }
}
