package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentControlMapper {
    @Insert("INSERT OR IGNORE INTO document_template_control(run_id) VALUES(#{id})")
    int initialize(String id);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_template_control WHERE run_id=#{id}")
    Optional<Control> find(String id);
    @Update("""
        UPDATE document_template_control SET stop_target=#{target},resume_state=#{resume},error_code=#{code},
          error_message=#{message},version=version+1 WHERE run_id=#{id} AND version=#{version}
        """)
    int stop(@Param("id") String id, @Param("version") long version, @Param("target") String target,
             @Param("resume") String resume, @Param("code") String code, @Param("message") String message);
    @Update("UPDATE document_template_control SET consecutive_errors=consecutive_errors+1,version=version+1 WHERE run_id=#{id}")
    int error(String id);
    @Update("UPDATE document_template_control SET consecutive_errors=0,version=version+1 WHERE run_id=#{id} AND consecutive_errors>0")
    int clearError(String id);
    @Update("""
        UPDATE document_template_control SET stop_target=NULL,resume_state=NULL,error_code=NULL,error_message=NULL,
          consecutive_errors=0,recoveries=recoveries+1,version=version+1 WHERE run_id=#{id} AND version=#{version}
        """)
    int recover(@Param("id") String id, @Param("version") long version);
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM document_template_command WHERE run_id=#{id} AND request_key=#{key}")
    Optional<Command> command(@Param("id") String id, @Param("key") String key);
    @Insert("""
        INSERT INTO document_template_command(run_id,request_key,request_sha256,command,resulting_version,created_at)
        VALUES(#{runId},#{requestKey},#{requestSha256},#{command},#{resultingVersion},#{createdAt})
        """)
    int insertCommand(Command command);
    @Delete("DELETE FROM task_archive WHERE task_id=(SELECT task_id FROM document_template_run WHERE id=#{id})")
    int restoreLinkedTask(String id);
    record Control(String runId, String stopTarget, String resumeState, String errorCode, String errorMessage,
                   int consecutiveErrors, int recoveries, long version) { }
    record Command(String runId, String requestKey, String requestSha256, String command, long resultingVersion, String createdAt) { }
}
