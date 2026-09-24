package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.service.roles.RolePromptResources;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.OpenCodeClient.SessionProfile;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Builds bounded commit-message evidence and normalizes the model's single-line subject. */
final class CommitMessagePromptFactory {
    private static final Pattern PREFIX = Pattern.compile("^#[0-9]{4}_");
    private final TaskService tasks;
    private final PublicationGitClient git;
    private final RoleSessions roleSessions;

    CommitMessagePromptFactory(TaskService tasks, PublicationGitClient git) {
        this(tasks, git, null);
    }

    CommitMessagePromptFactory(TaskService tasks, PublicationGitClient git, RoleSessions roleSessions) {
        this.tasks = tasks;
        this.git = git;
        this.roleSessions = roleSessions;
    }

    String prompt(TaskRow task, Path workspace) {
        return RoleSessions.render(roleSessions, "TASK", task.id(), SessionProfile.GENERAL_READ_ONLY,
                "COMMIT_MESSAGE", () -> promptUnscoped(task, workspace));
    }

    private String promptUnscoped(TaskRow task, Path workspace) {
        return RolePromptResources.read("commit.subject") + task.title()
                + "\n任务目标：" + taskGoal(task)
                + "\n实际 Git 变更摘要：\n" + publicationEvidence(task, workspace) + "\n";
    }

    String normalizeSubject(String output) {
        if (output == null) {
            throw new ServiceUnavailableException("COMMIT_MESSAGE_AI_EMPTY", "AI 没有返回提交信息");
        }
        String value = output.strip();
        if (value.startsWith("```") && value.endsWith("```")) {
            value = value.substring(3, value.length() - 3).strip();
            if (value.startsWith("text")) value = value.substring(4).strip();
        }
        value = CommitMessagePolicy.subject(PREFIX.matcher(value).replaceFirst("").replace("`", "").replace("\"", ""));
        if (value.codePointCount(0, value.length()) > 120) value = value.substring(0, value.offsetByCodePoints(0, 120)).strip();
        if (value.isBlank()) {
            throw new ServiceUnavailableException("COMMIT_MESSAGE_AI_EMPTY", "AI 没有返回可用的提交信息");
        }
        return value;
    }

    private String publicationEvidence(TaskRow task, Path workspace) {
        var checkpoint = tasks.latestWorkspaceCheckpoint(task.id());
        if (TaskState.AWAITING_DECISION.name().equals(task.state())
                && checkpoint != null && "READY".equals(checkpoint.state())) {
            String stat = git.allowEmpty(workspace,
                    List.of("git", "diff", "--stat", "--no-ext-diff", checkpoint.baselineCommit(),
                            checkpoint.checkpointTree(), "--"), "GIT_DIFF_SUMMARY_FAILED");
            String status = git.allowEmpty(workspace,
                    List.of("git", "diff", "--name-status", "--no-ext-diff", checkpoint.baselineCommit(),
                            checkpoint.checkpointTree(), "--"), "GIT_STATUS_FAILED");
            return bounded((stat.isBlank() ? "无文件统计" : stat) + "\n" + status);
        }
        String stat = git.allowEmpty(workspace,
                List.of("git", "diff", "--stat", "--no-ext-diff", task.baselineCommit(), "--"),
                "GIT_DIFF_SUMMARY_FAILED");
        String status = git.allowEmpty(workspace,
                List.of("git", "status", "--short", "--untracked-files=all"), "GIT_STATUS_FAILED");
        return bounded((stat.isBlank() ? "无已跟踪文件统计" : stat) + "\n" + status);
    }

    private String taskGoal(TaskRow task) {
        try {
            return tasks.goal(task.id());
        } catch (RuntimeException ignored) {
            return task.title();
        }
    }

    private String bounded(String evidence) {
        return evidence.substring(0, Math.min(evidence.length(), 5_000));
    }
}
