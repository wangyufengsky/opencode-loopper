package io.opencode.loopper.template;

/** Paths are server-resolved before confirmation. sourcePath and testOutputPath are project-relative. */
public record SourceTemplateParameters(String projectRoot, String sourcePath,
        String testOutputPath, String documentPath, String requirements) { }
