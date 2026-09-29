package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.workflow.WorkflowWorkspacePlan;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Operates the registered checkout using persisted branch identities. Does not own a queue or business lifecycle. */
@Component
public final class WorkflowGitWorkspace {
    private final GitEvidenceProcess git;
    private final GitWorktreeManager worktrees;
    public WorkflowGitWorkspace(GitEvidenceProcess git,GitWorktreeManager worktrees) { this.git=git;this.worktrees=worktrees; }
    public WorkflowWorkspacePlan inspect(String directory,String requirement,String attempt,String seedTree) {
        outsideTransaction();
        if (!requirement.matches("[a-zA-Z0-9-]{1,80}") || !attempt.matches("[a-zA-Z0-9-]{1,80}")) throw changed();
        var scope=GitProjectScope.require(git,Path.of(directory));
        var identity=DirectWorkspaceLeaseCoordinator.identify(scope.project());
        var current=worktrees.inspectDirtyWorkspace(scope.project());
        if (!current.clean()) throw new TaskFailure("WORKFLOW_WORKSPACE_DIRTY","项目有未提交文件，请先处理这些文件再恢复节点准备");
        if (current.branch().startsWith("loopper/")) throw new TaskFailure("WORKFLOW_SOURCE_BRANCH_REQUIRED","请先恢复项目的源分支，再准备流程节点");
        return new WorkflowWorkspacePlan(scope.project().toString(),identity.canonicalRoot(),identity.rootFingerprint(),
                current.branch(),current.head(),read(scope.repository(),"rev-parse",current.head()+"^{tree}").strip(),
                "loopper/workflow-"+attempt,"refs/loopper/checkpoints/"+requirement+"/"+attempt,seedTree);
    }
    public void enter(WorkflowWorkspacePlan plan) {
        outsideTransaction(); identity(plan);
        var current=worktrees.inspectDirtyWorkspace(project(plan));
        if (!current.head().equals(plan.sourceCommit())) throw changed();
        if (current.branch().equals(plan.sourceBranch())) {
            requireSource(plan);
            String existing=optional(root(plan),"rev-parse","--verify","refs/heads/"+plan.branch());
            if (existing!=null && !existing.equals(plan.sourceCommit())) throw changed();
            if (existing==null) run(root(plan),"switch","--create",plan.branch(),plan.sourceCommit());
            else run(root(plan),"switch",plan.branch());
        } else if (!current.branch().equals(plan.branch())) throw changed();
        requireBranch(plan);
        if (plan.seedTree()!=null) {
            if (!worktrees.workspaceMatchesTree(project(plan),plan.branch(),plan.seedTree())) {
                if (!worktrees.inspectDirtyWorkspace(project(plan)).clean()) throw changed();
                worktrees.materializeCheckpointTree(project(plan),plan.branch(),plan.seedTree());
            }
            // Recovery may observe read-tree complete but the final index reset not yet acknowledged.
            run(root(plan),"reset","--mixed","HEAD");
            if (!worktrees.workspaceMatchesTree(project(plan),plan.branch(),plan.seedTree())) throw changed();
        } else if (!worktrees.inspectDirtyWorkspace(project(plan)).clean()) throw changed();
        identity(plan); requireBranch(plan);
    }
    public GitWorktreeManager.WorkspaceCheckpoint capture(WorkflowWorkspacePlan plan,String requirement,String attempt) {
        outsideTransaction(); identity(plan); requireBranch(plan);
        var checkpoint=worktrees.freezePinnedWorkspace(project(plan),requirement,attempt,plan.branch(),plan.sourceCommit());
        if (!checkpoint.checkpointRef().equals(plan.checkpointRef())) throw changed();
        identity(plan); return checkpoint;
    }
    public void requireReady(WorkflowWorkspacePlan plan) {
        outsideTransaction();identity(plan);requireBranch(plan);
        if (!worktrees.workspaceMatchesTree(project(plan),plan.branch(),plan.seedTree()==null?plan.baseTree():plan.seedTree())) throw changed();
    }
    public void restore(WorkflowWorkspacePlan plan) {
        outsideTransaction(); identity(plan);
        if (onSource(plan)) return;
        requireBranch(plan);
        if (!worktrees.inspectDirtyWorkspace(project(plan)).clean()) throw changed();
        if (!read(root(plan),"rev-parse","--verify","refs/heads/"+plan.sourceBranch()).strip().equals(plan.sourceCommit())) throw changed();
        worktrees.restoreSourceBranch(project(plan),plan.branch(),plan.sourceBranch());
        requireSource(plan); identity(plan);
    }
    public boolean onSource(WorkflowWorkspacePlan plan) {
        outsideTransaction(); identity(plan);
        var current=worktrees.inspectDirtyWorkspace(project(plan));
        return current.branch().equals(plan.sourceBranch()) && current.head().equals(plan.sourceCommit()) && current.clean();
    }
    public void requireSource(WorkflowWorkspacePlan plan) { if (!onSource(plan)) throw changed(); }
    public DirectWorkspaceLeaseCoordinator.WorkspaceIdentity identity(WorkflowWorkspacePlan plan) {
        outsideTransaction();
        var actual=DirectWorkspaceLeaseCoordinator.identify(project(plan));
        if (!actual.canonicalRoot().equals(plan.canonicalRoot()) || !actual.rootFingerprint().equals(plan.rootFingerprint())) throw changed();
        return actual;
    }
    private void requireBranch(WorkflowWorkspacePlan plan) {
        var current=worktrees.inspectDirtyWorkspace(project(plan));
        if (!current.branch().equals(plan.branch()) || !current.head().equals(plan.sourceCommit())) throw changed();
    }
    private void run(Path directory,String... args) { read(directory,args); }
    private String read(Path directory,String... args) {
        var result=git.run(directory,Duration.ofSeconds(30),List.of(args)); result.requireSuccess(List.of(args));return result.output();
    }
    private String optional(Path directory,String... args) {
        var result=git.run(directory,Duration.ofSeconds(30),List.of(args)); return result.exitCode()==0?result.output().strip():null;
    }
    private static Path project(WorkflowWorkspacePlan plan) { return Path.of(plan.projectDirectory()); }
    private static Path root(WorkflowWorkspacePlan plan) { return Path.of(plan.canonicalRoot()); }
    private static void outsideTransaction() { if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Workflow Git I/O in transaction"); }
    private static TaskFailure changed() { return new TaskFailure("WORKFLOW_WORKSPACE_CHANGED","节点工作区、分支或基准不符合冻结记录，请检查后恢复；现有文件已保留"); }
}
