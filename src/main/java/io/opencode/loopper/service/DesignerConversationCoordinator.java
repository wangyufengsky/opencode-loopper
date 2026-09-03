package io.opencode.loopper.service;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Owns remote reuse and exact business-turn identity, independently of compilation and accounting. */
@Service
public final class DesignerConversationCoordinator {
    private final DesignerConversationMapper mapper;
    private final OpenCodeClient openCode;
    private final ObjectMapper json;
    private final java.util.concurrent.ConcurrentHashMap<String, OwnerGuard> ownerGuards = new java.util.concurrent.ConcurrentHashMap<>();

    /** Independent designers never wait on another designer's model/accounting call. */
    public Guard guard(String owner) {
        OwnerGuard state = ownerGuards.compute(owner, (key, current) -> {
            OwnerGuard result = current == null ? new OwnerGuard() : current;
            result.users++; return result;
        });
        state.lock.lock();
        return () -> {
            state.lock.unlock();
            ownerGuards.compute(owner, (key, current) -> --state.users == 0 ? null : state);
        };
    }
    public interface Guard extends AutoCloseable { @Override void close(); }
    private static final class OwnerGuard {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        int users;
    }
    public String previousRequirement(String id, int revision) {
        return mapper.previousRequirementSnapshot(id, revision).orElse("（首次讨论，暂无上一版）");
    }

    public DesignerConversationCoordinator(LoopperMapper mapper, OpenCodeClient openCode, ObjectMapper json) {
        this.mapper = mapper; this.openCode = openCode; this.json = json;
    }

    public void enable(String designerId) { mapper.enableDesignerConversations(designerId); }
    public boolean enabled(String designerId) { return mapper.designerConversationsEnabled(designerId); }
    public List<DesignerConversationRow> history(String designerId) { return mapper.designerConversations(designerId); }
    public List<View> views(String designerId) {
        return history(designerId).stream().map(row -> new View(row.id(), row.scopeKey(), row.generation(),
                row.externalSessionId(), row.state(), row.reason())).toList();
    }
    public record View(String id, String scopeKey, int generation, String externalSessionId, String state, String reason) { }

    public OpenCodeClient.OpenCodeSession requirement(DesignerSessionRow owner, Path root,
            OpenCodeClient.OpenCodeModel model, boolean candidate, boolean question, boolean replace) {
        if (enabled(owner.id())) return acquire(owner.id(), "REQUIREMENT", root, model, candidate, question, false, replace || !reusable(owner.externalSessionId(), owner.externalSessionState()) && owner.externalSessionId() != null);
        if (!replace && reusable(owner.externalSessionId(), owner.externalSessionState()))
            return new OpenCodeClient.OpenCodeSession(owner.externalSessionId(), root);
        return openCode.createSession(root, "OpenCode Loopper Requirement Designer (READ_ONLY)", model,
                question ? OpenCodeClient.SessionProfile.DESIGNER_INTERACTIVE_READ_ONLY : OpenCodeClient.SessionProfile.GENERAL_READ_ONLY);
    }

    public OpenCodeClient.OpenCodeSession workPackage(DesignerSessionRow owner, DesignWorkPackageRow workPackage,
            Path root, OpenCodeClient.OpenCodeModel model, boolean candidateCapable, boolean candidateTurn,
            boolean question, boolean direct, boolean replace) {
        if (enabled(owner.id())) return acquire(owner.id(), workPackage.id(), root, model, candidateCapable, question, direct,
                replace || workPackage.designerExternalSessionId() != null && !reusable(workPackage.designerExternalSessionId(), workPackage.designerExternalSessionState())
                        || "STOPPED_FOR_ATTACHMENT_REPLACEMENT".equals(workPackage.designerExternalSessionState()));
        if (!candidateTurn && !replace && reusable(workPackage.designerExternalSessionId(), workPackage.designerExternalSessionState()))
            return new OpenCodeClient.OpenCodeSession(workPackage.designerExternalSessionId(), root);
        var profile = candidateTurn
                ? question ? OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY : OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_READ_ONLY
                : question ? OpenCodeClient.SessionProfile.DESIGNER_INTERACTIVE_READ_ONLY : OpenCodeClient.SessionProfile.GENERAL_READ_ONLY;
        return openCode.createSession(root, "OpenCode Loopper Designer " + workPackage.packageId() + " (READ_ONLY)", model, profile);
    }

    private static boolean reusable(String id, String state) {
        return id != null && !id.isBlank() && !java.util.Set.of("FAILED", "ABORTED", "SUPERSEDED").contains(String.valueOf(state));
    }

