package io.opencode.loopper.api;

import io.opencode.loopper.domain.AutomationApprovalMode;
import io.opencode.loopper.domain.AutomationTriggerType;
import io.opencode.loopper.domain.InteractionAction;
import io.opencode.loopper.domain.InteractionKind;
import io.opencode.loopper.domain.InteractionState;
import io.opencode.loopper.domain.RecoveryMode;
import io.opencode.loopper.domain.LoopSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Public v1 wire contracts shared by the REST, SSE and Vue adapters. */
public final class FeatureContracts {
    private FeatureContracts() { }

    public record InteractionDto(String id, InteractionKind kind, InteractionState state,
                                 String taskId, String designerSessionId, String sessionId,
                                 String externalRequestId, JsonNode payload, long version,
                                 InteractionAction resolvedAction, String createdAt,
                                 String updatedAt, String resolvedAt) { }

    public record ResolveInteractionRequest(@NotNull InteractionAction action, List<List<String>> answers,
                                            String message, @NotNull Long version) {
        public ResolveInteractionRequest {
            answers = answers == null ? List.of() : List.copyOf(answers);
        }
    }

    public record QueueStatusDto(String taskId, String state, Long queuePosition,
                                 String leaseState, String rootFingerprint) { }

    public record RecoveryRequest(RecoveryMode mode) {
        public RecoveryRequest { mode = mode == null ? RecoveryMode.FROM_FAILED_STAGE : mode; }
    }

    public record RecoveryDto(String taskId, String parentTaskId, RecoveryMode mode,
                              String parentStageId, String workspaceFingerprint,
                              boolean writableSession) { }

    public record CheckpointRequest(String externalMessageId) { }
    public record CheckpointDto(String id, String taskId, String sessionId, String attemptId,
                                String externalMessageId, String contentSha256, String createdAt) { }

    /** Nullable totals/cost preserve the provider's unknown state; they are never serialized as zero. */
    public record UsageSummaryDto(String taskId, Long inputTokens, Long outputTokens, Long totalTokens,
                                  String costAmount, String currency, boolean reliable,
                                  long unknownUsageCount, long durationMs, long retryCount) { }

    public record AutomationRuleDto(String id, String name, String projectId, String templateVersionId,
                                    AutomationTriggerType triggerType, Map<String, Object> triggerConfig,
                                    String state, AutomationApprovalMode approvalMode,
                                    String updatedAt, long version) {
        public AutomationRuleDto {
            triggerConfig = triggerConfig == null ? Map.of() : Map.copyOf(triggerConfig);
        }
    }

    public record TemplateVersionDto(String id, String templateId, int versionNumber,
                                     LoopSpec spec, String specSha256, boolean immutable,
                                     boolean autoStartApproved, String createdAt) { }

    public record TemplateDto(String id, String name, String description, String state,
                              List<TemplateVersionDto> versions, String createdAt,
                              String updatedAt, long version) {
        public TemplateDto { versions = versions == null ? List.of() : List.copyOf(versions); }
    }

    public record CreateTemplateRequest(@NotBlank String name, String description) { }
    public record UpdateTemplateRequest(@NotBlank String name, String description,
                                        @NotBlank String state, @NotNull Long version) { }
    public record CreateTemplateVersionRequest(@NotNull @Valid LoopSpec spec,
                                               boolean autoStartApproved) { }
    public record WorkspaceTemplateVersionDto(String id, String templateId, int versionNumber,
                                              LoopSpec spec, String specSha256,
                                              boolean autoStartApproved, String createdAt) { }
    public record WorkspaceTemplateDto(String id, String name, String description, String state,
                                       String createdAt, String updatedAt, long version,
                                       List<WorkspaceTemplateVersionDto> versions) {
        public WorkspaceTemplateDto { versions = versions == null ? List.of() : List.copyOf(versions); }
    }
    public record WorkspaceRuleDto(String id, String name, String projectId, String templateVersionId,
                                   AutomationTriggerType triggerType, Map<String, Object> triggerConfig) {
        public WorkspaceRuleDto { triggerConfig = triggerConfig == null ? Map.of() : Map.copyOf(triggerConfig); }
    }
    public record WorkspaceExportDto(int formatVersion, List<WorkspaceTemplateDto> templates,
                                     List<WorkspaceRuleDto> rules) {
        public WorkspaceExportDto {
            templates = templates == null ? List.of() : List.copyOf(templates);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }
    public record WorkspacePreviewDto(String previewId, int templateCount, int ruleCount,
                                      WorkspaceExportDto exported, String expiresAt) { }

    public record CreateAutomationRuleRequest(@NotBlank String name, @NotBlank String projectId,
                                              @NotBlank String templateVersionId,
                                              @NotNull AutomationTriggerType triggerType,
                                              Map<String, Object> triggerConfig) {
        public CreateAutomationRuleRequest {
            triggerConfig = triggerConfig == null ? Map.of() : Map.copyOf(triggerConfig);
        }
    }

    public record UpdateAutomationRuleRequest(@NotBlank String name, @NotBlank String templateVersionId,
                                              @NotNull AutomationTriggerType triggerType,
                                              Map<String, Object> triggerConfig,
                                              @NotBlank String state,
                                              @NotNull AutomationApprovalMode approvalMode,
                                              @NotNull Long version) {
        public UpdateAutomationRuleRequest {
            triggerConfig = triggerConfig == null ? Map.of() : Map.copyOf(triggerConfig);
        }
    }

    /** The raw webhook token is populated only on the create response and is never persisted in clear text. */
    public record AutomationRuleMutationDto(AutomationRuleDto rule, String webhookToken,
                                            String webhookPath) { }

    public record AutomationRunDto(String id, String ruleId, AutomationTriggerType triggerType,
                                   String state, String draftId, String taskId, JsonNode evidence,
                                   String detectedAt, String startedAt, String endedAt) { }
    public record AutomationRunFeedDto(List<AutomationRunDto> runs, String serverTime) {
        public AutomationRunFeedDto { runs = runs == null ? List.of() : List.copyOf(runs); }
    }
    public record WorkspaceImportResultDto(List<TemplateDto> templates,
                                           List<AutomationRuleMutationDto> rules) {
        public WorkspaceImportResultDto {
            templates = templates == null ? List.of() : List.copyOf(templates);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    public record ConfirmAutomationRunRequest(String title) { }

    public record TaskSseEvent(long sequence, String type, String at, JsonNode data) { }
}
