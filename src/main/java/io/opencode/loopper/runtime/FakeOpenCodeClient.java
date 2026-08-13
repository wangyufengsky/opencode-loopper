package io.opencode.loopper.runtime;

import java.nio.file.Path;
import java.util.List;
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
    private final AtomicInteger failedAborts = new AtomicInteger();
    private final AtomicInteger createSessionCalls = new AtomicInteger();
    private final AtomicInteger createReadOnlySessionCalls = new AtomicInteger();
    private final AtomicInteger promptCalls = new AtomicInteger();
    private volatile String judgeOutput = "{\"verdict\":\"PASS\",\"reason\":\"确定性证据满足评审要求。\"}";
    private volatile boolean healthy = true;
    @Override public boolean healthy() { return healthy; }
    @Override public OpenCodeSession createSession(Path worktree, String title, OpenCodeModel model) { createSessionCalls.incrementAndGet(); String id = "fake-" + UUID.randomUUID(); states.put(id, "RUNNING"); return new OpenCodeSession(id, worktree); }
    @Override public OpenCodeSession createReadOnlySession(Path worktree, String title, OpenCodeModel model) {
        createReadOnlySessionCalls.incrementAndGet();
        if (failedReadOnlySessionCreations.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new SessionFailure("OPENCODE_SESSION_CREATE_FAILED", "Deterministic read-only Session creation failure");
        }
        String id = "fake-judge-" + UUID.randomUUID();
        readOnly.put(id, Boolean.TRUE);
        String role = title != null && title.toUpperCase().contains("COMMIT MESSAGE") ? "COMMIT"
                : title != null && title.toUpperCase().contains("LOOPSPEC COMPILER") ? "COMPILER"
                : title != null && title.toUpperCase().contains("DESIGNER") ? "DESIGNER"
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
    @Override public void promptAsync(OpenCodeSession session, String prompt) {
        promptCalls.incrementAndGet();
        promptBySession.put(session.id(), prompt);
        if (failedPrompts.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new SessionFailure("OPENCODE_PROMPT_FAILED", "Deterministic Designer prompt transport failure");
        }
        states.computeIfPresent(session.id(), (id, state) -> "FAILED".equals(state) ? state : "COMPLETED");
    }
    @Override public SessionStatus sessionStatus(OpenCodeSession session) {
        return new SessionStatus(states.getOrDefault(session.id(), "FAILED"), detailBySession.get(session.id()));
    }
    @Override public String sessionOutput(OpenCodeSession session) { return judgeOutputByRole.getOrDefault(judgeRoleBySession.get(session.id()), judgeOutput); }
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
        states.put(session.id(), "ABORTED");
    }
    public boolean isReadOnlySession(String id) { return Boolean.TRUE.equals(readOnly.get(id)); }
    public int createSessionCalls() { return createSessionCalls.get(); }
    public int createReadOnlySessionCalls() { return createReadOnlySessionCalls.get(); }
    public int promptCalls() { return promptCalls.get(); }
    public void setJudgeOutput(String output) { judgeOutput = output; }
    public void setJudgeOutput(String role, String output) { judgeOutputByRole.put(role.toUpperCase(), output); }
    public void setDesignerOutput(String output) {
        setJudgeOutput("DESIGNER", designerMarkdown(output));
        String compatibilityCompilation = compatibilityCompilation(output);
        if (compatibilityCompilation != null) setCompilerOutput(compatibilityCompilation);
    }
    public void setCompilerOutput(String output) { setJudgeOutput("COMPILER", output); }
    public void setHealthy(boolean value) { healthy = value; }
    public OpenCodeModel modelForSession(String id) { return modelBySession.get(id); }
    public String promptForSession(String id) { return promptBySession.get(id); }
    public void failNextPrompts(int count) { failedPrompts.set(Math.max(0, count)); }
    public void failNextAborts(int count) { failedAborts.set(Math.max(0, count)); }
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
    public void setSessionUsage(String sessionId, List<UsageRecord> usage) {
        usageBySession.put(sessionId, usage == null ? List.of() : List.copyOf(usage));
    }
    public List<ForkCall> forkCalls() { return List.copyOf(forkCalls); }
    public List<RevertCall> revertCalls() { return List.copyOf(revertCalls); }
    public List<SummarizeCall> summarizeCalls() { return List.copyOf(summarizeCalls); }
    public void failNextReadOnlySessions(int count) { failedReadOnlySessions.set(Math.max(0, count)); }
    public void failNextReadOnlySessionCreations(int count) { failedReadOnlySessionCreations.set(Math.max(0, count)); }
    public void failNextReadOnlySessions(String role, int count) { failedReadOnlySessionsByRole.put(role.toUpperCase(), new AtomicInteger(Math.max(0, count))); }
    public void reset() { states.clear(); readOnly.clear(); judgeRoleBySession.clear(); judgeOutputByRole.clear(); promptBySession.clear(); modelBySession.clear(); detailBySession.clear(); pendingQuestionBySession.clear(); answersByQuestion.clear(); rejectedQuestions.clear(); pendingPermissionsBySession.clear(); permissionRepliesByRequest.clear(); todosBySession.clear(); usageBySession.clear(); forkCalls.clear(); revertCalls.clear(); summarizeCalls.clear(); failedReadOnlySessionsByRole.clear(); failedReadOnlySessions.set(0); failedReadOnlySessionCreations.set(0); failedPrompts.set(0); failedAborts.set(0); createSessionCalls.set(0); createReadOnlySessionCalls.set(0); promptCalls.set(0); judgeOutput = "{\"verdict\":\"PASS\",\"reason\":\"确定性证据满足评审要求。\"}"; healthy = true; }
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
            LoopSpec spec = mapper.readValue(payload.substring(start, end + 1), LoopSpec.class);
            String excerpt = designerMarkdown(output);
            if (excerpt == null || excerpt.isBlank()) excerpt = "设计稿";
            java.util.List<java.util.Map<String, Object>> sources = new java.util.ArrayList<>();
            for (int stageIndex = 0; stageIndex < spec.stages().size(); stageIndex++) {
                for (LoopSpec.AcceptanceCriterion criterion : spec.stages().get(stageIndex).acceptanceCriteria()) {
                    sources.add(java.util.Map.of("stageIndex", stageIndex, "criterionId", criterion.id(),
                            "excerpt", excerpt));
                }
            }
            java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("status", "COMPILED");
            envelope.put("summary", "LoopSpec 已由测试用只读规范编译器生成。");
            envelope.put("loopSpec", spec);
            envelope.put("criterionSources", sources);
            envelope.put("designGaps", java.util.List.of());
            return "<!-- LOOPSPEC_COMPILATION_JSON_START -->\n```json\n"
                    + mapper.writeValueAsString(envelope)
                    + "\n```\n<!-- LOOPSPEC_COMPILATION_JSON_END -->";
        } catch (Exception invalid) {
            return null;
        }
    }
    public record PermissionReplyCall(String sessionId, String requestId, PermissionReply reply, String message) { }
    public record ForkCall(String parentSessionId, String childSessionId, String messageId) { }
    public record RevertCall(String sessionId, String messageId, String partId) { }
    public record SummarizeCall(String sessionId, OpenCodeModel model, boolean automatic) { }
}
