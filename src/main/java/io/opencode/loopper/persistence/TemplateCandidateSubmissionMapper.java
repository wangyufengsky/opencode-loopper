package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** Append-only tool receipts, independent of batch lifecycle and remote stop proof. */
@Mapper
public interface TemplateCandidateSubmissionMapper {
    @Select("SELECT * FROM template_candidate_submission WHERE batch_id=#{batchId} AND idempotency_key=#{key}")
    Optional<Receipt> replay(@Param("batchId") String batchId, @Param("key") String key);
    @Select("SELECT coalesce(max(submission_revision),0) FROM template_candidate_submission WHERE batch_id=#{batchId}")
    long revision(String batchId);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT output_json FROM template_candidate_submission WHERE batch_id=#{batchId} AND accepted=1")
    Optional<String> accepted(String batchId);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM template_candidate_submission WHERE batch_id=#{batchId} AND accepted=1")
    Optional<Receipt> acceptedReceipt(String batchId);
    @Select("SELECT * FROM template_candidate_submission WHERE batch_id=#{batchId} ORDER BY submission_revision DESC LIMIT 1")
    Optional<Receipt> latest(String batchId);
    @Insert("""
            INSERT INTO template_candidate_submission(batch_id,submission_revision,idempotency_key,candidate_sha256,
                accepted,output_json,response_json,created_at)
            VALUES(#{batchId},#{submissionRevision},#{idempotencyKey},#{candidateSha256},#{accepted},#{outputJson},#{responseJson},#{createdAt})
            """)
    int insert(Receipt receipt);
    record Receipt(String batchId, long submissionRevision, String idempotencyKey, String candidateSha256,
                   int accepted, String outputJson, String responseJson, String createdAt) { }
}