    public OpenCodeClient.OpenCodeSession acquire(String designerId, String scope,
            Path root, OpenCodeClient.OpenCodeModel model, boolean candidate, boolean question,
            boolean adoptRequirement, boolean replace) {
        try (var guard = guard(designerId)) {
            DesignerConversationRow row = mapper.latestDesignerConversation(designerId, scope).orElse(null);
            if (row == null && adoptRequirement) {
                row = mapper.latestDesignerConversation(designerId, "REQUIREMENT")
                        .filter(item -> "OPEN".equals(item.state())).orElse(null);
                if (row != null && mapper.adoptDesignerConversation(row.id(), scope) != 1)
                    throw conflict("设计会话已被并发交接，请刷新后重试");
            }
            if (row != null && !"RETIRED".equals(row.state())) {
                if ("CREATING".equals(row.state()) && row.externalSessionId() == null) {
                    // Creation cannot dispatch a prompt before bind succeeds. A late creator loses the
                    // bind CAS and stops its empty remote, so recovering this claim cannot repeat work.
                    mapper.retireDesignerConversation(row.id(), "CREATION_INTERRUPTED_BEFORE_BUSINESS", Instant.now().toString());
                    return acquire(designerId, scope, root, model, candidate, question, adoptRequirement, false);
                }
                if (!"OPEN".equals(row.state())) throw conflict("上次设计会话创建结果未知，请重新打开设计");
                OpenCodeClient.OpenCodeSession remote = restore(row);
                if (replace || !root.toString().equals(row.rootPath())) {
                    openCode.abortWithConfirmation(remote);
                    retire(remote.id(), "CONTEXT_REPLACED");
                } else {
                    DesignerConversationTurnRow turn = mapper.latestDesignerTurn(row.id()).orElse(null);
                    if (turn != null && !"SETTLED".equals(turn.state())) {
                        if ("PREPARED".equals(turn.state()) || "SENDING".equals(turn.state()) || "UNKNOWN".equals(turn.state()))
                            throw conflict("上次设计请求尚未确认完成，请等待恢复或重新打开设计");
                        var status = openCode.sessionStatus(remote);
                        if (!status.completed()) throw conflict("当前设计回合仍在运行，不能重复发送");
                        settle(remote.id());
                    }
                    return remote;
                }
            }
            int generation = mapper.latestDesignerConversation(designerId, scope).map(item -> item.generation() + 1).orElse(1);
            String id = UUID.randomUUID().toString();
            var profile = candidate
                    ? question ? OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY : OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_READ_ONLY
                    : question ? OpenCodeClient.SessionProfile.DESIGNER_INTERACTIVE_READ_ONLY : OpenCodeClient.SessionProfile.GENERAL_READ_ONLY;
            String now = Instant.now().toString();
            row = new DesignerConversationRow(id, designerId, scope, generation, null, null, null, root.toString(),
                    profile.name(), json.writeValueAsString(model), "CREATING", null, now, now, 0);
            mapper.insertDesignerConversation(row); // Unique active scope claims creation before network I/O.
            String title = "REQUIREMENT".equals(scope) ? "OpenCode Loopper Requirement Designer (READ_ONLY)"
                    : "OpenCode Loopper package Designer " + mapper.designerConversationPackageName(scope).orElse(scope) + " (READ_ONLY)";
            OpenCodeClient.OpenCodeSession remote;
            try { remote = openCode.createSession(root, title, model, profile); }
            catch (RuntimeException failure) {
                mapper.retireDesignerConversation(id, "CREATE_FAILED_BEFORE_BUSINESS", Instant.now().toString());
                throw failure;
            }
            if (mapper.bindDesignerConversation(id, remote.id(), remote.generation(), remote.internalMcpServer()) != 1) {
                openCode.abortWithConfirmation(remote);
                throw conflict("设计会话绑定已变化，未发送业务请求");
            }
            return remote;
        }
    }

    public void begin(OpenCodeClient.OpenCodeSession remote, String phase) {
        DesignerConversationRow row = mapper.designerConversationForRemote(remote.id()).orElse(null);
        if (row == null) return;
        if (!"OPEN".equals(row.state())) throw conflict("当前设计轮次已交接，请创建新轮次");
        String id = UUID.randomUUID().toString();
        String marker = switch (phase) { case "REQUIREMENT" -> "r"; case "PACKAGE_QUESTION" -> "q"; default -> "p"; };
        String now = Instant.now().toString();
        var turn = new DesignerConversationTurnRow(id, row.id(), "msg_loopper_design_" + marker + "_" + id.replace("-", ""),
                phase, UUID.randomUUID().toString(), null, null, "PREPARED", now, now, 0);
        mapper.insertDesignerTurn(turn);
        openCode.restoreDesignTurn(remote, OpenCodeClient.SessionProfile.valueOf(row.profile()),
                json.readValue(row.modelJson(), OpenCodeClient.OpenCodeModel.class), turn.messageId());
    }

