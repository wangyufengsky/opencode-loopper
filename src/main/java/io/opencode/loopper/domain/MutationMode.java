package io.opencode.loopper.domain;

public enum MutationMode implements DescribedEnum {
    READ_ONLY("只读"), WRITE_FILES("写入文件"), WRITE_CODE("写入代码"),
    SAFE_LOCAL_MAINTENANCE("安全本地维护");

    private final String description;
    MutationMode(String description) { this.description = description; }
    @Override public String description() { return description; }
}
