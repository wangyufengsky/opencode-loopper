package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

public interface PptRecoveryMapper {
    record Recovery(String generationId, int observedAttempt, long revision, String fingerprint, int failures,
                    String retryAt, String checkpointJson, String errorCode) { }
    record Failure(String runId, String category, String errorCode, String detail, String createdAt) { }
    @Select("SELECT * FROM ppt_generation_recovery WHERE generation_id=#{id}") Optional<Recovery> recovery(String id);
    @Insert("INSERT OR IGNORE INTO ppt_generation_recovery(generation_id) VALUES(#{id})") int enable(String id);
    @Update("""
        UPDATE ppt_generation_recovery SET observed_attempt=#{observedAttempt},revision=#{revision},fingerprint=#{fingerprint},
          failures=#{failures},retry_at=#{retryAt},checkpoint_json=#{checkpointJson},error_code=#{errorCode} WHERE generation_id=#{generationId}
        """) int save(Recovery row);
    @Update("UPDATE ppt_generation_recovery SET retry_at=NULL WHERE generation_id=#{id}") int clear(String id);
    @Select("SELECT * FROM ppt_agent_failure WHERE run_id=#{run}") Optional<Failure> failure(String run);
    @Insert("INSERT INTO ppt_agent_failure(run_id,category,error_code,detail,created_at) VALUES(#{runId},#{category},#{errorCode},#{detail},#{createdAt})") int insertFailure(Failure row);
}