    public void send(OpenCodeClient.OpenCodeSession remote, OpenCodeClient.PromptRequest prompt) {
        DesignerConversationTurnRow turn = mapper.designerTurnForRemote(remote.id()).orElse(null);
        if (turn == null) { openCode.promptAsync(remote, prompt); return; }
        var request = new OpenCodeClient.PromptRequest(prompt.text(), prompt.system(), "build",
                prompt.responseFormat(), turn.messageId(), prompt.files());
        String sha = OpenCodeClient.promptRequestSha256(request);
        if (mapper.claimDesignerTurn(turn.id(), json.writeValueAsString(request), sha) != 1)
            throw conflict("当前设计请求已发送或结果未知，未重复发送");
        try {
            openCode.promptAsync(remote, request);
            mapper.finishDesignerTurn(turn.id(), "SENT", Instant.now().toString());
        } catch (RuntimeException failure) {
            mapper.finishDesignerTurn(turn.id(), "UNKNOWN", Instant.now().toString());
            throw failure;
        }
    }

    public OpenCodeClient.OpenCodeSession remote(String remoteId, Path fallback) {
        var row = mapper.designerConversationForRemote(remoteId).orElse(null);
        if (row == null) return new OpenCodeClient.OpenCodeSession(remoteId, fallback);
        var remote = restore(row);
        var turn = mapper.latestDesignerTurn(row.id()).orElse(null);
        if (turn != null && "PREPARED".equals(turn.state()))
            throw new SessionFailure("DESIGNER_TURN_NOT_SENT", "设计请求尚未发送，未读取旧回合；请重新打开设计");
        if (turn != null && ("SENDING".equals(turn.state()) || "UNKNOWN".equals(turn.state()))) {
            var request = readRequest(turn.requestJson());
            var lookup = openCode.findPromptMessage(remote, request, turn.requestSha256());
            if (!lookup.exists() || !turn.requestSha256().equals(lookup.verifiedRequestSha256()))
                throw new SessionFailure("DESIGNER_TURN_RESULT_UNKNOWN", "设计请求结果未知，未自动重发；可重新打开设计");
            mapper.finishDesignerTurn(turn.id(), "SENT", Instant.now().toString());
        }
        return remote;
    }

    /** Read projection must not recover (or fail) a request that its dispatcher is still sending. */
    public List<OpenCodeClient.PendingQuestion> questions(String remoteId, Path root) {
        var row = mapper.designerConversationForRemote(remoteId).orElse(null);
        if (row != null && mapper.latestDesignerTurn(row.id())
                .filter(turn -> !java.util.Set.of("SENT", "SETTLED").contains(turn.state())).isPresent()) return List.of();
        try {
            return openCode.pendingQuestions(row == null ? new OpenCodeClient.OpenCodeSession(remoteId, root) : restore(row));
        } catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), failure.getMessage());
        }
    }

    private OpenCodeClient.OpenCodeSession restore(DesignerConversationRow row) {
        var remote = new OpenCodeClient.OpenCodeSession(row.externalSessionId(), Path.of(row.rootPath()),
                row.runtimeGenerationId(), row.internalMcpServer());
        mapper.latestDesignerTurn(row.id()).ifPresent(turn -> openCode.restoreDesignTurn(remote,
                OpenCodeClient.SessionProfile.valueOf(row.profile()),
                json.readValue(row.modelJson(), OpenCodeClient.OpenCodeModel.class), turn.messageId()));
        return remote;
    }

    private OpenCodeClient.PromptRequest readRequest(String value) {
        var node = json.readTree(value);
        if (node.path("responseFormat").has("schema")) throw conflict("设计回合不能恢复为其他角色的结构化请求");
        return new OpenCodeClient.PromptRequest(node.path("text").asText(), node.path("system").asString(null),
                node.path("agent").asString(null), new OpenCodeClient.ResponseFormat.Text(), node.path("messageId").asText(),
                json.convertValue(node.path("files"), new tools.jackson.core.type.TypeReference<List<OpenCodeClient.FilePart>>() { }));
    }

    public boolean candidate(String remoteId, boolean fallback) {
        return mapper.designerConversationForRemote(remoteId)
                .map(row -> row.profile().startsWith("PACKAGE_DESIGN_CANDIDATE")).orElse(fallback);
    }
    public void settle(String remoteId) {
        mapper.designerTurnForRemote(remoteId).ifPresent(turn ->
                mapper.finishDesignerTurn(turn.id(), "SETTLED", Instant.now().toString()));
    }
    public void retire(String remoteId, String reason) {
        if (remoteId == null) return;
        mapper.designerConversationForRemote(remoteId).ifPresent(row -> {
            settle(remoteId);
            mapper.retireDesignerConversation(row.id(), reason, Instant.now().toString());
        });
    }
    private static ConflictException conflict(String detail) { return new ConflictException("DESIGNER_CONVERSATION_CONFLICT", detail); }
}
