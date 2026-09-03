package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StoryAccountingActivityMapper;
import io.opencode.loopper.persistence.StoryAccountingCallRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient.SessionPart;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Read-only model output projection. No activity or receipt can change a business result. */
@Service
public class StoryAccountingActivityService {
    private final LoopperMapper mapper;
    private final StoryAccountingActivityMapper activity;
    private final ObjectProvider<OpenCodeClient> clients;
    private final ObjectMapper json;

    public StoryAccountingActivityService(LoopperMapper mapper, StoryAccountingActivityMapper activity,
                                          ObjectProvider<OpenCodeClient> clients, ObjectMapper json) {
        this.mapper = mapper;
        this.activity = activity;
        this.clients = clients;
        this.json = json;
    }

    public List<CallView> list() { return activity.visibleCalls().stream().map(this::view).toList(); }
    public CallView snapshot(String id) { return view(requireCall(id)); }

    public CallView get(String id) {
        var call = requireCall(id);
        String refreshError = null;
        if (active(call)) {
            try { capture(id); }
            catch (RuntimeException failure) { refreshError = "统计输出暂时无法刷新，仍在等待统计结果"; }
        }
        CallView current = view(requireCall(id));
        return new CallView(current.id(), current.operation(), current.state(), current.systemCode(), current.storyCode(),
                current.role(), current.designerSessionId(), current.taskId(), current.startedAt(), current.finishedAt(),
                current.detail(), current.parts(), refreshError, current.retryAvailable(), current.retryUnavailableReason());
    }

    /** Also captured before releasing the business barrier, so fast rounds retain their output. */
    public void capture(String id) {
        var call = requireCall(id);
        if (!active(call)) return;
        var owner = mapper.findStoryAccountingSessionById(call.accountingSessionId()).orElseThrow();
        var remote = new OpenCodeClient.OpenCodeSession(owner.externalSessionId(), Path.of(owner.worktreePath()),
                owner.runtimeGenerationId(), null);
        List<SessionPart> parts = clients.getObject().commandTranscript(remote, call.messageId()).parts();
        if (parts.isEmpty()) return;
        // Bound persisted output without flattening text or exposing transport envelopes.
        int[] remaining = { 32_768 };
        var safe = parts.stream().skip(Math.max(0, parts.size() - 32)).map(part -> {
            String text = part.content() == null ? "" : part.content();
            int size = Math.min(Math.min(text.length(), 8_192), remaining[0]);
            remaining[0] -= size;
            return new SessionPart(part.id(), part.type(), part.label(), text.substring(0, size), part.status(), part.startedAt());
        }).toList();
        activity.saveParts(id, json.writeValueAsString(safe));
    }

    public void dismiss(String id) { requireCall(id); activity.dismiss(id, Instant.now().toString()); }

    private StoryAccountingCallRow requireCall(String id) {
        return mapper.findStoryAccountingCallById(id).orElseThrow(() -> new NotFoundException("统计调用不存在"));
    }

    private CallView view(StoryAccountingCallRow call) {
        var owner = mapper.findStoryAccountingSessionById(call.accountingSessionId()).orElseThrow();
        var binding = owner.designerSessionId() != null ? mapper.findDesignerStoryBinding(owner.designerSessionId())
                : mapper.findTaskStoryBinding(owner.taskId());
        List<SessionPart> parts = activity.parts(call.id()).map(value -> List.of(json.readValue(value, SessionPart[].class)))
                .orElse(List.of());
        String retryReason = StoryAccountingRetryPolicy.unavailableReason(mapper, call);
        return new CallView(call.id(), call.operation(), call.state(), binding.map(row -> row.systemCode()).orElse(""),
                binding.map(row -> row.storyCode()).orElse(""), owner.role(), owner.designerSessionId(), owner.taskId(),
                call.startedAt(), call.finishedAt(), call.errorDetail(), parts, null, retryReason == null, retryReason);
    }

    private boolean active(StoryAccountingCallRow call) {
        return "PREPARED".equals(call.state()) || "CANCELLING".equals(call.state());
    }

    public record CallView(String id, String operation, String state, String systemCode, String storyCode,
                           String role, String designerSessionId, String taskId, String startedAt, String finishedAt,
                           String detail, List<SessionPart> parts, String refreshError,
                           boolean retryAvailable, String retryUnavailableReason) { }
}
