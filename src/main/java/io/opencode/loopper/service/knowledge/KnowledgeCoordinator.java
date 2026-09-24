package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.KnowledgeMapper;
import io.opencode.loopper.persistence.KnowledgeRows.*;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.assist.AssistRedaction;
import io.opencode.loopper.service.roles.RolePromptResources;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Durable single-turn dispatch and exact recovery. No remote call occurs inside a transaction. */
@Service
public class KnowledgeCoordinator {
    @org.springframework.beans.factory.annotation.Autowired(required = false) private io.opencode.loopper.service.RoleSessions roleSessions;
    private static final String EMPTY_ANSWER_CHECK = "模型已结束，正在核对回答内容";
    private final KnowledgeMapper mapper;
    private final KnowledgePersistence persistence;
    private final OpenCodeClient openCode;
    private final ObjectMapper json;
    private final LoopperProperties properties;
    private final KnowledgeEventHub events;
    private final KnowledgeQuestions questions;
    private final io.opencode.loopper.persistence.KnowledgeV2Mapper options;
    private final KnowledgeResearch research;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
            Thread.ofPlatform().daemon().name("knowledge-chat-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    public KnowledgeCoordinator(KnowledgeMapper mapper, KnowledgePersistence persistence, OpenCodeClient openCode,
            ObjectMapper json, LoopperProperties properties, KnowledgeEventHub events, KnowledgeQuestions questions, io.opencode.loopper.persistence.KnowledgeV2Mapper options, KnowledgeResearch research) {
        this.research = research;
        this.questions = questions; this.options = options; this.mapper = mapper; this.persistence = persistence; this.openCode = openCode; this.json = json; this.properties = properties; this.events = events;
    }
    public Turn send(String conversation, String key, String text) {
        if (!Set.of("managed", "fake").contains(properties.getOpenCode().getMode()))
            throw new BadRequestException("KNOWLEDGE_MANAGED_REQUIRED", "知识问答需要受管 OpenCode，请在设置中切换运行模式");
        if (key == null || !key.matches("[a-zA-Z0-9_-]{16,100}") || text == null || text.isBlank() || text.length() > 24000)
            throw new BadRequestException("KNOWLEDGE_MESSAGE_INVALID", "请输入不超过 24000 字符的问题，并使用有效的请求标识");
        Turn turn = persistence.begin(conversation, key, text); events.publish(conversation, "message"); enqueue(conversation); return turn;
    }
    public void stop(String id) { persistence.stop(id); events.publish(id, "state"); enqueue(id); }
    @Scheduled(fixedDelayString = "${loopper.knowledge-monitor-delay:1000}")
    public void monitor() { mapper.activeTurns().forEach(turn -> enqueue(turn.conversationId())); }
    private void enqueue(String id) {
        if (!running.add(id)) return;
        try { workers.execute(() -> { try { tick(id); } finally { running.remove(id); } }); }
        catch (RejectedExecutionException full) { running.remove(id); }
    }
    public void tick(String id) {
        var active = mapper.active(id); if (active.isEmpty()) return;
        try {
            var turn = active.get(); var conversation = persistence.require(id);
            if (turn.state().equals("STOPPING")) { stopRemote(conversation, turn); return; }
            if (conversation.remoteId() == null) {
                if (turn.state().equals("PREPARED")) createRemote(conversation, turn);
                else recoverCreation(conversation, turn);
                return;
            }
            var remote = restore(conversation, turn);
            switch (turn.state()) {
                case "PREPARED", "CREATING" -> dispatch(remote, turn);
                case "SENDING", "UNKNOWN" -> recoverPrompt(remote, turn);
                case "RUNNING" -> poll(remote, turn);
                default -> { }
            }
        } catch (RuntimeException failure) {
            mapper.active(id).ifPresent(turn -> {
                String detail = "连接暂不可用，正在核对原请求；不会重复发送。可停止当前生成后重试。";
                if (failure instanceof io.opencode.loopper.domain.SessionFailure safe && safe.code().contains("GENERATION"))
                    detail = "会话所属运行环境已变化，无法确认旧请求状态；请保留历史并检查运行环境。";
                if (!detail.equals(turn.detail())) mapper.detail(turn.id(), turn.version(), detail, Instant.now().toString());
            });
        } finally { if (!mapper.turn(active.get().id()).equals(active)) events.publish(id, "state"); }
    }
    private void createRemote(Conversation conversation, Turn turn) {
        SessionCreationPlan plan;
        try {
            if (conversation.planJson() != null) { recoverCreation(conversation, turn); return; }
            byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
            plan = openCode.prepareSessionCreation(Path.of(conversation.rootPath()), "Loopper 知识问答", model(conversation),
                    newProfile(conversation), Base64.getUrlEncoder().withoutPadding().encodeToString(random));
            plan = RoleSessions.prepare(roleSessions, plan, "KNOWLEDGE_CONVERSATION", conversation.id(), null);
            if (!plan.managed() && !"fake".equals(properties.getOpenCode().getMode())) throw new IllegalStateException("managed runtime required");
            if (mapper.plan(conversation.id(), json.writeValueAsString(plan)) != 1) return;
        } catch (RuntimeException beforeDispatch) {
            persistence.finish(turn, "FAILED", "问答运行环境尚未就绪，未发送问题；请检查运行环境后重试"); return;
        }
        persistence.state(turn, "CREATING", "正在建立问答会话");
        try {
            var attestation = openCode.createSession(plan);
            mapper.bind(conversation.id(), attestation.remoteId());
            // A concurrent Stop is preserved and handled by the next tick before any prompt.
        } catch (RuntimeException unknown) {
            mapper.active(conversation.id()).filter(t -> t.state().equals("CREATING"))
                    .ifPresent(t -> persistence.state(t, "CREATE_UNKNOWN", "会话创建结果待核对，未重复创建"));
        }
    }
    private void recoverCreation(Conversation conversation, Turn turn) {
        if (conversation.planJson() == null) return;
        var lookup = openCode.findSessionsByExactTitle(plan(conversation));
        if (lookup.supported() && lookup.matches().size() == 1) {
            mapper.bind(conversation.id(), lookup.matches().getFirst().remoteId());
            var fresh = mapper.turn(turn.id()).orElseThrow();
            if (!fresh.state().equals("STOPPING")) persistence.state(fresh, "CREATING", "已找回原问答会话");
        }
    }
    private void dispatch(OpenCodeSession remote, Turn turn) {
        var zone = java.time.ZoneId.of(options.options(turn.conversationId()).timezone());
        var date = java.time.ZonedDateTime.now(zone);
        String clock = "\n本会话时区：" + zone + "；当前时间：" + date + "；昨天的默认区间：["
                + date.toLocalDate().minusDays(1).atStartOfDay(zone).toOffsetDateTime() + ", "
                + date.toLocalDate().atStartOfDay(zone).toOffsetDateTime() + ")。用户明确日期、时区时以用户要求为准。";
        boolean autonomous = io.opencode.loopper.runtime.KnowledgeSessionPolicy.research(plan(persistence.require(turn.conversationId())).profile());
        var prompt = RoleSessions.renderSession(roleSessions, remote.id(), () -> new PromptRequest(turn.userText(), (autonomous ? KnowledgePrompts.research() : "") + RolePromptResources.read("knowledge.interactive") + clock, "build", new ResponseFormat.Text(), turn.messageId(), List.of()));
        persistence.dispatch(turn, json.writeValueAsString(prompt), OpenCodeClient.promptRequestSha256(prompt));
        try {
            openCode.promptAsync(remote, prompt);
            mapper.turn(turn.id()).filter(t -> t.state().equals("SENDING")).ifPresent(t -> persistence.state(t, "RUNNING", ""));
        } catch (RuntimeException unknown) {
            mapper.turn(turn.id()).filter(t -> t.state().equals("SENDING")).ifPresent(t -> {
                if (unknown instanceof io.opencode.loopper.domain.SessionFailure failure
                        && Set.of("ASSIST_SCOPE_UNAVAILABLE", "ASSIST_MCP_UNAVAILABLE").contains(failure.code()))
                    persistence.finish(t, "FAILED", failure.getMessage());
                else persistence.state(t, "UNKNOWN", "发送结果待核对，未重复发送");
            });
        }
    }
    private void recoverPrompt(OpenCodeSession remote, Turn turn) {
        var node = json.readTree(turn.requestJson());
        var prompt = new PromptRequest(node.path("text").asText(), node.path("system").asText(), "build", new ResponseFormat.Text(), turn.messageId(), List.of());
        var lookup = openCode.findPromptMessage(remote, prompt, turn.requestSha());
        if (lookup.supported() && lookup.exists() && turn.requestSha().equals(lookup.verifiedRequestSha256())) persistence.state(turn, "RUNNING", "");
    }
    private void poll(OpenCodeSession remote, Turn original) {
        var plan = plan(persistence.require(original.conversationId()));
        boolean autonomous = io.opencode.loopper.runtime.KnowledgeSessionPolicy.research(plan.profile());
        if (autonomous && research.advance(remote, original)) { usage(remote, original); return; }
        boolean interactive = io.opencode.loopper.runtime.KnowledgeSessionPolicy.interactive(plan.profile());
        var observation = openCode.observeKnowledgeSession(remote, interactive);
        if (interactive && questions.poll(remote, original, observation.questions())) { usage(observation.usage(), original); return; }
        var status = observation.status();
        String output = AssistRedaction.text(observation.output());
        if (autonomous && output.isBlank()) output = original.answer(); // Preserve drafts during legacy continuation recovery.
        if (output.length() > 500000) { persistence.stop(original.conversationId()); return; }
        String thinking = original.thinking();
        SessionTranscript observed = observation.transcript();
        if (observed != null) thinking = autonomous ? research.thinking(original, observed) : KnowledgeThinking.text(observed);
        if (autonomous && observed != null && research.nativeCalls(original, observed)) events.publish(original.conversationId(), "tools");
        if (!output.equals(original.answer()) || !thinking.equals(original.thinking()))
            mapper.output(original.id(), original.version(), output, thinking, Instant.now().toString());
        Turn turn = mapper.turn(original.id()).orElseThrow(); if (!turn.state().equals("RUNNING")) return;
        if (status.completed()) {
            var result = observation.result(); // Exact message filtering rejects stale previous answers.
            if (result == null) return;
            if (result.errorType() != null && !result.errorType().isBlank()) persistence.finish(turn, "FAILED", "模型未完成本次回答，请检查模型状态后重试");
            else if (!result.text().isBlank()) {
                String answer = AssistRedaction.text(result.text());
                if (!answer.equals(turn.answer())) { mapper.answer(turn.id(), turn.version(), answer, Instant.now().toString()); turn = mapper.turn(turn.id()).orElseThrow(); }
                if (!autonomous || research.completeExistingRound(turn)) persistence.finish(turn, "COMPLETED", "");
            } else if (EMPTY_ANSWER_CHECK.equals(turn.detail()))
                persistence.finish(turn, "FAILED", "模型已结束但没有返回有效回答，已保留调查记录；可以重新提问");
            else persistence.detail(turn, EMPTY_ANSWER_CHECK);
            usage(observation.usage(), turn);
        } else if (status.failed()) {
            confirmedStop(remote);
            persistence.finish(turn, "FAILED", "模型已停止，本次回答未完成；可以继续提问"); usage(observation.usage(), turn);
        } else if (EMPTY_ANSWER_CHECK.equals(turn.detail())) persistence.detail(turn, "");
    }
    private void stopRemote(Conversation conversation, Turn turn) {
        if (conversation.remoteId() == null) {
            if (conversation.planJson() == null) { persistence.finish(turn, "STOPPED", "已停止，问题未发送"); return; }
            var lookup = openCode.findSessionsByExactTitle(plan(conversation));
            if (!lookup.supported() || lookup.matches().size() > 1) return;
            if (lookup.matches().isEmpty()) { mapper.clearUnsentPlan(conversation.id(), conversation.planJson()); persistence.finish(turn, "STOPPED", "已停止，问题未发送"); return; }
            mapper.bind(conversation.id(), lookup.matches().getFirst().remoteId()); conversation = persistence.require(conversation.id());
        }
        var remote = restore(conversation, turn); confirmedStop(remote);
        persistence.finish(turn, "STOPPED", "已停止生成，以上为未完成回答"); usage(remote, turn);
    }
    private void confirmedStop(OpenCodeSession remote) {
        if (openCode.abortWithConfirmation(remote) == null) throw new IllegalStateException("stop not confirmed");
    }
    private OpenCodeSession restore(Conversation conversation, Turn turn) {
        var plan = plan(conversation);
        var remote = new OpenCodeSession(conversation.remoteId(), Path.of(conversation.rootPath()), plan.runtimeGenerationId(), plan.internalMcpServer());
        String message = io.opencode.loopper.runtime.KnowledgeSessionPolicy.research(plan.profile()) ? research.messageId(turn) : turn.messageId();
        openCode.restoreDesignTurn(remote, plan.profile(), model(conversation), message); return remote;
    }
    private void usage(OpenCodeSession remote, Turn turn) {
        try {
            usage(openCode.sessionUsage(remote), turn);
        } catch (RuntimeException unavailable) { /* Unavailable usage remains null. */ }
    }
    private void usage(List<UsageRecord> all, Turn turn) {
            if (all.isEmpty()) return;
            Long input = all.stream().noneMatch(r -> r.inputTokens() != null) ? null : all.stream().filter(r -> r.inputTokens() != null).mapToLong(UsageRecord::inputTokens).sum();
            Long output = all.stream().noneMatch(r -> r.outputTokens() != null) ? null : all.stream().filter(r -> r.outputTokens() != null).mapToLong(UsageRecord::outputTokens).sum();
            mapper.usage(turn.id(), input, output); // Remote cumulative totals; UI uses latest, never sums repeated history.
    }
    private SessionProfile newProfile(Conversation conversation) {
        var metadata = options.options(conversation.id());
        if (metadata != null && metadata.contractVersion() >= 2) {
            try { var capability = openCode.toolCapabilities(Path.of(conversation.rootPath()));
                if (capability.state() == CapabilityState.AVAILABLE && capability.contains("question"))
                    return metadata.contractVersion() >= 3 ? SessionProfile.KNOWLEDGE_RESEARCH_INTERACTIVE_READ_ONLY : SessionProfile.KNOWLEDGE_INTERACTIVE_READ_ONLY;
            } catch (RuntimeException unavailable) { /* Plain-text clarification remains available. */ }
        }
        return metadata != null && metadata.contractVersion() >= 3 ? SessionProfile.KNOWLEDGE_RESEARCH_READ_ONLY : SessionProfile.KNOWLEDGE_READ_ONLY;
    }
    private SessionCreationPlan plan(Conversation c) { return json.readValue(c.planJson(), SessionCreationPlan.class); }
    private OpenCodeModel model(Conversation c) { return json.readValue(c.modelJson(), OpenCodeModel.class); }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
