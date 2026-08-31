package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.AcceptanceBindingSource;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Narrow decision seam for selectively moving an already-routed v7 tie onto the internal candidate loop. */
final class AcceptanceClosedChoiceCandidateCoordinator {
    static final String CONTRACT_VERSION = "ACCEPTANCE_CLOSED_CHOICE_V7";
    static final String WORKFLOW_STEP = CONTRACT_VERSION;
    static final int MAX_ATTEMPTS = 2;
    static final OpenCodeClient.SessionProfile SESSION_PROFILE =
            OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS;
    private static final int MAX_ENUMERATED_CHOICES = 32;

    private final MachineCandidateSubmission submissions;
    private final LoopperProperties properties;
    private final Optional<CandidateRuntimeBindingService> bindings;
    private final DesignerAcceptanceCandidatePromptFactory prompts;

    AcceptanceClosedChoiceCandidateCoordinator(MachineCandidateSubmission submissions,
                                               LoopperProperties properties) {
        this(submissions, properties, Optional.empty(), new ObjectMapper());
    }

    AcceptanceClosedChoiceCandidateCoordinator(MachineCandidateSubmission submissions,
                                                LoopperProperties properties,
                                                Optional<CandidateRuntimeBindingService> bindings,
                                                ObjectMapper json) {
        this.submissions = submissions;
        this.properties = properties;
        this.bindings = bindings;
        this.prompts = new DesignerAcceptanceCandidatePromptFactory(json);
    }

    Decision decide(DesignAcceptancePlanningRow planning, DesignerAcceptanceWorkflow.RoutingResult routing) {
        if (planning == null || routing == null || routing.resolution() == null
                || !DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(planning.contractVersion())) {
            return new Decision(Action.WAITING_INPUT, "ACCEPTANCE_CANDIDATE_NOT_ENUMERABLE");
        }
        DesignerAcceptanceFastPathResolver.Resolution resolution = routing.resolution();
        if (resolution.outcome() == DesignerAcceptanceFastPathResolver.Outcome.RESOLVED
                && !routing.compilerRequired()) {
            return new Decision(Action.SERVER_DIRECT, "ACCEPTANCE_UNIQUE_OPTIMUM_SERVER_DIRECT");
        }
        if (!properties.getInternalCandidate().isAcceptanceClosedChoiceV7Enabled()) {
            return new Decision(Action.LEGACY_JSON, "ACCEPTANCE_CANDIDATE_FEATURE_DISABLED");
        }
        if (!exactTrueTie(routing)) {
            return new Decision(Action.WAITING_INPUT, "ACCEPTANCE_CANDIDATE_NOT_ENUMERABLE");
        }
        if (!AcceptanceBindingSource.AI_DISAMBIGUATION_V6.name().equals(planning.bindingSource())
                || planning.bindingJson() == null || planning.bindingJson().isBlank()) {
            return new Decision(Action.WAITING_INPUT, "ACCEPTANCE_CANDIDATE_ROUTE_NOT_PERSISTED");
        }
        return new Decision(Action.OPEN_INTERNAL_MCP, "ACCEPTANCE_CANDIDATE_INTERNAL_MCP");
    }

