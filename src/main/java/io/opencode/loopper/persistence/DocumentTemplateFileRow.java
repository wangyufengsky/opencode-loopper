package io.opencode.loopper.persistence;

public record DocumentTemplateFileRow(String id, String runId, int ordinal, String filename, String format,
        long sizeBytes, String sha256, String representationSha256, String parserVersion, String relativePath,
        int sectionCount, String limitationsJson) { }
