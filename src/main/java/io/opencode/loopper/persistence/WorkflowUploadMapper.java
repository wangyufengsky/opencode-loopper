package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowUploadMapper {
    record Upload(String id, String requirementId, int planRevision, String requestKey, String requestSha256,
                  String manifestJson, String sha256, String summaryJson, String createdAt) { }
    record Summary(String id, String summaryJson, String createdAt, boolean ready) { }
    record File(String uploadId, String path, long sizeBytes, String sha256, String storagePath, String content) { }
    record Section(String path,String sha256,int characters) { }
    @Select("SELECT path,sha256,length(content) AS characters FROM workflow_upload_file WHERE upload_id=#{id} AND path=#{path} AND content IS NOT NULL")
    Optional<Section> section(String id,String path);
    @Select("""
        <script>SELECT path,sha256,length(content) AS characters FROM workflow_upload_file WHERE upload_id=#{id}
        AND content IS NOT NULL AND path IN <foreach collection="paths" item="path" open="(" close=")" separator=",">#{path}</foreach></script>
        """) List<Section> sections(String id,Collection<String> paths);
    @Insert("""
        INSERT INTO workflow_upload(id,requirement_id,plan_revision,request_key,request_sha256,manifest_json,sha256,summary_json,created_at)
        VALUES(#{id},#{requirementId},#{planRevision},#{requestKey},#{requestSha256},#{manifestJson},#{sha256},#{summaryJson},#{createdAt})
        """) int insert(Upload row);
    @Select("SELECT * FROM workflow_upload WHERE id=#{id}") Optional<Upload> find(String id);
    @Select("SELECT * FROM workflow_upload WHERE request_key=#{key}") Optional<Upload> request(String key);
    @Insert("""
        INSERT INTO workflow_upload_file(upload_id,path,size_bytes,sha256,storage_path,content)
        VALUES(#{uploadId},#{path},#{sizeBytes},#{sha256},#{storagePath},#{content})
        """) int insertFile(File row);
    @Select("SELECT * FROM workflow_upload_file WHERE upload_id=#{id} AND path=#{path}") Optional<File> file(String id,String path);
    @Select("SELECT EXISTS(SELECT 1 FROM workflow_upload_ready WHERE upload_id=#{id})") boolean ready(String id);
    @Insert("INSERT INTO workflow_upload_ready(upload_id,created_at) VALUES(#{id},#{now})") int markReady(String id,String now);
    @Select("""
        SELECT u.id,u.summary_json,u.created_at,EXISTS(SELECT 1 FROM workflow_upload_ready r WHERE r.upload_id=u.id) AS ready
        FROM workflow_upload u WHERE requirement_id=#{requirement}
        AND (#{time} IS NULL OR u.created_at<#{time} OR (u.created_at=#{time} AND u.id<#{after}))
        ORDER BY u.created_at DESC,u.id DESC LIMIT #{limit}
        """) List<Summary> page(String requirement,String time,String after,int limit);
}
