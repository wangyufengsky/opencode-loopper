package io.opencode.loopper.persistence;

import io.opencode.loopper.persistence.KnowledgeRows.*;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

public interface KnowledgeMapper {
    @Select("SELECT * FROM knowledge_conversation WHERE id=#{id}")
    Optional<Conversation> conversation(String id);
    @Select("SELECT * FROM knowledge_conversation WHERE remote_id=#{remote}")
    Optional<Conversation> remote(String remote);
    @Select("SELECT * FROM knowledge_conversation WHERE project_id=#{project} AND (created_at,id)>(#{time},#{id}) ORDER BY created_at,id LIMIT #{limit}")
    List<Conversation> conversations(String project, String time, String id, int limit);
    @Insert("""
        INSERT INTO knowledge_conversation(id,project_id,root_path,title,model_json,sources_json,connections_json,created_at,updated_at)
        VALUES(#{id},#{projectId},#{rootPath},#{title},#{modelJson},#{sourcesJson},#{connectionsJson},#{createdAt},#{updatedAt})
        """)
    int insertConversation(Conversation row);
    @Update("UPDATE knowledge_conversation SET state=#{state},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version}")
    int conversationState(String id, long version, String state, String now);
    @Update("UPDATE knowledge_conversation SET plan_json=#{plan},version=version+1 WHERE id=#{id} AND plan_json IS NULL AND remote_id IS NULL")
    int plan(String id, String plan);
    @Update("UPDATE knowledge_conversation SET remote_id=#{remote},version=version+1 WHERE id=#{id} AND remote_id IS NULL AND plan_json IS NOT NULL")
    int bind(String id, String remote);
    @Update("UPDATE knowledge_conversation SET plan_json=NULL,version=version+1 WHERE id=#{id} AND remote_id IS NULL AND plan_json=#{plan}")
    int clearUnsentPlan(String id, String plan);
    @Select("SELECT * FROM knowledge_turn WHERE conversation_id=#{conversation} AND state NOT IN ('COMPLETED','STOPPED','FAILED') LIMIT 1")
    Optional<Turn> active(String conversation);
    @Select("SELECT * FROM knowledge_turn WHERE conversation_id=#{conversation} AND idempotency_key=#{key}")
    Optional<Turn> replay(String conversation, String key);
    @Select("SELECT * FROM knowledge_turn WHERE id=#{id}")
    Optional<Turn> turn(String id);
    @Select("SELECT coalesce(max(ordinal),0)+1 FROM knowledge_turn WHERE conversation_id=#{id}")
    int nextOrdinal(String id);
    @Select("SELECT * FROM knowledge_turn WHERE conversation_id=#{conversation} AND (created_at,id)<(#{time},#{id}) ORDER BY created_at DESC,id DESC LIMIT #{limit}")
    List<Turn> turns(String conversation, String time, String id, int limit);
    record Usage(Long inputTokens, Long outputTokens) { }
    @Select("SELECT input_tokens,output_tokens FROM knowledge_turn WHERE conversation_id=#{id} AND (input_tokens IS NOT NULL OR output_tokens IS NOT NULL) ORDER BY ordinal DESC LIMIT 1")
    Optional<Usage> latestUsage(String id);
    @Select("SELECT * FROM knowledge_turn WHERE state NOT IN ('COMPLETED','STOPPED','FAILED') ORDER BY updated_at,id LIMIT 100")
    List<Turn> activeTurns();
    @Insert("""
        INSERT INTO knowledge_turn(id,conversation_id,ordinal,idempotency_key,message_id,state,user_text,created_at,updated_at)
        VALUES(#{id},#{conversationId},#{ordinal},#{idempotencyKey},#{messageId},'PREPARED',#{userText},#{createdAt},#{updatedAt})
        """)
    int insertTurn(Turn row);
    @Update("UPDATE knowledge_turn SET state=#{state},detail=#{detail},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version} AND state NOT IN ('COMPLETED','STOPPED','FAILED')")
    int turnState(String id, long version, String state, String detail, String now);
    @Update("UPDATE knowledge_turn SET detail=#{detail},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version}")
    int detail(String id, long version, String detail, String now);
    @Update("UPDATE knowledge_turn SET request_json=#{request},request_sha=#{sha},state='SENDING',version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version} AND state IN ('PREPARED','CREATING')")
    int dispatch(String id, long version, String request, String sha, String now);
    @Update("UPDATE knowledge_turn SET answer=#{answer},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version} AND state='RUNNING'")
    int answer(String id, long version, String answer, String now);
    @Update("UPDATE knowledge_turn SET answer=#{answer},thinking=#{thinking},version=version+1,updated_at=#{now} WHERE id=#{id} AND version=#{version} AND state='RUNNING'")
    int output(String id, long version, String answer, String thinking, String now);
    @Update("UPDATE knowledge_turn SET input_tokens=#{input},output_tokens=#{output} WHERE id=#{id}")
    int usage(String id, Long input, Long output);
    @Select("SELECT * FROM knowledge_source WHERE project_id=#{project} AND state!='REMOVED' AND (created_at,id)>(#{time},#{id}) ORDER BY created_at,id LIMIT #{limit}")
    List<Source> sources(String project, String time, String id, int limit);
    @Select("SELECT * FROM knowledge_source WHERE id=#{id} AND project_id=#{project}")
    Optional<Source> source(String project, String id);
    @Select("SELECT * FROM knowledge_source WHERE project_id=#{project} AND kind='UPLOAD' AND sha256=#{sha} AND name=#{name} AND state!='REMOVED' ORDER BY created_at,id LIMIT 1")
    Optional<Source> duplicateUpload(String project, String sha, String name);
    @Select("SELECT * FROM knowledge_source WHERE project_id=#{project} AND kind='DIRECTORY' AND path=#{path} AND state!='REMOVED' ORDER BY created_at,id LIMIT 1")
    Optional<Source> duplicateDirectory(String project, String path);
    @Select("SELECT * FROM knowledge_source WHERE state='PREPARED' ORDER BY created_at,id LIMIT 100")
    List<Source> preparedSources();
    @Insert("""
        INSERT INTO knowledge_source(id,project_id,kind,name,path,sha256,state,detail,created_at,updated_at)
        VALUES(#{id},#{projectId},#{kind},#{name},#{path},#{sha256},#{state},#{detail},#{createdAt},#{updatedAt})
        """)
    int insertSource(Source row);
    @Update("UPDATE knowledge_source SET state=#{state},detail=#{detail},updated_at=#{now},version=version+1 WHERE id=#{id} AND version=#{version} AND state!='REMOVED'")
    int sourceState(String id, long version, String state, String detail, String now);
    @Insert("""
        INSERT INTO knowledge_citation SELECT #{id},#{conversationId},#{turnId},#{kind},#{sourceId},#{name},#{location},#{sha256},#{bodyJson},#{createdAt}
        WHERE (SELECT count(*) FROM knowledge_citation WHERE turn_id=#{turnId}) < 100 AND EXISTS(SELECT 1 FROM knowledge_turn t JOIN knowledge_conversation c ON c.id=t.conversation_id
          WHERE t.id=#{turnId} AND c.id=#{conversationId} AND c.state='RUNNING' AND t.state IN ('SENDING','UNKNOWN','RUNNING'))
        """)
    int cite(Citation row);
    @Select("SELECT sha256 FROM knowledge_citation WHERE turn_id=#{turn} AND source_id=#{source} AND json_extract(body_json,'$.path')=#{path} ORDER BY created_at DESC,id DESC LIMIT 1")
    String previousFileSha(String turn, String source, String path);
    @Update("UPDATE knowledge_call SET state='UNKNOWN',detail='服务重启前的读取结果待核对' WHERE state='RUNNING'")
    int interruptedCalls();
    @Select("SELECT count(*) FROM knowledge_citation WHERE turn_id=#{turn}")
    int citationCount(String turn);
    @Select("<script>SELECT id FROM knowledge_citation WHERE conversation_id=#{conversation} AND id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<String> knownCitations(String conversation, List<String> ids);
    @Select("<script>SELECT id,conversation_id,turn_id,kind,source_id,name,location,sha256,NULL AS body_json,created_at FROM knowledge_citation WHERE conversation_id=#{conversation} AND turn_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY created_at,id</script>")
    List<Citation> citationsForTurns(String conversation, List<String> ids);
    @Select("<script>SELECT id,conversation_id,turn_id,tool,state,detail,created_at,updated_at FROM (SELECT *,row_number() OVER(PARTITION BY turn_id ORDER BY created_at DESC,id DESC) AS rn FROM knowledge_call WHERE conversation_id=#{conversation} AND turn_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>) WHERE rn &lt;=30 ORDER BY created_at,id</script>")
    List<Call> callsForTurns(String conversation, List<String> ids);
    @Select("SELECT * FROM knowledge_citation WHERE conversation_id=#{conversation} AND id=#{id}")
    Optional<Citation> citation(String conversation, String id);
    @Select("SELECT id,conversation_id,turn_id,kind,source_id,name,location,sha256,NULL AS body_json,created_at FROM knowledge_citation WHERE conversation_id=#{conversation} AND turn_id=#{turn} ORDER BY created_at,id LIMIT 100")
    List<Citation> citations(String conversation, String turn);
    @Insert("INSERT INTO knowledge_call VALUES(#{id},#{conversationId},#{turnId},#{tool},'RUNNING','',#{createdAt},#{updatedAt})")
    int startCall(Call row);
    @Update("UPDATE knowledge_call SET state=#{state},detail=#{detail},updated_at=#{now} WHERE id=#{id} AND state='RUNNING'")
    int finishCall(String id, String state, String detail, String now);
    @Select("SELECT * FROM knowledge_call WHERE conversation_id=#{conversation} AND turn_id=#{turn} ORDER BY created_at DESC,id DESC LIMIT 30")
    List<Call> calls(String conversation, String turn);
}
