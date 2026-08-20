package io.opencode.loopper.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.domain.LoopSpec;
import tools.jackson.databind.ObjectMapper;

/** Deterministic local implementation used in tests and development without a provider. */
public class FakeOpenCodeClient implements OpenCodeClient {
    private final ConcurrentHashMap<String, String> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> readOnly = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> judgeRoleBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> judgeOutputByRole = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> promptBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PromptRequest> promptRequestBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionProfile> profileBySession = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PromptCall> promptHistory = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, OpenCodeModel> modelBySession = new ConcurrentHashMap<>();
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
    private final AtomicInteger toolLoopStatusFailures = new AtomicInteger();
    private final CopyOnWriteArrayList<String> abortedSessionIds = new CopyOnWriteArrayList<>();
    private final AtomicInteger createSessionCalls = new AtomicInteger();
    private final AtomicInteger createReadOnlySessionCalls = new AtomicInteger();
    private final AtomicInteger promptCalls = new AtomicInteger();
    private volatile String judgeOutput = "{\"verdict\":\"PASS\",\"reason\":\"确定性证据满足评审要求。\"}";
    private volatile boolean healthy = true;
    private volatile ToolCapabilityProbe toolCapability = new ToolCapabilityProbe(CapabilityState.AVAILABLE,
            List.of("read", "glob", "grep", "todowrite"), null);
    private volatile StructuredOutputCapability structuredCapability = new StructuredOutputCapability(
            CapabilityState.AVAILABLE, CapabilityState.AVAILABLE, null);
    @Override public boolean healthy() { return healthy; }
    @Override public OpenCodeSession createSession(Path worktree, String title, OpenCodeModel model) {
        createSessionCalls.incrementAndGet();
        String id = "fake-" + UUID.randomUUID();
        states.put(id, "RUNNING");
        profileBySession.put(id, SessionProfile.IMPLEMENTATION);
        if (model != null) modelBySession.put(id, model);
        return new OpenCodeSession(id, worktree);
    }
    @Override public OpenCodeSession createReadOnlySession(Path worktree, String title, OpenCodeModel model) {
        createReadOnlySessionCalls.incrementAndGet();
        if (failedReadOnlySessionCreations.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new SessionFailure("OPENCODE_SESSION_CREATE_FAILED", "Deterministic read-only Session creation failure");
        }
        String id = "fake-judge-" + UUID.randomUUID();
        readOnly.put(id, Boolean.TRUE);
        profileBySession.put(id, SessionProfile.GENERAL_READ_ONLY);
        String normalizedTitle = title == null ? "" : title.toUpperCase();
        String packageId = java.util.regex.Pattern.compile("\\bWP-\\d+\\b").matcher(normalizedTitle).results()
                .map(java.util.regex.MatchResult::group).findFirst().orElse(null);
        String role = normalizedTitle.contains("TASK ROUTER") ? "ROUTER"
                : normalizedTitle.contains("REVIEWER") ? "REVIEWER"
                : normalizedTitle.contains("TASK DECOMPOSER") ? "DECOMPOSER"
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
        return new OpenCodeSession(id, worktree);
    }
    @Override public OpenCodeSession createSession(Path worktree, String title, OpenCodeModel model,
                                                    SessionProfile profile) {
        OpenCodeSession session = profile == null || profile == SessionProfile.IMPLEMENTATION
                ? createSession(worktree, title, model) : createReadOnlySession(worktree, title, model);
        profileBySession.put(session.id(), profile == null ? SessionProfile.IMPLEMENTATION : profile);
        return session;
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
        promptCalls.incrementAndGet();
        promptBySession.put(session.id(), prompt.text());
        promptRequestBySession.put(session.id(), prompt);
        promptHistory.add(new PromptCall(session.id(), prompt.text()));
        if (failedPrompts.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new SessionFailure("OPENCODE_PROMPT_FAILED", "Deterministic Designer prompt transport failure");
        }
        states.computeIfPresent(session.id(), (id, state) -> "FAILED".equals(state) ? state : "COMPLETED");
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
            return explicitPlan == null ? decompositionPlanningOutput(outputForRole(role)) : explicitPlan;
        }
        if (prompt.contains("LOOPSPEC_COMPILATION_PLAN_JSON_START")) {
            String planRole = role == null ? "COMPILER_PLAN" : role.replaceFirst("^COMPILER", "COMPILER_PLAN");
            String explicitPlan = judgeOutputByRole.get(planRole);
            return explicitPlan == null ? packageCompilationPlanningOutput(outputForRole(role)) : explicitPlan;
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
        String text = prompt == null ? "" : prompt.toLowerCase(java.util.Locale.ROOT);
        int requirementStart = text.indexOf("requirement:");
        int evidenceStart = text.indexOf("server-observed repository facts");
        if (requirementStart >= 0 && evidenceStart > requirementStart) {
            text = text.substring(requirementStart + "requirement:".length(), evidenceStart);
        }
        String intent = "SOFTWARE_CHANGE";
        String artifacts = "[\"SOURCE_CODE\"]";
        String technologies = "[]";
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
        if (text.contains("python") || text.contains("py脚本")) {
            technologies = "[\"python\"]"; if (intent.equals("SOFTWARE_CHANGE")) artifacts = "[\"PYTHON_SCRIPT\"]";
        } else if (text.contains("vue") || text.contains("node") || text.contains("typescript")) technologies = "[\"node\"]";
        else if (text.contains("java") || text.contains("maven") || text.contains("spring")) technologies = "[\"java\"]";
        return "{\"intent\":\"" + intent + "\",\"artifactKinds\":" + artifacts
                + ",\"technologies\":" + technologies + ",\"complexity\":\"" + complexity
                + "\",\"confidence\":92,\"signals\":[\"fake-router\"]}";
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
                new SessionPart("fake-output", "OUTPUT", "模型输出", output, states.get(session.id()))));
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
        return new OpenCodeSession(childId, session.worktree());
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
        if (failedAborts.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
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
        setJudgeOutput("DESIGNER", designerMarkdown(output));
        String compatibilityCompilation = compatibilityCompilation(output);
        if (compatibilityCompilation != null) {
            setCompilerOutput(compatibilityCompilation);
            setDecomposerOutput(directDecomposition(output));
        }
    }
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
    public void failNextPrompts(int count) { failedPrompts.set(Math.max(0, count)); }
    public void failNextStructuredPrompts(int count) { failedStructuredPrompts.set(Math.max(0, count)); }
    public void failNextAborts(int count) { failedAborts.set(Math.max(0, count)); }
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
    public void setSessionUsage(String sessionId, List<UsageRecord> usage) {
        usageBySession.put(sessionId, usage == null ? List.of() : List.copyOf(usage));
    }
    public List<ForkCall> forkCalls() { return List.copyOf(forkCalls); }
    public List<RevertCall> revertCalls() { return List.copyOf(revertCalls); }
    public List<SummarizeCall> summarizeCalls() { return List.copyOf(summarizeCalls); }
    public void failNextReadOnlySessions(int count) { failedReadOnlySessions.set(Math.max(0, count)); }
    public void failNextReadOnlySessionCreations(int count) { failedReadOnlySessionCreations.set(Math.max(0, count)); }
    public void failNextReadOnlySessions(String role, int count) { failedReadOnlySessionsByRole.put(role.toUpperCase(), new AtomicInteger(Math.max(0, count))); }
    public void reset() { states.clear(); readOnly.clear(); judgeRoleBySession.clear(); judgeOutputByRole.clear(); promptBySession.clear(); promptRequestBySession.clear(); profileBySession.clear(); promptHistory.clear(); modelBySession.clear(); detailBySession.clear(); pendingQuestionBySession.clear(); answersByQuestion.clear(); rejectedQuestions.clear(); pendingPermissionsBySession.clear(); permissionRepliesByRequest.clear(); todosBySession.clear(); usageBySession.clear(); forkCalls.clear(); revertCalls.clear(); summarizeCalls.clear(); abortedSessionIds.clear(); failedReadOnlySessionsByRole.clear(); failedReadOnlySessions.set(0); failedReadOnlySessionCreations.set(0); failedPrompts.set(0); failedStructuredPrompts.set(0); failedAborts.set(0); toolLoopStatusFailures.set(0); createSessionCalls.set(0); createReadOnlySessionCalls.set(0); promptCalls.set(0); judgeOutput = "{\"verdict\":\"PASS\",\"reason\":\"确定性证据满足评审要求。\"}"; healthy = true; toolCapability = new ToolCapabilityProbe(CapabilityState.AVAILABLE, List.of("read", "glob", "grep", "todowrite"), null); structuredCapability = new StructuredOutputCapability(CapabilityState.AVAILABLE, CapabilityState.AVAILABLE, null); }
    private String designerMarkdown(String output) {
        if (output == null) return null;
        return output.replaceAll("(?is)<!--\\s*LOOPSPEC_JSON_START\\s*-->.*?<!--\\s*LOOPSPEC_JSON_END\\s*-->", "").trim();
    }
    private String compatibilityCompilation(String output) {
        if (output == null) return null;
        java.util.regex.Matcher marker = java.util.regex.Pattern.compile(
                "(?is)<!--\\s*LOOPSPEC_JSON_START\\s*-->(.*?)<!--\\s*LOOPSPEC_JSON_END\\s*-->").matcher(output);
        if (!marker.find()) return null;
        String payload = marker.group(1);
        int start = payload.indexOf('{');
        int end = payload.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            ObjectMapper mapper = new ObjectMapper();
            String specPayload = payload.substring(start, end + 1);
            LoopSpec spec = mapper.readValue(specPayload, LoopSpec.class);
            tools.jackson.databind.node.ObjectNode specNode = (tools.jackson.databind.node.ObjectNode) mapper.readTree(specPayload);
            String excerpt = designerMarkdown(output);
            if (excerpt == null || excerpt.isBlank()) excerpt = "设计稿";
            java.util.List<java.util.Map<String, Object>> sources = new java.util.ArrayList<>();
            tools.jackson.databind.node.ArrayNode stageNodes = (tools.jackson.databind.node.ArrayNode) specNode.get("stages");
            for (int stageIndex = 0; stageIndex < stageNodes.size(); stageIndex++) {
                tools.jackson.databind.node.ObjectNode stage = (tools.jackson.databind.node.ObjectNode) stageNodes.get(stageIndex);
                stage.put("workPackageId", "WP-1");
                tools.jackson.databind.JsonNode criteria = stage.get("acceptanceCriteria");
                if (criteria != null && criteria.isArray()) for (tools.jackson.databind.JsonNode value : criteria) {
                    tools.jackson.databind.node.ObjectNode criterion = (tools.jackson.databind.node.ObjectNode) value;
                    String original = criterion.path("id").asText();
                    String mapped = original.startsWith("WP-1-") ? original : "WP-1-" + original;
                    criterion.put("id", mapped);
                    sources.add(java.util.Map.of("stageIndex", stageIndex, "criterionId", mapped, "excerpt", excerpt));
                }
                tools.jackson.databind.JsonNode verifiers = stage.get("verifiers");
                if (verifiers != null && verifiers.isArray()) for (tools.jackson.databind.JsonNode value : verifiers) {
                    tools.jackson.databind.node.ObjectNode verifier = (tools.jackson.databind.node.ObjectNode) value;
                    tools.jackson.databind.JsonNode ids = verifier.get("criterionIds");
                    if (ids != null && ids.isArray()) {
                        tools.jackson.databind.node.ArrayNode mapped = mapper.createArrayNode();
                        for (tools.jackson.databind.JsonNode id : ids) {
                            String original = id.asText();
                            mapped.add(original.startsWith("WP-1-") ? original : "WP-1-" + original);
                        }
                        verifier.set("criterionIds", mapped);
                    }
                }
            }
            java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("status", "COMPILED");
            envelope.put("summary", "LoopSpec 已由测试用只读规范编译器生成。");
            envelope.put("stages", stageNodes);
            envelope.put("criterionSources", sources);
            envelope.put("handoffSummary", "WP-1 已完成，可执行聚合后的后续阶段。");
            envelope.put("designGaps", java.util.List.of());
            return "<!-- LOOPSPEC_COMPILATION_JSON_START -->\n```json\n"
                    + mapper.writeValueAsString(envelope)
                    + "\n```\n<!-- LOOPSPEC_COMPILATION_JSON_END -->";
        } catch (Exception invalid) {
            return null;
        }
    }
    private String directDecomposition(String output) {
        String goal = "设计并交付当前需求";
        try {
            java.util.regex.Matcher marker = java.util.regex.Pattern.compile(
                    "(?is)<!--\\s*LOOPSPEC_JSON_START\\s*-->(.*?)<!--\\s*LOOPSPEC_JSON_END\\s*-->").matcher(output);
            if (marker.find()) {
                LoopSpec spec = new ObjectMapper().readValue(marker.group(1).replace("```json", "").replace("```", "").trim(), LoopSpec.class);
                goal = spec.goal();
            }
            java.util.Map<String, Object> workPackage = new java.util.LinkedHashMap<>();
            workPackage.put("id", "WP-1");
            workPackage.put("title", "完整需求交付");
            workPackage.put("objective", goal);
            workPackage.put("scopeIn", java.util.List.of("当前需求涉及的业务能力"));
            workPackage.put("scopeOut", java.util.List.of("独立项目根和独立发布边界"));
            workPackage.put("dependencies", java.util.List.of());
            workPackage.put("deliverables", java.util.List.of("可验证实现"));
            workPackage.put("acceptanceIntent", java.util.List.of("需求中的可观察结果通过确定性验证"));
            String markdown = designerMarkdown(output);
            int segmentCount = markdown == null ? 1 : Math.max(1, (int) java.util.Arrays.stream(
                            markdown.replace("\r\n", "\n").replace('\r', '\n').split("\\n\\s*\\n"))
                    .map(String::trim).filter(value -> !value.isBlank()).count());
            java.util.List<String> requirementRefs = java.util.stream.IntStream.rangeClosed(1, segmentCount)
                    .mapToObj(index -> "RQ-" + index).toList();
            workPackage.put("requirementRefs", requirementRefs);
            java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("status", "DIRECT_DESIGN");
            envelope.put("normalizedGoal", goal);
            envelope.put("globalConstraints", java.util.List.of());
            envelope.put("workPackages", java.util.List.of(workPackage));
            envelope.put("designGaps", java.util.List.of());
            envelope.put("reason", null);
            return "<!-- TASK_DECOMPOSITION_JSON_START -->\n" + new ObjectMapper().writeValueAsString(envelope)
                    + "\n<!-- TASK_DECOMPOSITION_JSON_END -->";
        } catch (Exception invalid) {
            return "<!-- TASK_DECOMPOSITION_JSON_START -->\n{}\n<!-- TASK_DECOMPOSITION_JSON_END -->";
        }
    }
    private String decompositionPlanningOutput(String finalOutput) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            tools.jackson.databind.node.ObjectNode source = markedObject(finalOutput,
                    "TASK_DECOMPOSITION_JSON_START", "TASK_DECOMPOSITION_JSON_END", mapper);
            tools.jackson.databind.node.ObjectNode plan = mapper.createObjectNode();
            plan.set("status", source.path("status"));
            plan.set("normalizedGoal", source.path("normalizedGoal"));
            tools.jackson.databind.node.ArrayNode constraints = source.path("globalConstraints").isArray()
                    ? (tools.jackson.databind.node.ArrayNode) source.path("globalConstraints") : mapper.createArrayNode();
            tools.jackson.databind.node.ArrayNode packages = source.path("workPackages").isArray()
                    ? (tools.jackson.databind.node.ArrayNode) source.path("workPackages") : mapper.createArrayNode();
            plan.set("globalConstraints", constraints);
            plan.set("workPackages", packages);
            tools.jackson.databind.node.ArrayNode coverage = mapper.createArrayNode();
            for (int index = 0; index < constraints.size(); index++) {
                tools.jackson.databind.JsonNode constraint = constraints.get(index);
                for (tools.jackson.databind.JsonNode ref : constraint.path("requirementRefs")) {
                    tools.jackson.databind.node.ObjectNode mapping = coverage.addObject();
                    mapping.put("requirementRef", ref.asText());
                    mapping.put("targetType", "GLOBAL_CONSTRAINT");
                    mapping.put("targetId", "GC-" + (index + 1));
                    mapping.put("rationale", "测试规划将该需求段归入对应全局约束。");
                }
            }
            tools.jackson.databind.node.ArrayNode dependencies = mapper.createArrayNode();
            for (tools.jackson.databind.JsonNode workPackage : packages) {
                for (tools.jackson.databind.JsonNode ref : workPackage.path("requirementRefs")) {
                    tools.jackson.databind.node.ObjectNode mapping = coverage.addObject();
                    mapping.put("requirementRef", ref.asText());
                    mapping.put("targetType", "WORK_PACKAGE");
                    mapping.put("targetId", workPackage.path("id").asText());
                    mapping.put("rationale", "测试规划将该需求段归入当前纵向能力包。");
                }
                for (tools.jackson.databind.JsonNode dependency : workPackage.path("dependencies")) {
                    tools.jackson.databind.node.ObjectNode evidence = dependencies.addObject();
                    evidence.put("workPackageId", workPackage.path("id").asText());
                    evidence.put("dependsOn", dependency.asText());
                    evidence.put("rationale", "当前包使用前置包的已交付能力。");
                }
            }
            plan.set("coverageMappings", coverage);
            plan.set("dependencyEvidence", dependencies);
            plan.set("designGaps", source.path("designGaps").isArray()
                    ? source.path("designGaps") : mapper.createArrayNode());
            plan.set("reason", source.path("reason"));
            return "<!-- TASK_DECOMPOSITION_PLAN_JSON_START -->\n" + mapper.writeValueAsString(plan)
                    + "\n<!-- TASK_DECOMPOSITION_PLAN_JSON_END -->";
        } catch (Exception invalid) {
            return "<!-- TASK_DECOMPOSITION_PLAN_JSON_START -->\n{}\n<!-- TASK_DECOMPOSITION_PLAN_JSON_END -->";
        }
    }

    private String packageCompilationPlanningOutput(String finalOutput) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            tools.jackson.databind.node.ObjectNode source = markedObject(finalOutput,
                    "LOOPSPEC_COMPILATION_JSON_START", "LOOPSPEC_COMPILATION_JSON_END", mapper);
            tools.jackson.databind.node.ObjectNode plan = mapper.createObjectNode();
            boolean v2 = source.path("stages").isArray()
                    && java.util.stream.StreamSupport.stream(source.path("stages").spliterator(), false)
                    .anyMatch(stage -> !stage.path("implementationKind").isMissingNode()
                            && !stage.path("implementationKind").isNull());
            plan.put("contractVersion", v2 ? 2 : 1);
            plan.set("status", source.path("status"));
            plan.set("summary", source.path("summary"));
            tools.jackson.databind.node.ArrayNode stagePlans = mapper.createArrayNode();
            tools.jackson.databind.node.ArrayNode evidenceMappings = mapper.createArrayNode();
            tools.jackson.databind.JsonNode stages = source.path("stages");
            for (int stageIndex = 0; stages.isArray() && stageIndex < stages.size(); stageIndex++) {
                tools.jackson.databind.JsonNode stage = stages.get(stageIndex);
                tools.jackson.databind.node.ObjectNode stagePlan = stagePlans.addObject();
                stagePlan.set("objective", stage.path("objective"));
                stagePlan.set("allowedPaths", stage.path("allowedPaths"));
                stagePlan.set("forbiddenPaths", stage.path("forbiddenPaths"));
                stagePlan.set("deliverables", stage.path("deliverables"));
                stagePlan.set("verifiers", stage.path("verifiers"));
                stagePlan.set("verificationRuntime", stage.path("verificationRuntime"));
                stagePlan.set("implementationKind", stage.path("implementationKind"));
                stagePlan.set("workPackageId", stage.path("workPackageId"));
                tools.jackson.databind.JsonNode criteria = stage.path("acceptanceCriteria");
                for (tools.jackson.databind.JsonNode criterion : criteria) {
                    String criterionId = criterion.path("id").asText();
                    tools.jackson.databind.node.ObjectNode mapping = evidenceMappings.addObject();
                    mapping.put("stageIndex", stageIndex);
                    mapping.set("criterionId", criterion.path("id"));
                    mapping.set("description", criterion.path("description"));
                    String excerpt = "设计稿";
                    for (tools.jackson.databind.JsonNode sourceEntry : source.path("criterionSources")) {
                        if (sourceEntry.path("stageIndex").asInt() == stageIndex
                                && criterionId.equals(sourceEntry.path("criterionId").asText())) {
                            excerpt = sourceEntry.path("excerpt").asText();
                            break;
                        }
                    }
                    mapping.put("designerExcerpt", excerpt);
                    mapping.put("verificationMode", criterion.path("verificationMode").asText("MACHINE"));
                    mapping.set("judgeRubric", criterion.path("judgeRubric"));
                    mapping.set("judgeOnlyReason", criterion.path("judgeOnlyReason"));
                    tools.jackson.databind.node.ArrayNode testCommand = mapper.createArrayNode();
                    tools.jackson.databind.node.ArrayNode testTargets = mapper.createArrayNode();
                    String strategy = "deterministic verifier";
                    for (tools.jackson.databind.JsonNode verifier : stage.path("verifiers")) {
                        boolean mapped = false;
                        for (tools.jackson.databind.JsonNode id : verifier.path("criterionIds")) {
                            if (criterionId.equals(id.asText())) mapped = true;
                        }
                        if (!mapped) continue;
                        strategy = verifier.path("type").asText("deterministic verifier");
                        if ("PROCESS".equals(verifier.path("type").asText())
                                && "TEST".equals(verifier.path("processPurpose").asText())) {
                            for (tools.jackson.databind.JsonNode value : verifier.path("command")) testCommand.add(value.asText());
                            for (tools.jackson.databind.JsonNode value : verifier.path("testTargets")) testTargets.add(value.asText());
                        }
                        break;
                    }
                    mapping.put("verifierStrategy", strategy);
                    mapping.set("testCommand", testCommand);
                    mapping.set("testTargets", testTargets);
                }
            }
            plan.set("stages", stagePlans);
            plan.set("evidenceMappings", evidenceMappings);
            plan.set("handoffSummary", source.path("handoffSummary"));
            plan.set("designGaps", source.path("designGaps").isArray()
                    ? source.path("designGaps") : mapper.createArrayNode());
            return "<!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->\n" + mapper.writeValueAsString(plan)
                    + "\n<!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->";
        } catch (Exception invalid) {
            return "<!-- LOOPSPEC_COMPILATION_PLAN_JSON_START -->\n{}\n<!-- LOOPSPEC_COMPILATION_PLAN_JSON_END -->";
        }
    }

    private tools.jackson.databind.node.ObjectNode markedObject(String output, String startMarker,
                                                                String endMarker, ObjectMapper mapper) throws Exception {
        if (output == null) throw new IllegalArgumentException("missing output");
        int markerStart = output.indexOf(startMarker);
        int markerEnd = output.indexOf(endMarker);
        if (markerStart < 0 || markerEnd <= markerStart) throw new IllegalArgumentException("missing markers");
        String body = output.substring(markerStart + startMarker.length(), markerEnd);
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("missing object");
        return (tools.jackson.databind.node.ObjectNode) mapper.readTree(body.substring(start, end + 1));
    }
    public record PermissionReplyCall(String sessionId, String requestId, PermissionReply reply, String message) { }
    public record PromptCall(String sessionId, String prompt) { }
    public record ForkCall(String parentSessionId, String childSessionId, String messageId) { }
    public record RevertCall(String sessionId, String messageId, String partId) { }
    public record SummarizeCall(String sessionId, OpenCodeModel model, boolean automatic) { }
}
