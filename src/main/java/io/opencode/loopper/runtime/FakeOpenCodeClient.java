package io.opencode.loopper.runtime;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import io.opencode.loopper.domain.SessionFailure;

/** Deterministic local implementation used in tests and development without a provider. */
public class FakeOpenCodeClient implements OpenCodeClient {
    private final ConcurrentHashMap<String, String> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> readOnly = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> judgeRoleBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> judgeOutputByRole = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OpenCodeModel> modelBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> detailBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> failedReadOnlySessionsByRole = new ConcurrentHashMap<>();
    private final AtomicInteger failedReadOnlySessions = new AtomicInteger();
    private final AtomicInteger failedPrompts = new AtomicInteger();
    private final AtomicInteger failedAborts = new AtomicInteger();
    private volatile String judgeOutput = "{\"verdict\":\"PASS\",\"reason\":\"Deterministic evidence satisfies the review contract.\"}";
    private volatile boolean healthy = true;
    @Override public boolean healthy() { return healthy; }
    @Override public OpenCodeSession createSession(Path worktree, String title, OpenCodeModel model) { String id = "fake-" + UUID.randomUUID(); states.put(id, "RUNNING"); return new OpenCodeSession(id, worktree); }
    @Override public OpenCodeSession createReadOnlySession(Path worktree, String title, OpenCodeModel model) {
        String id = "fake-judge-" + UUID.randomUUID();
        readOnly.put(id, Boolean.TRUE);
        String role = title != null && title.toUpperCase().contains("DESIGNER") ? "DESIGNER"
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
        if (failedPrompts.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new SessionFailure("OPENCODE_PROMPT_FAILED", "Deterministic Designer prompt transport failure");
        }
        states.computeIfPresent(session.id(), (id, state) -> "FAILED".equals(state) ? state : "COMPLETED");
    }
    @Override public SessionStatus sessionStatus(OpenCodeSession session) {
        return new SessionStatus(states.getOrDefault(session.id(), "FAILED"), detailBySession.get(session.id()));
    }
    @Override public String sessionOutput(OpenCodeSession session) { return judgeOutputByRole.getOrDefault(judgeRoleBySession.get(session.id()), judgeOutput); }
    @Override public String diff(OpenCodeSession session) { return "[]"; }
    @Override public void abort(OpenCodeSession session) {
        if (failedAborts.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new SessionFailure("OPENCODE_ABORT_FAILED", "Deterministic abort transport failure");
        }
        states.put(session.id(), "ABORTED");
    }
    public boolean isReadOnlySession(String id) { return Boolean.TRUE.equals(readOnly.get(id)); }
    public void setJudgeOutput(String output) { judgeOutput = output; }
    public void setJudgeOutput(String role, String output) { judgeOutputByRole.put(role.toUpperCase(), output); }
    public void setDesignerOutput(String output) { setJudgeOutput("DESIGNER", output); }
    public void setHealthy(boolean value) { healthy = value; }
    public OpenCodeModel modelForSession(String id) { return modelBySession.get(id); }
    public void failNextPrompts(int count) { failedPrompts.set(Math.max(0, count)); }
    public void failNextAborts(int count) { failedAborts.set(Math.max(0, count)); }
    public void setSessionState(String id, String state) { states.put(id, state); detailBySession.remove(id); }
    public void setSessionStatus(String id, String state, String detail) {
        states.put(id, state);
        if (detail == null || detail.isBlank()) detailBySession.remove(id); else detailBySession.put(id, detail);
    }
    public void failNextReadOnlySessions(int count) { failedReadOnlySessions.set(Math.max(0, count)); }
    public void failNextReadOnlySessions(String role, int count) { failedReadOnlySessionsByRole.put(role.toUpperCase(), new AtomicInteger(Math.max(0, count))); }
    public void reset() { states.clear(); readOnly.clear(); judgeRoleBySession.clear(); judgeOutputByRole.clear(); modelBySession.clear(); detailBySession.clear(); failedReadOnlySessionsByRole.clear(); failedReadOnlySessions.set(0); failedPrompts.set(0); failedAborts.set(0); judgeOutput = "{\"verdict\":\"PASS\",\"reason\":\"Deterministic evidence satisfies the review contract.\"}"; healthy = true; }
}
