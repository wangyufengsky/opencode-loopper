package io.opencode.loopper.service;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.StageKind;
import io.opencode.loopper.domain.WorkflowTemplate;
import io.opencode.loopper.persistence.DesignDiscussionRevisionRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Compiles simple safe local maintenance into one implicit WP-1 with hard no-delete evidence. */
@Service
public final class DirectMaintenanceDesignService {
    private static final Pattern PATH = Pattern.compile("`([A-Za-z0-9_./-]{1,512})`");
    private final LoopperMapper mapper;
    private final LoopDraftService drafts;

    public DirectMaintenanceDesignService(LoopperMapper mapper, LoopDraftService drafts) {
        this.mapper = mapper; this.drafts = drafts;
    }

    public LoopSpec compile(String sessionId, TaskProfileService.View profile) {
        if (profile.workflowTemplate() != WorkflowTemplate.LOCAL_MAINTENANCE) {
            throw new ConflictException("MAINTENANCE_PROFILE_INVALID", "当前画像不是简单本地维护");
        }
        DesignerSessionRow session = mapper.findDesignerSession(sessionId)
                .orElseThrow(() -> new NotFoundException("Designer session not found: " + sessionId));
        DesignDiscussionRevisionRow discussion = mapper.findLatestDesignDiscussionRevision(sessionId, "REQUIREMENT")
                .orElseThrow(() -> new ConflictException("REQUIREMENT_DISCUSSION_MISSING", "维护需求讨论快照不存在"));
        List<String> targets = targets(discussion.snapshotMarkdown());
        if (targets.isEmpty()) {
            throw new BadRequestException("MAINTENANCE_TARGET_REQUIRED",
                    "简单维护必须在确认稿中用反引号明确列出允许修改的本地相对路径");
        }
        LoopSpec.VerifierSpec diff = new LoopSpec.VerifierSpec("GIT_DIFF", List.of(), null, true,
                targets, List.of(".git/**", ".env", ".env.*", "data/**", "target/**", "node_modules/**"), true);
        String criterionId = "WP-1-AC-1";
        LoopSpec.AcceptanceCriterion criterion = new LoopSpec.AcceptanceCriterion(criterionId,
                "维护后的本地配置或文档满足确认稿中的可观察结果", "JUDGE",
                "根据冻结需求和只允许修改的目标文件判断维护语义是否满足", "配置语义无法由通用表达式安全执行");
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("在禁止删除和外部操作的边界内完成本地维护",
                targets, List.of(".git/**", ".env", ".env.*", "data/**"), targets,
                List.of(diff), List.of(criterion), null, ImplementationKind.NON_JAVA, "WP-1",
                StageKind.LOCAL_MAINTENANCE, ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, null);
        LoopDraftRow draft = drafts.get(session.loopDraftId()); LoopSpec old = drafts.spec(draft);
        LoopSpec compiled = new LoopSpec("v2", old.projectId(), old.goal(), discussion.snapshotMarkdown(),
                List.of(stage), old.limits(), old.model(), old.sessionPolicy(), old.nextAttemptPromptTemplate(), old.budget());
        drafts.updateAtVersion(draft.id(), compiled, draft.version());
        return compiled;
    }

    private static List<String> targets(String markdown) {
        List<String> result = new ArrayList<>(); Matcher matcher = PATH.matcher(markdown == null ? "" : markdown);
        while (matcher.find() && result.size() < 32) {
            String value = matcher.group(1);
            if (!value.startsWith("/") && !value.contains("..") && !value.equals(".git")
                    && !value.startsWith(".git/") && !value.equals(".env") && !value.startsWith(".env.")) result.add(value);
        }
        return result.stream().distinct().toList();
    }
}
