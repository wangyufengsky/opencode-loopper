package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SourceUnitEvidenceMapper {
    @Select("SELECT * FROM source_unit_evidence WHERE run_id=#{run}")
    Optional<Evidence> find(String run);
    @Insert("INSERT INTO source_unit_evidence VALUES(#{runId},#{taskId},#{cycleId},#{contentJson},#{sha256},#{createdAt})")
    int insert(Evidence evidence);
    record Evidence(String runId, String taskId, String cycleId, String contentJson, String sha256, String createdAt) { }
}
