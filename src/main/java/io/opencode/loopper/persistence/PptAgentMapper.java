package io.opencode.loopper.persistence;

import io.opencode.loopper.persistence.PptAgentRows.*;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

public interface PptAgentMapper {
    record Status(String id, String state, String detail, long version) { }
    @Select("SELECT id,state,detail,version FROM ppt_agent_run WHERE document_id=#{document} ORDER BY (state NOT IN ('COMPLETED','STOPPED','FAILED')) DESC,created_at DESC,id DESC LIMIT 1")
    Optional<Status> status(String document);
    @Select("SELECT count(*) FROM ppt_agent_run WHERE document_id=#{document} AND state NOT IN ('COMPLETED','STOPPED','FAILED')")
    int activeCount(String document);
    @Select("SELECT document_id FROM ppt_agent_run WHERE state NOT IN ('COMPLETED','STOPPED','FAILED') ORDER BY updated_at,id LIMIT 100")
    List<String> activeDocuments();
    @Select("SELECT * FROM ppt_agent_run WHERE id=#{id}") Optional<Run> run(String id);
    @Select("SELECT * FROM ppt_agent_run WHERE external_session_id=#{session}") Optional<Run> session(String session);
    @Select("SELECT * FROM ppt_agent_run WHERE document_id=#{document} AND idempotency_key=#{key}") Optional<Run> replay(String document, String key);
    @Select("SELECT * FROM ppt_agent_run WHERE document_id=#{document} AND state NOT IN ('COMPLETED','STOPPED','FAILED') LIMIT 1") Optional<Run> active(String document);
    @Select("SELECT * FROM ppt_agent_run WHERE document_id=#{document} ORDER BY created_at DESC,id DESC LIMIT 1") Optional<Run> latest(String document);
    @Select("SELECT * FROM ppt_agent_run WHERE state NOT IN ('COMPLETED','STOPPED','FAILED','WAITING_INPUT') ORDER BY updated_at,id LIMIT 100") List<Run> activeRuns();
    @Select("SELECT * FROM ppt_agent_run WHERE document_id=#{document} AND (created_at,id)<(#{time},#{id}) ORDER BY created_at DESC,id DESC LIMIT #{limit}")
    List<Run> history(String document, String time, String id, int limit);
    @Insert("""
        INSERT INTO ppt_agent_run(id,document_id,idempotency_key,input_sha,user_text,scope_json,source_revision,phase,
          model_json,root_path,context_json,state,detail,answer,message_id,created_at,updated_at)
        VALUES(#{id},#{documentId},#{idempotencyKey},#{inputSha},#{userText},#{scopeJson},#{sourceRevision},#{phase},
          #{modelJson},#{rootPath},#{contextJson},#{state},#{detail},#{answer},#{messageId},#{createdAt},#{updatedAt})
        """) int insert(Run row);
    @Update("UPDATE ppt_agent_run SET state=#{state},detail=#{detail},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version}")
    int state(String id, long version, String state, String detail, String now);
    @Update("UPDATE ppt_agent_run SET detail=#{detail},updated_at=#{now},version=version+1 WHERE id=#{id} AND version=#{version}")
    int detail(String id, long version, String detail, String now);
    @Update("UPDATE ppt_agent_run SET plan_json=#{plan},generation=#{generation},version=version+1 WHERE id=#{id} AND version=#{version} AND state='PREPARED' AND plan_json IS NULL")
    int plan(String id, long version, String plan, String generation);
    @Update("UPDATE ppt_agent_run SET create_dispatched=1,version=version+1 WHERE id=#{id} AND version=#{version} AND state='CREATING'")
    int createDispatched(String id, long version);
    @Update("UPDATE ppt_agent_run SET external_session_id=#{session},version=version+1 WHERE id=#{id} AND external_session_id IS NULL AND plan_json IS NOT NULL")
    int bind(String id, String session);
    @Update("UPDATE ppt_agent_run SET request_json=#{request},request_sha=#{sha},version=version+1 WHERE id=#{id} AND version=#{version} AND state IN ('PREPARED','CREATING') AND request_json IS NULL")
    int request(String id, long version, String request, String sha);
    @Insert("INSERT INTO ppt_agent_prompt VALUES(#{id},#{round},#{message},#{request},#{sha},#{now})")
    int prompt(String id, int round, String message, String request, String sha, String now);
    @Select("SELECT message_id FROM ppt_agent_prompt WHERE run_id=#{run} AND round<#{beforeRound} ORDER BY round DESC LIMIT 100")
    List<String> previousPromptMessageIds(String run, int beforeRound);
    @Update("UPDATE ppt_agent_run SET answer=#{answer},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version} AND state='RUNNING'")
    int answer(String id, long version, String answer, String now);
    @Update("UPDATE ppt_agent_run SET stop_reason=#{reason},version=version+1 WHERE id=#{id} AND version=#{version}")
    int stopReason(String id, long version, String reason);
    @Update("UPDATE ppt_agent_run SET stop_proof=#{proof},version=version+1 WHERE id=#{id} AND version=#{version}")
    int proof(String id, long version, String proof);
    @Update("""
        UPDATE ppt_agent_run SET round=round+1,message_id=#{message},request_json=NULL,request_sha=NULL,
          source_revision=#{revision},context_json=#{context},stop_proof=NULL,stop_reason=NULL,version=version+1
        WHERE id=#{id} AND version=#{version} AND state='PREPARED'
        """) int resume(String id, long version, String message, long revision, String context);
    @Update("UPDATE ppt_agent_run SET input_tokens=#{input},output_tokens=#{output} WHERE id=#{id}")
    int usage(String id, Long input, Long output);
    @Select("SELECT * FROM ppt_agent_question WHERE id=#{id} AND document_id=#{document}") Optional<Question> question(String document, String id);
    @Select("SELECT * FROM ppt_agent_question WHERE run_id=#{run} AND state='PENDING' LIMIT 1") Optional<Question> pending(String run);
    @Select("SELECT q.* FROM ppt_agent_question q JOIN ppt_agent_run r ON r.id=q.run_id WHERE r.document_id=#{document} AND json_extract(r.context_json,'$.pptDiscussionProtocol')='FREEFORM_DIALOGUE_V1' AND q.state='PENDING' LIMIT 1") Optional<Question> pendingDiscussion(String document);
    @Select("SELECT * FROM ppt_agent_question WHERE run_id=#{run} ORDER BY created_at,id LIMIT 100") List<Question> questions(String run);
    @Select("""
        <script>SELECT * FROM ppt_agent_question WHERE run_id IN
        <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
        ORDER BY created_at,id</script>
        """) List<Question> questionsFor(List<String> ids);
    @Insert("INSERT INTO ppt_agent_question(id,run_id,document_id,prompt,options_json,state,created_at,kind) VALUES(#{id},#{runId},#{documentId},#{prompt},#{optionsJson},'PENDING',#{createdAt},#{kind})")
    int insertQuestion(Question row);
    @Update("UPDATE ppt_agent_question SET state='ANSWERED',answer=#{answer},reply_key=#{key},reply_sha=#{sha},confirmed=#{confirmed},version=version+1 WHERE id=#{id} AND version=#{version} AND state='PENDING'")
    int reply(String id, long version, String answer, String key, String sha, Boolean confirmed);
    @Update("UPDATE ppt_agent_question SET state='CLOSED',version=version+1 WHERE run_id=#{run} AND state='PENDING'") int closeQuestions(String run);
    @Select("SELECT * FROM ppt_agent_receipt WHERE run_id=#{run} AND idempotency_key=#{key}") Optional<Receipt> receipt(String run, String key);
    @Insert("INSERT INTO ppt_agent_receipt VALUES(#{runId},#{idempotencyKey},#{tool},#{inputSha},#{responseJson},#{createdAt})") int insertReceipt(Receipt row);
}
