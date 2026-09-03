package io.opencode.loopper.service;

import io.opencode.loopper.persistence.StoryBindingMapper;
import io.opencode.loopper.persistence.StoryAccountingCallRow;
import java.util.Set;

/** Shared server decision for retry buttons and the transactional retry claim. */
final class StoryAccountingRetryPolicy {
    private StoryAccountingRetryPolicy() { }

    static String unavailableReason(StoryBindingMapper mapper, StoryAccountingCallRow call) {
        if (!Set.of("FAILED", "UNKNOWN", "CANCELLED").contains(call.state())) return "本次统计尚未失败或取消";
        var owner = mapper.findStoryAccountingSessionById(call.accountingSessionId()).orElseThrow();
        if (!Set.of("REQUIREMENT_DESIGNER", "PACKAGE_DESIGNER", "PACKAGE_DESIGN_V1", "IMPLEMENTATION").contains(owner.role())) {
            return "此角色不再统计";
        }
        if (!mapper.findStoryAccountingCall(owner.id(), call.phase()).orElseThrow().id().equals(call.id())) {
            return "已有更新的统计调用，请查看最新结果";
        }
        if (Set.of("BINDING", "COMPLETING").contains(owner.state())) return "该会话已有统计正在执行";
        if (mapper.storyAccountingOwnerActive(owner.externalSessionId())) return "该会话仍用于业务或提问，请在会话交接后重试";
        if ("BEGIN".equals(call.phase()) && mapper.findStoryAccountingCall(owner.id(), "COMPLETE")
                .filter(row -> "SUCCEEDED".equals(row.state())).isPresent()) return "该会话已完成统计，无需重新开启";
        return null;
    }
}
