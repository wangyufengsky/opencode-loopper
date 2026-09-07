package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

public interface PackageDesignEvidenceMapper {
    @Insert("""
            INSERT INTO package_design_evidence(run_id,policy_version,requirement_sha256,snapshot_json,snapshot_sha256,created_at)
            VALUES(#{runId},#{policyVersion},#{requirementSha256},#{snapshotJson},#{snapshotSha256},#{createdAt})
            ON CONFLICT(run_id) DO NOTHING
            """)
    int insertPackageDesignEvidence(PackageDesignEvidenceRow row);

    @Select("SELECT * FROM package_design_evidence WHERE run_id=#{runId}")
    Optional<PackageDesignEvidenceRow> findPackageDesignEvidence(String runId);
}
