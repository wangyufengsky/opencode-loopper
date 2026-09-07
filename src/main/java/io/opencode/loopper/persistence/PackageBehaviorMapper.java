package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

public interface PackageBehaviorMapper {
    @Insert("INSERT INTO package_behavior_policy VALUES(#{id},'PACKAGE_BEHAVIOR_POLICY_V1')")
    int freezeBehaviorPolicy(String id);
    @Select("SELECT EXISTS(SELECT 1 FROM package_behavior_policy p JOIN designer_conversation c ON c.id=p.conversation_id WHERE c.external_session_id=#{remote})")
    boolean behaviorPolicyForRemote(String remote);
    @Select("SELECT * FROM package_behavior_preparation WHERE design_work_package_id=#{owner} AND discussion_revision=#{revision}")
    Optional<PackageBehaviorPreparationRow> findBehaviorPreparation(@Param("owner") String owner, @Param("revision") int revision);
    @Select("SELECT p.* FROM package_behavior_preparation p JOIN package_behavior_run r ON r.preparation_id=p.id WHERE r.run_id=#{run} AND r.book_sha256=p.book_sha256")
    Optional<PackageBehaviorPreparationRow> behaviorForRun(String run);
    @Select("SELECT * FROM package_behavior_preparation WHERE design_work_package_id=#{owner} AND remote_id=#{remote} ORDER BY discussion_revision DESC LIMIT 1")
    Optional<PackageBehaviorPreparationRow> behaviorForRemote(@Param("owner") String owner, @Param("remote") String remote);
    @Insert("""
        INSERT INTO package_behavior_preparation(id,design_work_package_id,discussion_revision,remote_id,requirement_sha256,
          base_prompt,context_json,prompt_version,state,created_at) VALUES(#{id},#{designWorkPackageId},#{discussionRevision},#{remoteId},#{requirementSha256},
          #{basePrompt},#{contextJson},#{promptVersion},'EXTRACTING',#{createdAt}) ON CONFLICT(design_work_package_id,discussion_revision) DO NOTHING
        """)
    int insertBehaviorPreparation(PackageBehaviorPreparationRow row);
    @Update("""
        UPDATE package_behavior_preparation SET state=#{next},extraction=coalesce(#{extraction},extraction),
          review_remote_id=coalesce(#{remote},review_remote_id),review_json=coalesce(#{review},review_json),
          book_json=coalesce(#{book},book_json),book_sha256=coalesce(#{sha},book_sha256),failure_code=#{code},version=version+1
        WHERE id=#{row.id} AND version=#{row.version} AND state=#{row.state}
        """)
    int transitionBehavior(@Param("row") PackageBehaviorPreparationRow row, @Param("next") String next,
            @Param("extraction") String extraction, @Param("remote") String remote, @Param("review") String review,
            @Param("book") String book, @Param("sha") String sha, @Param("code") String code);
    @Insert("INSERT INTO package_behavior_run VALUES(#{run},#{preparation},#{sha}) ON CONFLICT(run_id) DO NOTHING")
    int bindBehaviorRun(@Param("run") String run, @Param("preparation") String preparation, @Param("sha") String sha);
    @Update("""
        UPDATE package_behavior_preparation SET state='STOPPED',version=version+1
        WHERE design_work_package_id IN (SELECT id FROM design_work_package WHERE designer_session_id=#{designer})
          AND state NOT IN ('DISPATCHED','STOPPED')
        """)
    int stopBehaviorPreparations(String designer);
}
