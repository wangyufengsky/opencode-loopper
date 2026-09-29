package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowWriterCandidateMapper {
    record Candidate(String attemptId,long attemptVersion,String contentJson,String sha256,String createdAt) { }
    @Select("SELECT * FROM workflow_writer_candidate WHERE attempt_id=#{id}")
    Optional<Candidate> find(String id);
    @Insert("""
            INSERT INTO workflow_writer_candidate(attempt_id,attempt_version,content_json,sha256,created_at)
            VALUES(#{attemptId},#{attemptVersion},#{contentJson},#{sha256},#{createdAt})
            """)
    int insert(Candidate row);
}
