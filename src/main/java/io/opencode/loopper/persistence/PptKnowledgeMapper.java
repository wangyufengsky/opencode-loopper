package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** PPT-owned authorization and immutable source reads; no knowledge-conversation ownership. */
public interface PptKnowledgeMapper {
    record Scope(String documentId,String projectId,String projectName,String selectionJson,String sourcesJson,String createdAt) { }
    record Evidence(String id,String documentId,String runId,String messageId,String sourceId,String bodyJson,String digest,String createdAt) { }
    record ProjectChoice(String id,String name,String description,String createdAt) { }
    @Insert("INSERT INTO ppt_knowledge_scope VALUES(#{documentId},#{projectId},#{projectName},#{selectionJson},#{sourcesJson},#{createdAt})")
    int insertScope(Scope scope);
    @Select("SELECT * FROM ppt_knowledge_scope WHERE document_id=#{document}") Optional<Scope> scope(String document);
    @Insert("INSERT OR IGNORE INTO ppt_knowledge_evidence VALUES(#{id},#{documentId},#{runId},#{messageId},#{sourceId},#{bodyJson},#{digest},#{createdAt})")
    int insertEvidence(Evidence evidence);
    @Select("SELECT * FROM ppt_knowledge_evidence WHERE document_id=#{document} AND id=#{id}") Optional<Evidence> evidence(String document,String id);
    @Select("SELECT * FROM ppt_knowledge_evidence WHERE run_id=#{run} AND message_id=#{message} AND digest=#{digest}")
    Optional<Evidence> replay(String run,String message,String digest);
    @Select("SELECT count(*) FROM ppt_knowledge_evidence WHERE run_id=#{run}") int evidenceCount(String run);
    @Select("SELECT id FROM ppt_knowledge_evidence WHERE document_id=#{document}") List<String> evidenceIds(String document);
    @Select("""
        SELECT id,name,description,created_at FROM project WHERE managed=1 AND (#{query}='' OR instr(lower(name),lower(#{query}))>0)
        AND (#{createdAt}='' OR (created_at,id)<(#{createdAt},#{id})) ORDER BY created_at DESC,id DESC LIMIT #{limit}
        """)
    List<ProjectChoice> projects(String query,String createdAt,String id,int limit);
}
