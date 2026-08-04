package io.opencode.loopper.persistence;
public record ProjectRow(String id, String name, String rootPath, String createdAt, String updatedAt, long version) { }
