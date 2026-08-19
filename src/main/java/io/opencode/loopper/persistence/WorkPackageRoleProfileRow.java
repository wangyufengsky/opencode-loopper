package io.opencode.loopper.persistence;

public record WorkPackageRoleProfileRow(String id, String designerSessionId, String packageId,
                                        String taskProfileId, String rolePackId, String rolePackVersion,
                                        String executionStrategy, String testPolicy, String technologiesJson) { }
