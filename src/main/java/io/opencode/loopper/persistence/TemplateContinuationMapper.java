package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** Immutable continuation intents; the owning batch holds the current dispatch checkpoint. */
@Mapper
public interface TemplateContinuationMapper {
    @Select("SELECT * FROM template_length_continuation WHERE batch_id=#{id} ORDER BY ordinal DESC LIMIT 1")
    Optional<Continuation> latest(String id);
    @Select("SELECT count(DISTINCT candidate_sha256) FROM template_candidate_submission WHERE batch_id=#{id}")
    int distinctSubmissions(String id);
    @Insert("""
            INSERT INTO template_length_continuation(batch_id,ordinal,prior_prompt_json,prompt_json,prompt_sha256,
                distinct_submissions,stagnant_lengths,created_at)
            VALUES(#{batchId},#{ordinal},#{priorPromptJson},#{promptJson},#{promptSha256},#{distinctSubmissions},#{stagnantLengths},#{createdAt})
            """)
    int insert(Continuation row);
    record Continuation(String batchId, int ordinal, String priorPromptJson, String promptJson,
                        String promptSha256, int distinctSubmissions, int stagnantLengths, String createdAt) { }
}
