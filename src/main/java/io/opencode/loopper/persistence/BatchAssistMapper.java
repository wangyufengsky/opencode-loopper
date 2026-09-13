package io.opencode.loopper.persistence;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BatchAssistMapper {
    record TestFailure(String id,String snapshotId,String name,String className,String state,String detailJson) { }
    @Insert("INSERT INTO evidence_test_failure VALUES(#{id},#{snapshotId},#{name},#{className},#{state},#{detailJson})") int insertFailure(TestFailure row);
    @Select("SELECT f.id,f.snapshot_id,f.name,f.class_name,f.state,'' AS detail_json FROM evidence_test_failure f JOIN execution_evidence e ON e.id=f.snapshot_id WHERE e.owner_key=#{owner} AND e.created_at<#{before} AND (#{attempt}='' OR e.attempt_id=#{attempt}) AND f.id>#{cursor} ORDER BY f.id LIMIT 51") List<TestFailure> failures(String owner,String before,String attempt,String cursor);
    @Select("SELECT f.* FROM evidence_test_failure f JOIN execution_evidence e ON e.id=f.snapshot_id WHERE e.owner_key=#{owner} AND e.created_at<#{before} AND f.id=#{id}") TestFailure failure(String owner,String before,String id);
    record Config(String projectId, String configJson, long version, String updatedAt) { }
    record Evidence(String id, String ownerKey, String taskId, String stageId, String attemptId,
                    String executionId, String kind, String source, String contentPath, String sha256,
                    long byteSize, String status, String metadataJson, String createdAt) { }
    @Select("SELECT * FROM assist_project_config WHERE project_id=#{id}") Config config(String id);
    @Insert("INSERT OR IGNORE INTO assist_project_config VALUES(#{projectId},#{configJson},0,#{updatedAt})") int insertConfig(Config row);
    @Update("UPDATE assist_project_config SET config_json=#{configJson},version=version+1,updated_at=#{updatedAt} WHERE project_id=#{projectId} AND version=#{version}") int updateConfig(Config row);
    @Select("SELECT config_json FROM assist_batch_binding WHERE owner_key=#{owner}") String binding(String owner);
    @Insert("INSERT OR IGNORE INTO assist_batch_binding VALUES(#{owner},#{config},#{time})") int bind(String owner,String config,String time);
    @Insert("INSERT INTO execution_evidence VALUES(#{id},#{ownerKey},#{taskId},#{stageId},#{attemptId},#{executionId},#{kind},#{source},#{contentPath},#{sha256},#{byteSize},#{status},#{metadataJson},#{createdAt})") int insertEvidence(Evidence row);
    @Update("UPDATE execution_evidence SET status=#{status},metadata_json=#{metadata},created_at=#{time} WHERE id=#{id} AND status='PREPARED'") int finishEvidence(String id,String status,String metadata,String time);
    @Select("SELECT * FROM execution_evidence WHERE owner_key=#{owner} AND id=#{id} AND created_at<#{before}") Evidence evidence(String owner,String id,String before);
    @Select("SELECT * FROM execution_evidence WHERE owner_key=#{owner} AND created_at<#{before} AND (#{attempt}='' OR attempt_id=#{attempt}) AND (created_at>#{time} OR (created_at=#{time} AND id>#{id})) ORDER BY created_at,id LIMIT #{limit}")
    List<Evidence> page(String owner,String attempt,String before,String time,String id,int limit);
    @Select("SELECT coalesce(sum(byte_size),0) FROM execution_evidence WHERE owner_key=#{owner}") long size(String owner);
    @Select("SELECT coalesce(sum(byte_size),0) FROM execution_evidence WHERE attempt_id=#{attempt}") long attemptSize(String attempt);
    @Select("SELECT count(*) FROM execution_evidence WHERE attempt_id=#{attempt} AND kind IN ('JUNIT','LOG')") int files(String attempt);
    @Select("SELECT result_json FROM evidence_parse_cache WHERE sha256=#{sha} AND parser_version=#{version}") String parsed(String sha,String version);
    @Insert("INSERT OR IGNORE INTO evidence_parse_cache VALUES(#{sha},#{version},#{result})") int cache(String sha,String version,String result);
}