    MachineCandidateSubmission.RunSnapshot open(OpenRequest request) {
        if (request == null || request.compilation() == null || request.planning() == null
                || request.runId() == null || request.runId().isBlank()
                || request.runtimeGenerationId() == null || request.runtimeGenerationId().isBlank()
                || request.externalSessionId() == null || request.externalSessionId().isBlank()) {
            throw new BadRequestException("ACCEPTANCE_CANDIDATE_OPEN_INVALID",
                    "验收闭集候选运行参数不完整");
        }
        Decision decision = decide(request.planning(), request.routing());
        if (decision.action() != Action.OPEN_INTERNAL_MCP) {
            throw new ConflictException("ACCEPTANCE_CANDIDATE_NOT_ELIGIBLE",
                    "当前验收规划不是可枚举的 v7 真实同分闭集");
        }
        LoopSpecCompilationRow compilation = request.compilation();
        DesignAcceptancePlanningRow planning = request.planning();
        if (!compilation.id().equals(planning.compilationId())
                || !compilation.designerSessionId().equals(planning.designerSessionId())
                || compilation.designRevision() != planning.designRevision()
                || !request.externalSessionId().equals(compilation.externalSessionId())) {
            throw new ConflictException("ACCEPTANCE_CANDIDATE_OWNER_MISMATCH",
                    "验收闭集候选运行与冻结编译拥有者不一致");
        }
        return submissions.open(new MachineCandidateSubmission.OpenCommand(
                request.runId(), compilation.designerSessionId(),
                MachineCandidateSubmission.CandidateOwner.loopSpecCompilation(compilation.id()),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7, WORKFLOW_STEP,
                planning.designRevision(), compilation.version(),
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                CONTRACT_VERSION, request.runtimeGenerationId(), request.externalSessionId(), MAX_ATTEMPTS));
    }

