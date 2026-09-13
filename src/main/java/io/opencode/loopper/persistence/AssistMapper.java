package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** Persistence for auxiliary capabilities; no lifecycle mutations. */
@Mapper
public interface AssistMapper {
    record DatabaseRow(String id, String name, String configJson, String credentialRef, int enabled,
                       int archived, long version, String createdAt, String updatedAt) { }
    @Select("SELECT * FROM database_connection WHERE id=#{id}")
    DatabaseRow database(String id);
    @Select("SELECT * FROM database_connection WHERE (created_at,id) > (#{time},#{id}) ORDER BY created_at,id LIMIT #{limit}")
    List<DatabaseRow> databases(String time, String id, int limit);
    @Select("""
        SELECT * FROM database_connection WHERE (created_at,id) > (#{time},#{id})
        AND (#{query}='' OR instr(lower(name),lower(#{query}))>0 OR instr(lower(json_extract(config_json,'$.host')),lower(#{query}))>0)
        AND (#{type}='' OR json_extract(config_json,'$.type')=#{type})
        AND (#{state}='ALL' OR (#{state}='AVAILABLE' AND archived=0) OR (#{state}='ENABLED' AND enabled=1 AND archived=0)
             OR (#{state}='DISABLED' AND enabled=0 AND archived=0) OR (#{state}='ARCHIVED' AND archived=1))
        ORDER BY created_at,id LIMIT #{limit}
        """)
    List<DatabaseRow> filteredDatabases(String time,String id,int limit,String query,String type,String state);
    @Select("SELECT d.* FROM database_connection d JOIN database_connection_project p ON p.connection_id=d.id WHERE p.project_id=#{projectId} AND d.enabled=1 AND d.archived=0 ORDER BY d.id LIMIT 101")
    List<DatabaseRow> projectDatabases(String projectId);
    @Select("SELECT project_id FROM database_connection_project WHERE connection_id=#{id} ORDER BY project_id")
    List<String> databaseProjects(String id);
    @Select({"<script>SELECT connection_id,project_id FROM database_connection_project WHERE connection_id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>"})
    List<Map<String,String>> databaseBindings(List<String> ids);
    @Insert("INSERT INTO database_connection VALUES(#{id},#{name},#{configJson},#{credentialRef},#{enabled},#{archived},#{version},#{createdAt},#{updatedAt})")
    void insertDatabase(DatabaseRow row);
    @Update("UPDATE database_connection SET name=#{name},config_json=#{configJson},credential_ref=#{credentialRef},enabled=#{enabled},archived=#{archived},version=version+1,updated_at=#{updatedAt} WHERE id=#{id} AND version=#{version}")
    int updateDatabase(DatabaseRow row);
    @Delete("DELETE FROM database_connection_project WHERE connection_id=#{id}")
    void clearDatabaseProjects(String id);
    @Insert("INSERT INTO database_connection_project VALUES(#{id},#{projectId})")
    void bindDatabase(String id, String projectId);
    record Policy(String scope, String serverId, String toolName, int enabled, long version, String updatedAt) { }
    @Select("SELECT * FROM assist_tool_policy WHERE server_id=#{server} AND (scope='' OR scope=#{scope}) ORDER BY scope,tool_name")
    List<Policy> policies(String scope, String server);
    @Insert("INSERT OR IGNORE INTO assist_tool_policy VALUES(#{scope},#{serverId},#{toolName},#{enabled},#{version},#{updatedAt})")
    int insertPolicy(Policy policy);
    @Update("UPDATE assist_tool_policy SET enabled=#{enabled},version=version+1,updated_at=#{updatedAt} WHERE scope=#{scope} AND server_id=#{serverId} AND tool_name=#{toolName} AND version=#{version}")
    int updatePolicy(Policy policy);
    @Select("SELECT count(*) FROM assist_catalog_registration WHERE server_id=#{server}")
    int catalogRegistered(String server);
    @Insert("INSERT OR IGNORE INTO assist_catalog_registration VALUES(#{server},#{time})")
    void registerCatalog(String server, String time);
    @Insert("INSERT INTO assist_policy_audit VALUES(#{id},#{scope},#{server},#{tool},#{action},#{time})")
    void audit(String id, String scope, String server, String tool, String action, String time);
    record Session(String externalSessionId, String generation, String directory, String profile,
                   String permissionsJson, String toolsJson, String createdAt) { }
    @Insert("INSERT INTO assist_session VALUES(#{externalSessionId},#{generation},#{directory},#{profile},#{permissionsJson},#{toolsJson},#{createdAt})")
    void insertSession(Session session);
    @Select("SELECT * FROM assist_session WHERE external_session_id=#{id}")
    Session session(String id);
    @Select("SELECT owner_json FROM assist_scope_binding WHERE external_session_id=#{id}")
    String scopeOwner(String id);
    @Insert("INSERT OR IGNORE INTO assist_scope_binding VALUES(#{id},#{owner})")
    void bindScopeOwner(String id,String owner);
    @Select("SELECT connections_json FROM assist_resource_binding WHERE owner_key=#{owner}")
    String resources(String owner);
    @Insert("INSERT OR IGNORE INTO assist_resource_binding VALUES(#{owner},#{json},#{time})")
    void bindResources(String owner, String json, String time);
    @Insert("INSERT INTO assist_call(id,owner_key,external_session_id,tool_name,state,created_at) VALUES(#{id},#{owner},#{session},#{tool},'RUNNING',#{time})")
    void startCall(String id, String owner, String session, String tool, String time);
    @Update("UPDATE assist_call SET state=#{state},result_json=#{result},completed_at=#{time} WHERE id=#{id} AND state='RUNNING'")
    int finishCall(String id, String state, String result, String time);
    @Select("SELECT id,tool_name,state,created_at,completed_at FROM assist_call WHERE owner_key=#{owner} AND created_at<=#{before} AND external_session_id NOT IN (SELECT external_session_id FROM assist_session WHERE profile LIKE '%JUDGE%' OR profile LIKE '%REVIEWER%') AND (created_at,id) > (#{time},#{id}) ORDER BY created_at,id LIMIT #{limit}")
    List<Map<String,Object>> calls(String owner, String time, String id, int limit,String before);
    @Select("SELECT result_json FROM assist_call WHERE id=#{id} AND owner_key=#{owner} AND state IN ('SUCCEEDED','FAILED')")
    String callResult(String owner, String id);
    @Select("SELECT result_json FROM assist_call WHERE id=#{id} AND owner_key=#{owner} AND completed_at<=#{before} AND state IN ('SUCCEEDED','FAILED') AND external_session_id NOT IN (SELECT external_session_id FROM assist_session WHERE profile LIKE '%JUDGE%' OR profile LIKE '%REVIEWER%')")
    String evidenceCall(String owner,String id,String before);
    record WordReceipt(String ownerKey, String idempotencyKey, String requestHash, String targetPath,
                       String sourceHash, String contentRef, String outputHash, String state, String createdAt) { }
    @Select("SELECT * FROM assist_word_receipt WHERE owner_key=#{owner} AND idempotency_key=#{key}")
    WordReceipt word(String owner, String key);
    @Select("SELECT * FROM assist_word_receipt WHERE owner_key=#{owner} AND target_path=#{target} AND state='WRITTEN' ORDER BY created_at DESC LIMIT 1")
    WordReceipt previousWord(String owner, String target);
    @Insert("INSERT INTO assist_word_receipt VALUES(#{ownerKey},#{idempotencyKey},#{requestHash},#{targetPath},#{sourceHash},#{contentRef},#{outputHash},#{state},#{createdAt})")
    void insertWord(WordReceipt receipt);
    @Update("UPDATE assist_word_receipt SET state='WRITTEN' WHERE owner_key=#{owner} AND idempotency_key=#{key}")
    void completeWord(String owner, String key);
    record Owner(String projectId,String taskId,String stageId,String attemptId,String designerId,String state) { }
    @Select("SELECT t.project_id,e.task_id,e.stage_id,e.attempt_id,NULL AS designer_id,e.state FROM execution_session e JOIN task t ON t.id=e.task_id JOIN attempt a ON a.id=e.attempt_id WHERE e.external_session_id=#{id} AND a.state='RUNNING' AND t.state='RUNNING' ORDER BY e.created_at DESC LIMIT 1")
    Owner executionOwner(String id);
    @Select("SELECT coalesce(r.project_id,t.project_id,d.project_id) AS project_id,r.task_id,NULL AS stage_id,NULL AS attempt_id,r.designer_session_id AS designer_id,r.state FROM ai_candidate_submission_run r LEFT JOIN task t ON t.id=r.task_id LEFT JOIN designer_session d ON d.id=r.designer_session_id WHERE r.external_session_id=#{id} AND r.state='OPEN' ORDER BY r.created_at DESC LIMIT 1")
    Owner candidateOwner(String id);
    @Select("SELECT project_id,NULL AS task_id,NULL AS stage_id,NULL AS attempt_id,id AS designer_id,state FROM designer_session WHERE external_session_id=#{id} AND state NOT IN ('CANCELLED','COMPLETED','FAILED') LIMIT 1")
    Owner designerOwner(String id);
    @Select("SELECT d.project_id,NULL AS task_id,NULL AS stage_id,NULL AS attempt_id,d.id AS designer_id,d.state FROM designer_session d WHERE d.state NOT IN ('COMPLETED','CANCELLED') AND (EXISTS(SELECT 1 FROM task_decomposition x WHERE x.designer_session_id=d.id AND x.external_session_id=#{id} AND x.state IN ('PENDING_HANDOFF','RUNNING','VALIDATING','NEEDS_INPUT')) OR EXISTS(SELECT 1 FROM loop_spec_compilation x WHERE x.designer_session_id=d.id AND x.external_session_id=#{id} AND x.state IN ('PENDING_HANDOFF','RUNNING')) OR EXISTS(SELECT 1 FROM analysis_report x WHERE x.designer_session_id=d.id AND x.external_session_id=#{id} AND x.state IN ('RUNNING','VALIDATING')) OR EXISTS(SELECT 1 FROM design_work_package x WHERE x.designer_session_id=d.id AND x.designer_external_session_id=#{id} AND x.state IN ('QUESTIONING','DESIGNING','REVIEWING','WAITING_INPUT'))) LIMIT 1")
    Owner designerRoleOwner(String id);
    @Insert("INSERT INTO task_artifact(id,task_id,attempt_id,kind,name,content_type,content,metadata_json,created_at) VALUES(#{id},#{task},#{attempt},'ASSIST_CALL',#{name},'application/json',#{body},'{}',#{time})")
    void callArtifact(String id,String task,String attempt,String name,String body,String time);
    @Select("SELECT id,kind,name,content_type,created_at FROM task_artifact WHERE task_id=#{task} AND judge_run_id IS NULL AND created_at<=#{before} AND (created_at,id) > (#{time},#{id}) ORDER BY created_at,id LIMIT #{limit}")
    List<Map<String,Object>> evidence(String task,String time,String id,int limit,String before);
    @Select("SELECT content FROM task_artifact WHERE task_id=#{task} AND id=#{id} AND judge_run_id IS NULL AND created_at<=#{before}")
    String evidenceBody(String task,String id,String before);
    @Select("SELECT v.id,v.type,v.state,v.summary,v.created_at,v.attempt_id FROM verification_result v JOIN attempt a ON a.id=v.attempt_id WHERE a.task_id=#{task} AND a.id=#{attempt} ORDER BY v.verifier_index LIMIT 101")
    List<Map<String,Object>> verificationFacts(String task,String attempt);
    @Select("SELECT v.evidence_json FROM verification_result v JOIN attempt a ON a.id=v.attempt_id WHERE a.task_id=#{task} AND v.id=#{id} AND v.created_at<=#{before}")
    String verificationBody(String task,String id,String before);
    @Select("SELECT id FROM attempt WHERE task_id=#{task} AND (#{stage} IS NULL OR stage_id=#{stage}) AND state!='RUNNING' ORDER BY created_at DESC,ordinal DESC LIMIT 1")
    String previousAttempt(String task,String stage);
    @Select("SELECT count(*) FROM attempt WHERE id=#{attempt} AND task_id=#{task} AND (#{stage} IS NULL OR stage_id=#{stage}) AND state!='RUNNING'")
    int ownsCompletedAttempt(String task,String stage,String attempt);
    @Select("SELECT id,original_filename,sha256 FROM task_design_attachment WHERE task_id=#{task} ORDER BY frozen_at,id LIMIT 101")
    List<Map<String,Object>> taskAttachments(String task);
    @Select("SELECT id,original_filename,sha256 FROM designer_attachment WHERE designer_session_id=#{designer} AND state='ACTIVE' ORDER BY created_at,id LIMIT 101")
    List<Map<String,Object>> designerAttachments(String designer);
    @Select("SELECT project_id FROM (SELECT id AS project_id FROM project WHERE root_path=#{directory} UNION SELECT project_id FROM task WHERE worktree_path=#{directory}) LIMIT 2")
    List<String> projectsAt(String directory);
    @Select("SELECT t.project_id,j.task_id,NULL AS stage_id,NULL AS attempt_id,NULL AS designer_id,j.state FROM judge_run j JOIN task t ON t.id=j.task_id WHERE j.external_session_id=#{id} AND j.state='RUNNING' LIMIT 1")
    Owner judgeOwner(String id);
}
