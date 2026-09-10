package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.DirectWorkspaceLeaseCoordinator;
import io.opencode.loopper.runtime.GitWorktreeManager;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Completes the initial Designer handoff before admitting a new Task to its workspace. */
@Service
public class TaskStartPreflight {
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final GitWorktreeManager worktrees;
    private final RollingPackageTaskHooks rollingPackages;
    private final StoryAccountingCoordinator accounting;

    public TaskStartPreflight(LoopperMapper mapper, ProjectService projects, GitWorktreeManager worktrees,
                              RollingPackageTaskHooks rollingPackages, StoryAccountingCoordinator accounting) {
        this.mapper = mapper;
        this.projects = projects;
        this.worktrees = worktrees;
        this.rollingPackages = rollingPackages;
        this.accounting = accounting;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<DirectWorkspaceLeaseCoordinator.WorkspaceIdentity> prepare(TaskRow requested) {
        // A fresh Task has no execution clock, lease, branch or Attempt yet. Retired accounting
        // may still be using the registered directory, so finish it before any workspace preparation.
        if (TaskState.PENDING_START.name().equals(requested.state())) {
            accounting.awaitInitialTaskHandoff(requested.id());
        }
        TaskRow current = mapper.findTask(requested.id()).orElseThrow(() -> new NotFoundException("Task not found"));
        if (current.version() != requested.version() || !current.state().equals(requested.state())) {
            return Optional.empty(); // Cancellation or another Start won while the external call was pending.
        }
        Path root = Path.of(projects.get(current.projectId()).rootPath());
        if (current.baselineCommit() != null && !worktrees.inspect(root).isolatedWorktree()
                && !(rollingPackages.applies(current.id()) && GitWorktreeManager.DIRECT_BRANCH.equals(current.branchName()))) {
            throw new TaskFailure("REWORK_REPOSITORY_REQUIRED", "Rework requires a Git source branch");
        }
        return Optional.of(DirectWorkspaceLeaseCoordinator.identify(root));
    }
}
