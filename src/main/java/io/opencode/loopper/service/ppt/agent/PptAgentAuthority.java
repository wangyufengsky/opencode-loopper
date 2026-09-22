package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.PptAgentMapper;
import io.opencode.loopper.persistence.PptAgentRows.Run;
import io.opencode.loopper.runtime.PptRuntimeSupport;
import io.opencode.loopper.service.ConflictException;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Rechecks the durable run, phase and exact session at both tool entry and commit. */
@Service
public class PptAgentAuthority {
    private final PptAgentMapper mapper;
    private final PptRuntimeSupport runtime;
    private final PptAgentWorkspace workspace;
    private final LoopperProperties properties;
    private final io.opencode.loopper.runtime.OwnedRuntimeGenerationStore generations;
    private final PptAgentWorkflowGate workflow;
    public PptAgentAuthority(PptAgentMapper mapper, PptRuntimeSupport runtime, PptAgentWorkspace workspace,
                             LoopperProperties properties, io.opencode.loopper.runtime.OwnedRuntimeGenerationStore generations,PptAgentWorkflowGate workflow) {
        this.mapper = mapper; this.runtime = runtime; this.workspace = workspace; this.properties = properties;
        this.generations = generations;this.workflow=workflow;
    }
    /** Process I/O belongs only in coordinator recovery, outside all persistence transactions. */
    public java.util.Optional<String> retiredRuntimeProof(Run run) {
        if (run.generation() == null || runtime.isCurrentGeneration(run.generation())) return java.util.Optional.empty();
        return generations.exitProof(run.generation(), true);
    }
    public Run validate(Run original, String tool) {
        Run current = mapper.run(original.id()).orElseThrow(() -> denied("PPT 助手请求不存在"));
        workflow.validateRun(current);
        runtime.validateGeneration(current);
        if (!java.util.Objects.equals(current.externalSessionId(), original.externalSessionId())
                || !java.util.Objects.equals(current.generation(), original.generation())
                || current.round() != original.round()
                || !Set.of("SENDING", "UNKNOWN", "RUNNING").contains(current.state())) throw denied("该请求已经停止、等待输入或被新轮次替代");
        var work = workspace.workspace(current.documentId());
        if (!phaseMatches(current.phase(), work.phase())) throw denied("作品阶段已变化，请结束当前请求后重新读取");
        if (!allowed(tool, current.phase())) throw denied("当前阶段不允许此 PPT 操作");
        return current;
    }
    public void validateCompleted(String id) {
        var run = mapper.run(id).orElseThrow(() -> denied("PPT 请求不存在"));
        if (!"fake".equals(properties.getOpenCode().getMode())) runtime.validateGeneration(run);
        if (!run.state().equals("COMPLETED") || run.stopProof() == null
                || !workspace.workspace(run.documentId()).phase().equals(run.phase())
                || mapper.active(run.documentId()).isPresent()) throw denied("制作请求尚未证明完成或已经有新请求");
    }
    public static boolean phaseMatches(String expected, String actual) {
        return expected.equals(actual) || Set.of("REVIEW", "EXPORTED").contains(expected)
                && Set.of("REVIEW", "EXPORTED").contains(actual);
    }
    public static boolean allowed(String tool, String phase) {
        return switch (tool) {
            case "ppt_submit_plan" -> Set.of("BRIEFING", "DIRECTION", "DESIGN").contains(phase);
            case "ppt_apply_operations" -> Set.of("DESIGN", "PRODUCING", "REVIEW", "EXPORTED").contains(phase);
            case "ppt_export" -> Set.of("REVIEW", "EXPORTED").contains(phase);
            default -> io.opencode.loopper.runtime.PptAgentProfile.TOOLS.contains(tool);
        };
    }
    private static ConflictException denied(String message) { return new ConflictException("PPT_AGENT_AUTHORITY_CHANGED", message); }
}