    MachineCandidateSubmission.RunSnapshot openInternal(
            LoopSpecCompilationRow compilation, DesignAcceptancePlanningRow planning,
            DesignerAcceptanceWorkflow.RoutingResult routing, OpenCodeClient.OpenCodeSession remote) {
        CandidateRuntimeBindingService.Binding binding = binding(remote,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        return open(new OpenRequest(runId(compilation.id(),
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP), compilation, planning, routing,
                binding.runtimeGenerationId(), remote.id()));
    }

    MachineCandidateSubmission.RunSnapshot openLegacy(
            LoopSpecCompilationRow compilation, DesignAcceptancePlanningRow planning,
            DesignerAcceptanceWorkflow.RoutingResult routing, OpenCodeClient.OpenCodeSession remote) {
        if (!exactTrueTie(routing)) {
            throw new ConflictException("ACCEPTANCE_CANDIDATE_NOT_ELIGIBLE",
                    "只有 v7 真实同分闭集可进入进程内兼容候选运行");
        }
        CandidateRuntimeBindingService.Binding binding = binding(remote,
                MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY);
        return submissions.open(new MachineCandidateSubmission.OpenCommand(
                runId(compilation.id(), MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY),
                compilation.designerSessionId(),
                MachineCandidateSubmission.CandidateOwner.loopSpecCompilation(compilation.id()),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7, WORKFLOW_STEP,
                planning.designRevision(), compilation.version(),
                MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY,
                CONTRACT_VERSION, binding.runtimeGenerationId(), remote.id(), MAX_ATTEMPTS));
    }

    Optional<MachineCandidateSubmission.RunSnapshot> find(String compilationId) {
        Optional<MachineCandidateSubmission.RunSnapshot> legacy = submissions.find(runId(
                compilationId, MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY));
        return legacy.isPresent() ? legacy : submissions.find(runId(
                compilationId, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP));
    }

    Optional<MachineCandidateSubmission.SubmissionResult> terminal(String compilationId) {
        return find(compilationId).flatMap(run -> submissions.terminal(run.runId()));
    }

    MachineCandidateSubmission.SubmissionResult submitLegacy(String compilationId, String output) {
        MachineCandidateSubmission.RunSnapshot run = submissions.find(runId(
                        compilationId, MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY))
                .orElseThrow(() -> new ConflictException("ACCEPTANCE_LEGACY_RUN_MISSING",
                        "验收闭集进程内兼容候选运行不存在"));
        return submissions.submit(new MachineCandidateSubmission.SubmitCommand(
                run.runId(), run.externalSessionId() + ":" + (run.attemptsUsed() + 1),
                prompts.candidateJson(output), run.version(),
                MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY));
    }

    MachineCandidateSubmission.RunSnapshot close(
            String compilationId, MachineCandidateSubmission.SubmissionChannel channel) {
        MachineCandidateSubmission.RunSnapshot run = submissions.find(runId(compilationId, channel))
                .orElseThrow(() -> new ConflictException("ACCEPTANCE_CANDIDATE_RUN_MISSING",
                        "验收闭集候选运行不存在"));
        return submissions.close(new MachineCandidateSubmission.CloseCommand(run.runId(), run.version()));
    }

    void validate(MachineCandidateSubmission.RunSnapshot run) {
        bindings.orElseThrow(() -> new ConflictException("CANDIDATE_RUNTIME_BINDING_UNAVAILABLE",
                        "候选运行时绑定服务不可用"))
                .validate(run, run.submissionChannel());
    }

    String runId(String compilationId, MachineCandidateSubmission.SubmissionChannel channel) {
        if (compilationId == null || compilationId.isBlank() || channel == null) {
            throw new IllegalArgumentException("Candidate compilation and channel are required");
        }
        return UUID.nameUUIDFromBytes(("acceptance-candidate:" + compilationId + ":" + channel.name())
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private CandidateRuntimeBindingService.Binding binding(
            OpenCodeClient.OpenCodeSession remote,
            MachineCandidateSubmission.SubmissionChannel channel) {
        return bindings.orElseThrow(() -> new ConflictException("CANDIDATE_RUNTIME_BINDING_UNAVAILABLE",
                        "候选运行时绑定服务不可用"))
                .bind(remote, channel);
    }

    static boolean exactTrueTie(DesignerAcceptanceWorkflow.RoutingResult routing) {
        if (routing == null || !routing.compilerRequired() || routing.resolution() == null) return false;
        DesignerAcceptanceFastPathResolver.Resolution resolution = routing.resolution();
        if (resolution.outcome() != DesignerAcceptanceFastPathResolver.Outcome.NEEDS_COMPILER
                || !resolution.designGaps().isEmpty()
                || !resolution.unresolvedFactIndexes().isEmpty()
                || resolution.groupHints().isEmpty()
                || resolution.ambiguousCapabilityFactIndexes().isEmpty()
                || resolution.trueCapabilityTieCount() < 2
                || resolution.trueCapabilityTieCount() > MAX_ENUMERATED_CHOICES
                || resolution.optimalTieChoiceSets().size() != resolution.trueCapabilityTieCount()) {
            return false;
        }
        Set<Integer> ambiguous = new LinkedHashSet<>(resolution.ambiguousCapabilityFactIndexes());
        if (!resolution.tiedCapabilityIndexesByFact().keySet().equals(ambiguous)
                || resolution.tiedCapabilityIndexesByFact().values().stream().anyMatch(value ->
                    value == null || value.size() < 2 || value.stream().anyMatch(index -> index == null || index < 0))) {
            return false;
        }
        Set<Integer> allowed = resolution.tiedCapabilityIndexesByFact().values().stream()
                .flatMap(List::stream).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Set<Integer>> distinct = new LinkedHashSet<>();
        for (List<Integer> choice : resolution.optimalTieChoiceSets()) {
            if (choice == null || choice.isEmpty() || choice.size() > MAX_ENUMERATED_CHOICES
                    || choice.stream().anyMatch(index -> index == null || !allowed.contains(index))) return false;
            Set<Integer> normalized = new LinkedHashSet<>(choice);
            if (normalized.size() != choice.size() || !distinct.add(Set.copyOf(normalized))) return false;
        }
        return true;
    }

    enum Action { SERVER_DIRECT, LEGACY_JSON, OPEN_INTERNAL_MCP, WAITING_INPUT }
    record Decision(Action action, String reasonCode) { }
    record OpenRequest(String runId, LoopSpecCompilationRow compilation, DesignAcceptancePlanningRow planning,
                       DesignerAcceptanceWorkflow.RoutingResult routing,
                       String runtimeGenerationId, String externalSessionId) { }
}
