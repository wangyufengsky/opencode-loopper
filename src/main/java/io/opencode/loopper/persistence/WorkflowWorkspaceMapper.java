package io.opencode.loopper.persistence;

import io.opencode.loopper.workflow.WorkflowWorkspacePlan;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowWorkspaceMapper {
    record Workspace(String attemptId,String requirementId,String projectId,String state,String projectDirectory,
            String canonicalRoot,String rootFingerprint,String sourceBranch,String sourceCommit,String baseTree,
            String branch,String checkpointRef,String seedSnapshotId,String seedSha256,String seedTree,
            String checkpointCommit,String checkpointTree,String stashCommit,String releaseReceiptJson,long version,String createdAt,String updatedAt,String objectRepository) {
        public WorkflowWorkspacePlan plan() { return new WorkflowWorkspacePlan(projectDirectory,canonicalRoot,rootFingerprint,
                sourceBranch,sourceCommit,baseTree,branch,checkpointRef,seedTree,objectRepository); }
    }
    @Select("SELECT * FROM workflow_workspace WHERE attempt_id=#{id}") Optional<Workspace> find(String id);
    @Insert("""
        INSERT INTO workflow_workspace(attempt_id,requirement_id,project_id,state,project_directory,canonical_root,
            root_fingerprint,source_branch,source_commit,base_tree,branch,checkpoint_ref,seed_snapshot_id,seed_sha256,seed_tree,
            version,created_at,updated_at,object_repository)
        VALUES(#{attemptId},#{requirementId},#{projectId},#{state},#{projectDirectory},#{canonicalRoot},
            #{rootFingerprint},#{sourceBranch},#{sourceCommit},#{baseTree},#{branch},#{checkpointRef},#{seedSnapshotId},#{seedSha256},#{seedTree},
            #{version},#{createdAt},#{updatedAt},#{objectRepository})
        """) int insert(Workspace row);
    @Update("""
        UPDATE workflow_workspace SET state=#{next},version=version+1,updated_at=#{now}
        WHERE attempt_id=#{id} AND version=#{version} AND state=#{previous}
        """) int transition(String id,long version,String previous,String next,String now);
    @Update("""
        UPDATE workflow_workspace SET checkpoint_commit=#{commit},checkpoint_tree=#{tree},stash_commit=#{stash},
            version=version+1,updated_at=#{now}
        WHERE attempt_id=#{id} AND version=#{version} AND state='CAPTURING' AND checkpoint_tree IS NULL
        """) int checkpoint(String id,long version,String commit,String tree,String stash,String now);
    @Update("""
        UPDATE workflow_workspace SET release_receipt_json=#{json},version=version+1,updated_at=#{now}
        WHERE attempt_id=#{id} AND version=#{version} AND state='RESTORED' AND release_receipt_json IS NULL
        """) int releaseReceipt(String id,long version,String json,String now);
}
