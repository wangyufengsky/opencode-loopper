package io.opencode.loopper.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskProfileSafetyPolicyTest {
    private final TaskProfileSafetyPolicy policy = new TaskProfileSafetyPolicy();

    @Test
    void acceptsBusinessSignalPublicationRegardlessOfNaturalWordOrder() {
        assertThat(policy.requestsUnsafeOperation("新增事件发布能力并划分多个发布边界")).isFalse();
        assertThat(policy.requestsUnsafeOperation(
                "失败时发布包含调用身份、失败节点、错误分类和原始 cause 的领域事件")).isFalse();
        assertThat(policy.requestsUnsafeOperation("publish a domain event containing the failure cause")).isFalse();
        assertThat(policy.requestsUnsafeOperation("发布携带版本号的状态变更消息")).isFalse();
        assertThat(policy.requestsUnsafeOperation(
                "每个节点记录执行轨迹（含成功标记）并发布/可观测，链路成功后发布成功事件")).isFalse();
        assertThat(policy.requestsUnsafeOperation(
                "新增领域事件类 + 事件发布器：结构化领域事件，进程内同步发布")).isFalse();
        assertThat(policy.requestsUnsafeOperation(
                "事件发布为进程内同步调用；发布器自身异常不得掩盖原始失败（原始失败优先，发布异常可 append suppressed）")).isFalse();
        assertThat(policy.requestsUnsafeOperation(
                "新增轻量进程内发布-订阅器，支持按事件类型注册监听器并发布事件；发布-订阅器生命周期与链执行解耦")).isFalse();
        assertThat(policy.requestsUnsafeOperation(
                "执行器创建并绑定 ChainExecutionRecord，发布 CHAIN_STARTED；全部节点成功后发布 CHAIN_SUCCEEDED")).isFalse();
        assertThat(policy.requestsUnsafeOperation(
                "失败事件仅发布一次（防止 finally 与主路径重复发布）")).isFalse();
        assertThat(policy.requestsUnsafeOperation(
                "支持监听器注册、发布与按类型分发；Spring 仅装配，核心可脱离 Spring 直接 new 使用")).isFalse();
        assertThat(policy.requestsUnsafeOperation(
                "重复或非法事件不得改变状态：如 succeeded 重复发布、failed 之后再 succeeded、补偿事件在无失败时发布等")).isFalse();
        assertThat(policy.requestsUnsafeOperation("验收须区分构建与测试结果，不伪造外部系统结果")).isFalse();
    }

    @Test
    void rejectsExternalPublicationTargetsEvenWhenBusinessEventsAreAlsoRequested() {
        assertThat(policy.requestsUnsafeOperation("完成后发布")).isTrue();
        assertThat(policy.requestsUnsafeOperation("提交代码并发布新版本")).isTrue();
        assertThat(policy.requestsUnsafeOperation("发布领域事件并发布构建产物到 GitHub Release")).isTrue();
        assertThat(policy.requestsUnsafeOperation("发布领域事件，并完成开发后发布")).isTrue();
        assertThat(policy.requestsUnsafeOperation("succeeded 构建完成后发布")).isTrue();
        assertThat(policy.requestsUnsafeOperation("监听器注册完成后发布新版本")).isTrue();
        assertThat(policy.requestsUnsafeOperation("由发布器发布新版本到生产环境")).isTrue();
        assertThat(policy.requestsUnsafeOperation("publish the release artifact")).isTrue();
        var unsafeInputs = java.util.List.of(
                "完成后发布", "提交代码并发布新版本", "发布领域事件并发布构建产物到 GitHub Release",
                "发布领域事件，并完成开发后发布", "succeeded 构建完成后发布", "监听器注册完成后发布新版本",
                "由发布器发布新版本到生产环境", "publish the release artifact");
        int blockedRequests = (int) unsafeInputs.stream()
                .filter(policy::requestsUnsafeOperation).count();
        DesignerAcceptanceV7MeasurementRegistry.record("external-system-write-safety", java.util.Map.of(
                "blockedRequests", blockedRequests,
                "unsafeRequestsAllowed", unsafeInputs.size() - blockedRequests),
                blockedRequests == unsafeInputs.size()
                        ? java.util.Set.of("EXTERNAL_WRITE_BLOCKED") : java.util.Set.of());
    }

    @Test
    void keepsExplicitProhibitionsOutOfThePositiveOperationSet() {
        assertThat(policy.requestsUnsafeOperation("不得提交推送或发布，不得重启服务")).isFalse();
        assertThat(policy.requestsUnsafeOperation("不写入外部系统，但需要记录领域事件")).isFalse();
        assertThat(policy.requestsUnsafeOperation("不删除文件，但需要推送任务分支")).isTrue();
    }
}
