package io.opencode.loopper.persistence;

import io.opencode.loopper.persistence.PptGenerationRows.*;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

public interface PptGenerationMapper {
    @Select("SELECT * FROM ppt_generation WHERE id=#{id}") Optional<Generation> get(String id);
    @Select("SELECT * FROM ppt_generation WHERE document_id=#{document} ORDER BY created_at DESC,id DESC LIMIT 1") Optional<Generation> latest(String document);
    @Select("SELECT * FROM ppt_generation WHERE document_id=#{document} AND state NOT IN ('COMPLETED','STOPPED','FAILED') LIMIT 1") Optional<Generation> active(String document);
    @Select("SELECT * FROM ppt_generation WHERE state NOT IN ('COMPLETED','STOPPED','FAILED') ORDER BY updated_at,id LIMIT 100") List<Generation> activeRows();
    @Select("SELECT * FROM ppt_generation_request WHERE document_id=#{document} AND idempotency_key=#{key}") Optional<Request> request(String document,String key);
    @Select("SELECT MAX(CAST(json_extract(response_json,'$.revision') AS INTEGER)) FROM ppt_agent_receipt WHERE run_id=#{run} AND tool IN ('ppt_submit_plan','ppt_apply_operations')") Long savedRevision(String run);
    @Select("SELECT q.* FROM ppt_agent_question q JOIN ppt_agent_run r ON r.id=q.run_id WHERE r.document_id=#{document} AND r.idempotency_key GLOB #{prefix} AND q.state='ANSWERED' ORDER BY q.created_at,q.id LIMIT 100")
    List<PptAgentRows.Question> answers(String document,String prefix);
    @Insert("""
        INSERT INTO ppt_generation(id,document_id,idempotency_key,input_sha,prompt,mode,scope_json,source_revision,dispatch_revision,
          state,step,attempt,agent_key,run_id,job_id,preview_job_id,output_revision,detail,version,created_at,updated_at,requirements_confirmed)
        VALUES(#{id},#{documentId},#{idempotencyKey},#{inputSha},#{prompt},#{mode},#{scopeJson},#{sourceRevision},#{dispatchRevision},
          #{state},#{step},#{attempt},#{agentKey},#{runId},#{jobId},#{previewJobId},#{outputRevision},#{detail},#{version},#{createdAt},#{updatedAt},#{requirementsConfirmed})
        """) int insert(Generation row);
    @Insert("INSERT INTO ppt_generation_request(document_id,idempotency_key,input_sha,generation_id,kind,created_at) VALUES(#{documentId},#{idempotencyKey},#{inputSha},#{generationId},#{kind},#{createdAt})") int insertRequest(Request row);
    @Update("UPDATE ppt_generation SET state=#{state},detail=#{detail},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version}")
    int state(String id,long version,String state,String detail,String now);
    @Update("UPDATE ppt_generation SET detail=#{detail},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version}")
    int detail(String id,long version,String detail,String now);
    @Update("UPDATE ppt_generation SET run_id=#{run},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version} AND run_id IS NULL")
    int bindRun(String id,long version,String run,String now);
    @Update("UPDATE ppt_generation SET job_id=#{job},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version} AND job_id IS NULL")
    int bindJob(String id,long version,String job,String now);
    @Update("UPDATE ppt_generation SET output_revision=#{revision},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version} AND output_revision IS NULL")
    int freeze(String id,long version,long revision,String now);
    @Update("UPDATE ppt_generation SET attempt=attempt+1,version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version}")
    int resumeOutput(String id,long version,String now);
    @Update("""
        UPDATE ppt_generation SET step=#{step},attempt=#{attempt},dispatch_revision=#{revision},agent_key=#{key},
          run_id=NULL,job_id=NULL,preview_job_id=#{preview},output_revision=#{output},version=version+1,updated_at=#{now}
        WHERE id=#{id} AND version=#{version}
        """) int step(String id,long version,String step,int attempt,long revision,String key,String preview,Long output,String now);
}
