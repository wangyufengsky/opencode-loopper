package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** Durable stop intent and proof are separate from business acceptance and cancellation. */
@Mapper
public interface TemplateBatchRecoveryMapper {
    // Remote I/O can suspend transactions; never reuse an intent read before another transaction committed.
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM template_batch_recovery WHERE batch_id=#{id}")
    Optional<Recovery> find(String id);

    @Insert("""
            INSERT INTO template_batch_recovery(batch_id,session_id,prompt_sha256,action,command_id,requested_at,not_before)
            VALUES(#{batchId},#{sessionId},#{promptSha256},#{action},#{commandId},#{requestedAt},#{notBefore})
            ON CONFLICT(batch_id) DO NOTHING
            """)
    int insert(Recovery row);

    @Update("""
            UPDATE template_batch_recovery SET proof=#{proof},proof_at=#{at},error_code=NULL
            WHERE batch_id=#{id} AND proof IS NULL
            """)
    int proof(String id, String proof, String at);

    @Update("UPDATE template_batch_recovery SET last_attempt_at=#{at},error_code=#{code} WHERE batch_id=#{id} AND proof IS NULL")
    int attempted(String id, String at, String code);
    @Update("UPDATE template_batch_recovery SET not_before=#{at} WHERE batch_id=#{id} AND not_before>#{at} AND proof IS NULL")
    int expedite(String id, String at);
    @Select("SELECT * FROM template_batch_recovery_command WHERE command_id=#{id}")
    Optional<Command> findCommand(String id);
    @Insert("INSERT INTO template_batch_recovery_command VALUES(#{commandId},#{batchId},#{action},#{expectedVersion},#{createdAt})")
    int insertCommand(Command row);

    @Select("SELECT * FROM template_batch_observation WHERE batch_id=#{id}")
    Optional<Observation> observation(String id);

    @Insert("""
            INSERT INTO template_batch_observation(batch_id,session_id,prompt_sha256,observed_at,last_activity_at,
                last_progress_at,fingerprint,progress_fingerprint,remote_state,connected)
            VALUES(#{batchId},#{sessionId},#{promptSha256},#{observedAt},#{lastActivityAt},#{lastProgressAt},
                #{fingerprint},#{progressFingerprint},#{remoteState},#{connected})
            ON CONFLICT(batch_id) DO UPDATE SET session_id=excluded.session_id,prompt_sha256=excluded.prompt_sha256,
                observed_at=excluded.observed_at,last_activity_at=excluded.last_activity_at,
                last_progress_at=excluded.last_progress_at,fingerprint=excluded.fingerprint,
                progress_fingerprint=excluded.progress_fingerprint,remote_state=excluded.remote_state,connected=excluded.connected
            WHERE excluded.observed_at > template_batch_observation.observed_at
            """)
    int observe(Observation row);

    @Delete("DELETE FROM template_batch_observation WHERE batch_id IN (SELECT id FROM template_task_batch WHERE task_id=#{id})")
    int deleteObservations(String id);
    @Delete("DELETE FROM template_batch_recovery WHERE batch_id IN (SELECT id FROM template_task_batch WHERE task_id=#{id})")
    int deleteRecovery(String id);
    @Delete("DELETE FROM template_batch_recovery_command WHERE batch_id IN (SELECT id FROM template_task_batch WHERE task_id=#{id})")
    int deleteCommands(String id);

    record Command(String commandId, String batchId, String action, long expectedVersion, String createdAt) { }

    record Recovery(String batchId, String sessionId, String promptSha256, String action, String commandId,
                    String requestedAt, String notBefore, String proof, String proofAt, String lastAttemptAt, String errorCode) { }
    record Observation(String batchId, String sessionId, String promptSha256, String observedAt, String lastActivityAt,
                       String lastProgressAt, String fingerprint, String progressFingerprint, String remoteState, int connected) { }
}
