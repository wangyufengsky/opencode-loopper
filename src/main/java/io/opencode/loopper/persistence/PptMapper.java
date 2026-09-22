package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;
import io.opencode.loopper.persistence.PptRows.*;

@Mapper
public interface PptMapper {
    @Select("SELECT * FROM ppt_document WHERE id=#{id}") Optional<Document> document(String id);
    @Insert("INSERT INTO ppt_document VALUES(#{id},#{title},#{projectId},#{model},#{phase},#{revision},#{version},#{archived},#{createDigest},#{createdAt},#{updatedAt})") int insert(Document row);
    @Select("""
        SELECT * FROM ppt_document WHERE (#{archive}='all' OR archived=CASE WHEN #{archive}='archived' THEN 1 ELSE 0 END)
        AND (#{query}='' OR instr(lower(title),lower(#{query}))>0) AND (#{phase}='' OR phase=#{phase})
        AND (updated_at < #{before} OR (updated_at=#{before} AND id < #{beforeId}))
        ORDER BY updated_at DESC,id DESC LIMIT #{limit}
        """) List<Document> list(String archive,String query,String phase,String before,String beforeId,int limit);
    @Update("UPDATE ppt_document SET title=#{title},revision=#{revision},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version}")
    int edit(String id,long version,long revision,String title,String now);
    @Update("UPDATE ppt_document SET phase=#{phase},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version}")
    int phase(String id,long version,String phase,String now);
    @Update("UPDATE ppt_document SET archived=#{archived},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version}")
    int archive(String id,long version,boolean archived,String now);
    @Select("SELECT * FROM ppt_revision WHERE document_id=#{id} AND revision=#{revision}") Optional<Revision> revision(String id,long revision);
    @Select("SELECT document_id,revision,'' AS deck_json,'' AS plan_json,reason,created_at FROM ppt_revision WHERE document_id=#{id} AND revision<#{before} ORDER BY revision DESC LIMIT #{limit}")
    List<Revision> revisions(String id,long before,int limit);
    @Insert("INSERT INTO ppt_revision VALUES(#{documentId},#{revision},#{deckJson},#{planJson},#{reason},#{createdAt})") int insertRevision(Revision row);
    @Select("SELECT * FROM ppt_edit_receipt WHERE document_id=#{id} AND request_key=#{key}") Optional<Receipt> receipt(String id,String key);
    @Insert("INSERT INTO ppt_edit_receipt VALUES(#{documentId},#{requestKey},#{digest},#{resultJson},#{createdAt})") int insertReceipt(Receipt row);
    @Select("SELECT * FROM ppt_resource WHERE document_id=#{documentId} AND id=#{id}") Optional<Resource> resource(String documentId,String id);
    @Select("SELECT * FROM ppt_resource WHERE document_id=#{id} AND request_key=#{key}") Optional<Resource> resourceReceipt(String id,String key);
    @Select("SELECT id,document_id,kind,name,media_type,storage_key,sha256,bytes,state,detail,CASE WHEN body_json IS NULL THEN NULL ELSE json_object('sectionCount',json_array_length(body_json,'$.sections'),'limitations',json_extract(body_json,'$.limitations')) END AS body_json,request_key,digest,created_at FROM ppt_resource WHERE document_id=#{id} ORDER BY created_at,id") List<Resource> resources(String id);
    @Insert("INSERT INTO ppt_resource VALUES(#{id},#{documentId},#{kind},#{name},#{mediaType},#{storageKey},#{sha256},#{bytes},#{state},#{detail},#{bodyJson},#{requestKey},#{digest},#{createdAt})") int insertResource(Resource row);
    @Update("UPDATE ppt_resource SET state=#{state},detail=#{detail},body_json=#{body} WHERE id=#{id} AND state<>'READY'") int resourceState(String id,String state,String detail,String body);
    @Select("SELECT * FROM ppt_resource WHERE state='PREPARING' AND created_at<#{before} ORDER BY created_at LIMIT 10") List<Resource> preparingResources(String before);
    @Select("SELECT * FROM ppt_job WHERE document_id=#{documentId} AND id=#{id}") Optional<Job> job(String documentId,String id);
    @Select("SELECT * FROM ppt_job WHERE document_id=#{id} AND request_key=#{key}") Optional<Job> jobReceipt(String id,String key);
    @Select("SELECT * FROM ppt_job WHERE document_id=#{id} ORDER BY created_at DESC,id DESC LIMIT 100") List<Job> jobs(String id);
    @Select("SELECT * FROM ppt_job WHERE state IN ('PREPARED','RUNNING') ORDER BY created_at LIMIT 100") List<Job> activeJobs();
    @Insert("INSERT INTO ppt_job VALUES(#{id},#{documentId},#{kind},#{revision},#{slideId},#{state},#{completed},#{total},#{detail},#{requestKey},#{digest},#{createdAt},#{updatedAt},#{version})") int insertJob(Job row);
    @Update("UPDATE ppt_job SET state=#{state},completed=#{completed},detail=#{detail},updated_at=#{now},version=version+1 WHERE id=#{id} AND version=#{version}")
    int jobState(String id,long version,String state,int completed,String detail,String now);
    @Update("UPDATE ppt_job SET completed=#{completed},updated_at=#{now},version=version+1 WHERE id=#{id} AND version=#{version} AND state='RUNNING'") int jobProgress(String id,long version,int completed,String now);
    @Insert("INSERT INTO ppt_artifact VALUES(#{id},#{documentId},#{jobId},#{revision},#{slideId},#{name},#{mediaType},#{storageKey},#{sha256},#{bytes},#{createdAt})") int insertArtifact(Artifact row);
    @Select("SELECT * FROM ppt_artifact WHERE document_id=#{documentId} AND id=#{id}") Optional<Artifact> artifact(String documentId,String id);
    @Select("SELECT * FROM ppt_artifact WHERE document_id=#{documentId} AND job_id=#{jobId} ORDER BY created_at,id") List<Artifact> artifacts(String documentId,String jobId);
    @Select("SELECT * FROM ppt_artifact WHERE document_id=#{documentId} AND job_id IN (SELECT id FROM ppt_job WHERE document_id=#{documentId} ORDER BY created_at DESC,id DESC LIMIT 100) ORDER BY created_at,id")
    List<Artifact> recentArtifacts(String documentId);
}
