package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowDocumentMapper {
    record Document(String attemptId,String manifestJson,String sha256,String createdAt) { }
    record File(String attemptId,String path,long sizeBytes,String sha256,String content) { }
    @Insert("INSERT INTO workflow_document(attempt_id,manifest_json,sha256,created_at) VALUES(#{attemptId},#{manifestJson},#{sha256},#{createdAt})")
    int insert(Document row);
    @Select("SELECT * FROM workflow_document WHERE attempt_id=#{id}") Optional<Document> find(String id);
    @Insert("INSERT INTO workflow_document_file(attempt_id,path,size_bytes,sha256,content) VALUES(#{attemptId},#{path},#{sizeBytes},#{sha256},#{content})")
    int insertFile(File row);
    @Select("SELECT * FROM workflow_document_file WHERE attempt_id=#{id} AND path=#{path}") Optional<File> file(String id,String path);
    @Select("SELECT count(*) FROM workflow_document_file WHERE attempt_id=#{id}") int count(String id);
    @Select("SELECT * FROM workflow_document_file WHERE attempt_id=#{id} ORDER BY path") List<File> files(String id);
    @Select("SELECT id FROM workflow_node_attempt WHERE adapter_key IN ('system.source.design-document.v1','system.document.review-report.v1','system.history.report.v1','system.snapshot.report.v1') AND state='RUNNING' AND id>#{after} ORDER BY id LIMIT #{limit}")
    List<String> active(String after,int limit);
}
