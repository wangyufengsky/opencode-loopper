package io.opencode.loopper.persistence;

public record LoopSpecTemplateVersionRow(String id, String templateId, int versionNumber,
                                         String specJson, String specSha256, boolean immutable,
                                         boolean autoStartApproved, String createdAt) { }
