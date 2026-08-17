package io.opencode.loopper.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationMetaDescriptionPolicyTest {

    @Test
    void recognizesOnlyClosedSetVerificationMetaDescriptions() {
        assertThat(VerificationMetaDescriptionPolicy.isMetaDescription("全量测试通过。"))
                .isTrue();
        assertThat(VerificationMetaDescriptionPolicy.isMetaDescription("  ALL   TESTS PASS "))
                .isTrue();
        assertThat(VerificationMetaDescriptionPolicy.isMetaDescription("构建成功"))
                .isTrue();
        assertThat(VerificationMetaDescriptionPolicy.isMetaDescription("发布未知事件时不报错且状态不变"))
                .isFalse();
        assertThat(VerificationMetaDescriptionPolicy.isMetaDescription("构建成功后广播状态变更事件"))
                .isFalse();
    }
}
