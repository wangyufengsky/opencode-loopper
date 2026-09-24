package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Complete examples for the model-owned document; executable policy stays in the codec/compiler. */
final class PackageDesignCandidatePromptContract {
    private PackageDesignCandidatePromptContract() { }

    static String readyExample() {
        return RolePromptResources.read("prompt.v1.PackageDesignCandidatePromptContract.block01");
    }

    static String instructions() {
        return (RolePromptResources.read("prompt.v1.PackageDesignCandidatePromptContract.block02.segment0")
                + String.format("%s", (Object) (readyExample()))
                + RolePromptResources.read("prompt.v1.PackageDesignCandidatePromptContract.block02.segment1")
                + String.format("%d", (Object) (io.opencode.loopper.domain.PackageDesignLimits.MAX_SCENARIOS))
                + RolePromptResources.read("prompt.v1.PackageDesignCandidatePromptContract.block02.segment2")
                + String.format("%d", (Object) (io.opencode.loopper.domain.PackageDesignLimits.MAX_FACTS))
                + ".\nStages <= "
                + String.format("%d", (Object) (io.opencode.loopper.domain.PackageDesignLimits.MAX_STAGES))
                + RolePromptResources.read("prompt.v1.PackageDesignCandidatePromptContract.block02.segment4")
                + String.format("%s", (Object) (Arrays.stream(DesignerSemanticContracts.DesignGapCode.values())
                .map(Enum::name).collect(Collectors.joining(", "))))
                + ".\n");
    }
}
