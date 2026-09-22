package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.PptAgentMapper;
import io.opencode.loopper.persistence.PptAgentRows.Run;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient.*;
import io.opencode.loopper.service.assist.AssistRedaction;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Durable single-writer dispatch; uncertain remote actions are inspected, never blindly repeated. */
@Service
public class PptAgentCoordinator {
    private final PptAgentMapper mapper;
    private final PptAgentPersistence persistence;
    private final OpenCodeClient openCode;
    private final ObjectMapper json;
    private final LoopperProperties properties;
    private final PptAgentWorkspace workspace;
    private final PptAgentAuthority authority;
    private final PptAgentActivity activity;
    private final io.opencode.loopper.service.ppt.PptEvents events;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
            Thread.ofPlatform().daemon().name("ppt-agent-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    public PptAgentCoordinator(PptAgentMapper mapper, PptAgentPersistence persistence, OpenCodeClient openCode,
            ObjectMapper json, LoopperProperties properties, PptAgentWorkspace workspace, PptAgentAuthority authority, io.opencode.loopper.service.ppt.PptEvents events, PptAgentActivity activity) {
        this.mapper = mapper; this.persistence = persistence; this.openCode = openCode; this.json = json;
        this.properties = properties; this.workspace = workspace; this.authority = authority; this.events = events;
        this.activity = activity;
    }
    public void enqueue(String document) {
        if (!running.add(document)) return;
        try { workers.execute(() -> { try { tick(document); } finally { running.remove(document); } }); }
        catch (RejectedExecutionException full) { running.remove(document); }
    }
    @Scheduled(fixedDelayString="${loopper.ppt-agent-monitor-delay:1000}")
    public void monitor() { mapper.activeDocuments().forEach(this::enqueue); }
    public void tick(String document) {
        var active = mapper.active(document); if (active.isEmpty()) return;
        try {
            var run = active.get();
            var retired = authority.retiredRuntimeProof(run);
            if (retired.isPresent()) { persistence.runtimeStopped(run, retired.get()); return; }
            if (run.state().equals("STOPPING")) { stop(run); return; }
            if (run.state().equals("WAITING_INPUT")) return;
            if (!PptAgentAuthority.phaseMatches(run.phase(), workspace.workspace(document).phase())) {
                persistence.stop(document, "PHASE_CHANGED"); return;
            }
            if (run.externalSessionId() == null) {
                if (run.state().equals("PREPARED") && run.planJson() == null) create(run); else recoverCreate(run);
                return;
            }
            var remote = restore(run);
            if (mapper.pending(run.id()).isPresent()) { persistence.stop(document, "INPUT"); return; }
            switch (PptAgentState.valueOf(run.state())) {
                case PREPARED, CREATING -> dispatch(remote, run);
                case SENDING, UNKNOWN -> recoverPrompt(remote, run);
                case RUNNING -> poll(remote, run);
                default -> { }
            }
        } catch (RuntimeException failure) {
            mapper.active(document).ifPresent(run -> {
                String detail = "连接或操作状态待核对，系统保留原请求，不会重复发送。可请求停止。";
                if (failure instanceof io.opencode.loopper.domain.SessionFailure safe && safe.code().contains("GENERATION"))
                    detail = "运行环境已变化，旧会话停止尚未得到证明；保留当前请求并检查原运行环境。";
                try { persistence.detail(run, detail); } catch (RuntimeException concurrentUpdate) { /* Next monitor re-reads authoritative state. */ }
            });
        } finally { events.publish(document, "agent"); }
    }
    private void create(Run run) {
        SessionCreationPlan plan;
        try {
            java.nio.file.Files.createDirectories(Path.of(run.rootPath()));
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            plan = openCode.prepareSessionCreation(Path.of(run.rootPath()), "Loopper PPT 助手", model(run),
                    SessionProfile.PPT_AGENT, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            if (!plan.managed() && !"fake".equals(properties.getOpenCode().getMode())) throw new IllegalStateException("PPT requires managed runtime");
        } catch (Exception unsent) {
            var current = persistence.require(run.id());
            if (current.state().equals("PREPARED")) persistence.proven(current, PptAgentState.FAILED, "NOT_DISPATCHED", "PPT 运行环境未就绪，请检查模型与受管 OpenCode 后重新发送");
            return;
        }
        persistence.creating(run, json.writeValueAsString(plan), plan.runtimeGenerationId());
        try {
            var attestation = openCode.createSession(plan);
            if (!plan.equals(attestation.plan())) throw new IllegalStateException("PPT session attestation mismatch");
            mapper.bind(run.id(), attestation.remoteId());
        } catch (RuntimeException unknown) {
            var current = persistence.require(run.id());
            if (current.state().equals("CREATING")) persistence.state(current, PptAgentState.CREATE_UNKNOWN, "会话创建待核对，未重复创建");
        }
    }
    private void recoverCreate(Run run) {
        if (run.planJson() == null) return;
        var lookup = openCode.findSessionsByExactTitle(plan(run));
        if (lookup.supported() && lookup.matches().size() == 1) {
            var attestation = lookup.matches().getFirst();
            if (!plan(run).equals(attestation.plan())) throw new IllegalStateException("Recovered PPT session identity mismatch");
            mapper.bind(run.id(), attestation.remoteId()); var fresh = persistence.require(run.id());
            if (fresh.state().equals("CREATE_UNKNOWN")) persistence.state(fresh, PptAgentState.CREATING, "已找回原 PPT 会话");
        }
    }
    private void dispatch(OpenCodeSession remote, Run run) {
        PromptRequest prompt = PptAgentPrompts.build(run, mapper.questions(run.id()),json);
        persistence.dispatch(run, json.writeValueAsString(prompt), OpenCodeClient.promptRequestSha256(prompt));
        if (!persistence.require(run.id()).state().equals("SENDING")) return;
        try {
            openCode.promptAsync(remote, prompt);
            var fresh = persistence.require(run.id());
            if (fresh.state().equals("SENDING")) persistence.state(fresh, PptAgentState.RUNNING, "");
        } catch (RuntimeException unknown) {
            var fresh = persistence.require(run.id());
            if (fresh.state().equals("SENDING")) persistence.state(fresh, PptAgentState.UNKNOWN, "投递结果待核对，未重复发送");
        }
    }
    private void recoverPrompt(OpenCodeSession remote, Run run) {
        var lookup = openCode.findPromptMessage(remote, PptAgentPrompts.restore(run, json), run.requestSha());
        if (lookup.supported() && lookup.exists() && run.requestSha().equals(lookup.verifiedRequestSha256()))
            persistence.state(run, PptAgentState.RUNNING, "已确认原请求");
    }
    private void poll(OpenCodeSession remote, Run run) {
        var status = openCode.sessionStatus(remote);
        activity.capture(openCode, remote, run);
        String answer = AssistRedaction.text(openCode.sessionLiveOutput(remote));
        if (answer != null && answer.length() > 500000) { persistence.stop(run.documentId(), "OUTPUT_LIMIT"); return; }
        if (answer != null && !answer.isBlank() && !answer.equals(run.answer())) {
            mapper.answer(run.id(), run.version(), answer, Instant.now().toString()); run = persistence.require(run.id());
        }
        if (!run.state().equals("RUNNING")) return;
        if (status.completed()) {
            if (mapper.pending(run.id()).isPresent()) { persistence.stop(run.documentId(), "INPUT"); return; }
            var result = openCode.sessionResult(remote);
            if (result.errorType() != null && !result.errorType().isBlank()) {
                persistence.proven(run, PptAgentState.FAILED, "REMOTE_TERMINAL", "模型本轮失败；已保存的页面与候选保留");
            } else {
                String output = AssistRedaction.text(result.text());
                if (output != null && !output.equals(run.answer())) { mapper.answer(run.id(), run.version(), output, Instant.now().toString()); run = persistence.require(run.id()); }
                persistence.proven(run, PptAgentState.COMPLETED, "REMOTE_TERMINAL", "本轮模型已完成；制作结果以作品检查和作业状态为准");
                completeProduction(run);
            }
            usage(remote, run);
        } else if (status.failed()) {
            var proof = openCode.abortWithConfirmation(remote);
            if (proof != null) persistence.proven(run, PptAgentState.FAILED, proof.name(), "模型本轮已停止，可重新发送请求");
        }
    }
    private void completeProduction(Run run) {
        if(json.readTree(run.contextJson()).has("generationAuthorization"))return;
        if (!Set.of("BRIEFING", "PRODUCING").contains(run.phase())) return;
        try {
            var work = workspace.workspace(run.documentId());
            workspace.invoke(run.documentId(), run.phase().equals("PRODUCING") ? "finish_production" : "finish_planning", json.valueToTree(Map.of("expectedRevision", work.revision())),
                    () -> authority.validateCompleted(run.id()));
        } catch (RuntimeException incomplete) {
            persistence.detail(persistence.require(run.id()), "模型已停止；制作完成检查未通过，请检查页面问题后继续修改或重试完成制作");
        }
    }
    private void stop(Run run) {
        if (run.externalSessionId() == null) {
            if (run.createDispatched() == 0) { persistence.proven(run, PptAgentState.STOPPED, "NOT_DISPATCHED", "请求未发送，已停止"); return; }
            recoverCreate(run); run = persistence.require(run.id());
            if (run.externalSessionId() == null) return; // An in-flight create cannot be disproved by one empty list.
        }
        var remote = restore(run);
        activity.capture(openCode, remote, run);
        var proof = openCode.abortWithConfirmation(remote);
        if (proof == null) return;
        boolean input = "INPUT".equals(run.stopReason()) && mapper.pending(run.id()).isPresent();
        persistence.proven(run, input ? PptAgentState.WAITING_INPUT : PptAgentState.STOPPED, proof.name(),
                input ? "请回答问题后继续" : "已停止，保存的页面与候选保留");
        usage(remote, run);
    }
    private OpenCodeSession restore(Run run) {
        var plan = plan(run);
        var remote = new OpenCodeSession(run.externalSessionId(), Path.of(run.rootPath()), plan.runtimeGenerationId(), plan.internalMcpServer());
        openCode.restoreDesignTurn(remote, SessionProfile.PPT_AGENT, model(run), run.messageId()); return remote;
    }
    private void usage(OpenCodeSession remote, Run run) {
        try {
            var records = openCode.sessionUsage(remote); if (records.isEmpty()) return;
            Long input = records.stream().noneMatch(v -> v.inputTokens() != null) ? null : records.stream().filter(v -> v.inputTokens() != null).mapToLong(UsageRecord::inputTokens).sum();
            Long output = records.stream().noneMatch(v -> v.outputTokens() != null) ? null : records.stream().filter(v -> v.outputTokens() != null).mapToLong(UsageRecord::outputTokens).sum();
            mapper.usage(run.id(), input, output);
        } catch (RuntimeException unavailable) { /* Missing usage stays unknown. */ }
    }
    private SessionCreationPlan plan(Run run) { return json.readValue(run.planJson(), SessionCreationPlan.class); }
    private OpenCodeModel model(Run run) { return json.readValue(run.modelJson(), OpenCodeModel.class); }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
