package io.opencode.loopper.service.workflow;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.service.*;
final class WorkflowFailures {
    private WorkflowFailures() { }
    static String code(RuntimeException failure) {
        return safe(switch(failure) {
            case SessionFailure typed -> typed.code();case TaskFailure typed -> typed.code();
            case BadRequestException typed -> typed.code();case ConflictException typed -> typed.code();
            default -> "WORKFLOW_MODEL_RECOVERY_REQUIRED";
        });
    }
    static String safe(String code) { return code!=null && code.matches("[A-Z][A-Z0-9_]{1,119}")?code:"WORKFLOW_MODEL_RECOVERY_REQUIRED"; }
}
