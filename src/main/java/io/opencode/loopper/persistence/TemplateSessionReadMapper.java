package io.opencode.loopper.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** Session titles from persisted batch identity, never from list position or remote transcripts. */
@Mapper
public interface TemplateSessionReadMapper {
    @Select("""
        SELECT s.id AS session_id, b.purpose, b.ordinal + 1 AS ordinal,
            CASE WHEN b.purpose='REVIEW' THEN p.review_batches ELSE p.contributor_batches END AS total,
            CASE WHEN b.purpose='REVIEW' THEN b.ordinal+1
                 WHEN b.purpose='CONTRIBUTOR' THEN p.review_batches+b.ordinal+1 END AS overall_ordinal,
            p.review_batches+p.contributor_batches AS overall_total,
            MAX(0,a.ordinal-1) AS repair_round,
            CASE WHEN instr(s.id,'-cleanup-')>0 THEN 1 ELSE 0 END AS cleanup
        FROM execution_session s JOIN template_task_run r ON r.task_id=s.task_id
        JOIN stage st ON st.id=s.stage_id AND st.task_id=s.task_id AND st.ordinal=1
        JOIN attempt a ON a.id=s.attempt_id AND a.task_id=s.task_id
        LEFT JOIN template_task_batch b ON b.session_id=s.id AND b.task_id=s.task_id AND b.attempt_id=s.attempt_id
        LEFT JOIN template_task_plan p ON p.task_id=s.task_id
        WHERE s.task_id=#{taskId}
        """)
    List<Batch> sessions(String taskId);
    record Batch(String sessionId,String purpose,Integer ordinal,Integer total,Integer overallOrdinal,
                 Integer overallTotal,int repairRound,boolean cleanup) { }
}
