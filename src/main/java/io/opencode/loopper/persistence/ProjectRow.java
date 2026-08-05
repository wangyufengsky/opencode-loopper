package io.opencode.loopper.persistence;
public record ProjectRow(String id, String name, String rootPath, String description, String createdAt,
                         String updatedAt, int managed, long version) { }
