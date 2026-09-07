package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class PackageBehaviorSourceReviewTest {
    static final String ORIGINAL = "领取后取消进入正在停止；已在正在停止状态时重复取消保持正在停止。";
    final ObjectMapper json = new ObjectMapper();
    static PackageBehaviorSourceReview.Review review(String original, String extraction, String context) {
        return new PackageBehaviorSourceReview.Review(PackageBehaviorSourceReview.VERSION, PackageBehaviorSourceReview.hash(original),
                PackageBehaviorSourceReview.hash(extraction), PackageBehaviorSourceReview.hash(context), PackageBehaviorPrompts.example(),
                List.of(new PackageBehaviorSourceReview.SourceCheck("REQ-L001", "取消", "MODELED", List.of("CANCEL", "REPEAT"), "before 表示触发前状态；首次取消与重复取消分别核对；after 均为正在停止")), List.of());
    }
    ObjectNode valid() { return (ObjectNode) json.valueToTree(review(ORIGINAL, "draft", "{}")); }
    PackageBehaviorSourceReview.Result check(ObjectNode value) { return new PackageBehaviorSourceReview(json).validate(ORIGINAL, "draft", "{}", json.writeValueAsString(value)); }
    @Test void fixedExampleAndReviewPassProductionModelValidator() {
        var result = check(valid()); assertThat(result.accepted()).as(result.toString()).isTrue();
        assertThat(result.bookSha256()).hasSize(64); assertThat(result.bookJson()).contains("CLAIMED", "STOPPING");
        assertThat(new PackageBehaviorSourceReview(json).validate(ORIGINAL, "draft", "{}", "```json\n" + json.writeValueAsString(valid()) + "\n```").accepted()).isTrue();
    }
    @Test void completeReviewExampleIncludesNonBehaviorSourceAndParsesWithoutRepairs() {
        var sample = PackageBehaviorPrompts.reviewExample(json);
        assertThat(new PackageBehaviorSourceReview(json).validate(ORIGINAL + "\n测试范围为取消行为的聚焦测试。", "draft", "{}", json.writeValueAsString(sample)).accepted()).isTrue();
        assertThat(PackageBehaviorPreparation.reasons("输入为布尔值，若为真则返回否，否则返回是。")).isEmpty();
    }
    @Test void sourceExtractionAndContextHashesCannotBeRebound() {
        for (String name : List.of("sourceSha256", "extractionSha256", "contextSha256")) {
            var value = valid(); value.put(name, "0".repeat(64)); assertThat(check(value).accepted()).as(name).isFalse();
        }
    }
    @Test void fabricatedQuoteMissingSourceOrObligationAndFindingsRemainUnconfirmed() {
        var value = valid(); ((ObjectNode) value.path("sourceChecks").get(0)).put("quote", "不存在于原文"); assertThat(check(value).accepted()).isFalse();
        value = valid(); value.set("sourceChecks", json.createArrayNode()); assertThat(check(value).accepted()).isFalse();
        value = valid(); ((ObjectNode) value.path("sourceChecks").get(0)).set("obligationRefs", json.valueToTree(List.of("CANCEL"))); assertThat(check(value).problems()).contains("SOURCE_REVIEW_OBLIGATION_COVERAGE");
        value = valid(); value.set("findings", json.valueToTree(List.of("REQ-L001 未说明取消失败的业务选择"))); assertThat(check(value).accepted()).isFalse();
    }
    @Test void reviewerCannotLabelBehaviorAsNonBehaviorToSkipObligations() {
        var value = valid(); var check = (ObjectNode) value.path("sourceChecks").get(0);
        check.put("disposition", "NON_BEHAVIOR"); check.set("obligationRefs", json.createArrayNode());
        ((ObjectNode) value.path("book")).set("unmodeledSources", json.valueToTree(List.of("REQ-L001")));
        assertThat(check(value).accepted()).isFalse();
    }
    @Test void inconsistentReviewedModelCannotBeFrozenEvenWithEmptyFindings() {
        var value = valid(); var obligations = (tools.jackson.databind.node.ArrayNode) value.path("book").path("obligations");
        var changed = (ObjectNode) obligations.get(0).path("when"); changed.put("variable", "after");
        assertThat(check(value).accepted()).isFalse();
    }
    @Test void unknownFieldsMalformedJsonAndOversizedOutputAreBoundedFailures() {
        var value = valid(); value.put("approvedByUser", true); assertThat(check(value).accepted()).isFalse();
        var service = new PackageBehaviorSourceReview(json);
        for (String output : List.of("null", "[]", "not json", "中".repeat(22000))) assertThat(service.validate(ORIGINAL, "draft", "{}", output).accepted()).isFalse();
    }
    @Test void simpleTasksHaveNoExtraTurnsAndKnownComplexDefectsTriggerPreparation() {
        assertThat(PackageBehaviorPreparation.reasons("新增固定返回值的测试，无需幂等或补偿。")).isEmpty();
        assertThat(PackageBehaviorPreparation.reasons("页面显示运行状态。")).isEmpty();
        assertThat(PackageBehaviorPreparation.reasons("管理员或持有未撤销委托的用户可以读取；若资源已归档则所有角色均拒绝。")).isNotEmpty();
        assertThat(PackageBehaviorPreparation.reasons("领取与取消并发只能有一个成功；领取先成功时取消等待停止确认。")).isNotEmpty();
    }
}
