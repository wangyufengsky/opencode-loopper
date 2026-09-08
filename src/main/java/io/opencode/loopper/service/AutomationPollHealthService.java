package io.opencode.loopper.service;

import io.opencode.loopper.persistence.AutomationPollHealthMapper;
import io.opencode.loopper.persistence.AutomationPollHealthRow;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Detection health is independent of rule lifecycle and task/run success. Never stores raw exception output. */
@Service
public class AutomationPollHealthService {
    private final AutomationPollHealthMapper mapper;
    public AutomationPollHealthService(AutomationPollHealthMapper mapper) { this.mapper = mapper; }

    public Map<String, AutomationPollHealthRow> current() {
        return mapper.currentHealth().stream().collect(Collectors.toMap(AutomationPollHealthRow::ruleId, row -> row));
    }
    public AutomationPollHealthRow current(String id) { return mapper.currentRuleHealth(id).orElse(null); }
    public void success(String id, long version) { mapper.record(id, version, "CHECKED", Instant.now().toString(), null, null); }
    public void failure(String id, long version, Failure reason) {
        mapper.record(id, version, "FAILED", Instant.now().toString(), reason.name(), reason.message);
    }

    public enum Failure {
        GIT_HEAD_TIMEOUT("Git 检查超时，请检查项目目录后刷新。"),
        GIT_HEAD_UNAVAILABLE("无法读取 Git 提交，请检查项目目录和仓库状态。"),
        CRON_DETECTION_FAILED("定时规则检查失败，请检查表达式、时区及本地服务状态。"),
        RECONCILIATION_FAILED("运行状态核对失败，稍后将自动重试。"),
        DETECTION_FAILED("自动化检测失败，请检查项目和本地服务状态。" );
        private final String message;
        Failure(String message) { this.message = message; }
    }
    public static final class DetectionFailure extends RuntimeException {
        private final Failure reason;
        public DetectionFailure(Failure reason) { super(reason.message); this.reason = reason; }
        public Failure reason() { return reason; }
    }
}
