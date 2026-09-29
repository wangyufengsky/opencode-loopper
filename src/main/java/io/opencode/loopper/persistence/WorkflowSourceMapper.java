package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowSourceMapper {
    record Snapshot(String nodeRunId,String requirementId,String projectId,String rootPath,String sourcePath,
                    String purpose,String manifestJson,String manifestSha256,String createdAt,String readyAt) { }
    @Select("SELECT * FROM workflow_source_snapshot WHERE node_run_id=#{id}") Optional<Snapshot> find(String id);
    @Insert("""
        INSERT INTO workflow_source_snapshot(node_run_id,requirement_id,project_id,root_path,source_path,purpose,created_at)
        VALUES(#{nodeRunId},#{requirementId},#{projectId},#{rootPath},#{sourcePath},#{purpose},#{createdAt})
        """) int insert(Snapshot row);
    @Update("""
        UPDATE workflow_source_snapshot SET manifest_json=#{body},manifest_sha256=#{sha}
        WHERE node_run_id=#{id} AND manifest_json IS NULL
        """) int plan(String id,String body,String sha);
    @Update("UPDATE workflow_source_snapshot SET ready_at=#{now} WHERE node_run_id=#{id} AND manifest_json IS NOT NULL AND ready_at IS NULL")
    int ready(String id,String now);
    @Select("""
        SELECT a.id FROM workflow_node_attempt a JOIN workflow_source_snapshot s ON s.node_run_id=a.node_run_id
        WHERE a.adapter_key='system.source.snapshot.v1' AND a.state='RUNNING' AND a.id>#{after} ORDER BY a.id LIMIT #{limit}
        """) List<String> active(String after,int limit);
}
