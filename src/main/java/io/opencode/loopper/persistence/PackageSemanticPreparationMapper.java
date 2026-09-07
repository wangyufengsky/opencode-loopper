package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

public interface PackageSemanticPreparationMapper {
    @Select("SELECT * FROM package_semantic_preparation WHERE design_work_package_id=#{owner} AND discussion_revision=#{revision}")
    Optional<PackageSemanticPreparationRow> findPackageSemanticPreparation(@Param("owner") String owner, @Param("revision") int revision);
    @Insert("""
        INSERT INTO package_semantic_preparation(id,design_work_package_id,discussion_revision,remote_id,requirement_sha256,
          prompt_version,reasons_json,base_prompt,state,created_at,version)
        VALUES(#{id},#{designWorkPackageId},#{discussionRevision},#{remoteId},#{requirementSha256},#{promptVersion},
          #{reasonsJson},#{basePrompt},'PREPARED',#{createdAt},0)
        ON CONFLICT(design_work_package_id,discussion_revision) DO NOTHING
        """)
    int insertPackageSemanticPreparation(PackageSemanticPreparationRow row);
    @Update("""
        UPDATE package_semantic_preparation SET state=#{next},material=coalesce(#{material},material),failure_code=#{code},version=version+1
        WHERE id=#{id} AND version=#{version} AND state=#{expected}
        """)
    int transitionPackageSemanticPreparation(@Param("id") String id, @Param("version") long version,
            @Param("expected") String expected, @Param("next") String next, @Param("material") String material, @Param("code") String code);
}
