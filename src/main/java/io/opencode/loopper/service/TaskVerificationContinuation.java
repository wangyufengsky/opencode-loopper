package io.opencode.loopper.service;

enum VerificationAction { NONE, RETRY_STAGE, NEXT_STAGE, PACKAGE_CHECKPOINT, FINAL_REVIEW }

record VerificationContinuation(VerificationAction action, String taskId, String stageId,
                                String attemptId, String prompt, String packageRunId) {
    static VerificationContinuation none(String taskId) {
        return new VerificationContinuation(VerificationAction.NONE, taskId, null, null, null, null);
    }
    static VerificationContinuation retry(String taskId, String stageId, String prompt) {
        return new VerificationContinuation(VerificationAction.RETRY_STAGE, taskId, stageId, null, prompt, null);
    }
    static VerificationContinuation nextStage(String taskId, String stageId, String prompt) {
        return new VerificationContinuation(VerificationAction.NEXT_STAGE, taskId, stageId, null, prompt, null);
    }
    static VerificationContinuation packageCheckpoint(String taskId, String attemptId,
                                                      String stageId, String packageRunId) {
        return new VerificationContinuation(VerificationAction.PACKAGE_CHECKPOINT, taskId, stageId,
                attemptId, null, packageRunId);
    }
    static VerificationContinuation finalReview(String taskId, String attemptId, String stageId) {
        return new VerificationContinuation(VerificationAction.FINAL_REVIEW, taskId, stageId, attemptId, null, null);
    }
}
