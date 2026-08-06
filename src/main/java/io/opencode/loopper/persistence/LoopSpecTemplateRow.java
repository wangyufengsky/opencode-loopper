package io.opencode.loopper.persistence;

public record LoopSpecTemplateRow(String id, String name, String description, String state,
                                  String createdAt, String updatedAt, long version) { }
