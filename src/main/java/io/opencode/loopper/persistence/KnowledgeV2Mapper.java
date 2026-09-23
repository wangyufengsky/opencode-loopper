package io.opencode.loopper.persistence;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** Conversation organization and interaction receipts are independent of execution state. */
public interface KnowledgeV2Mapper {
    record Options(String conversationId, int contractVersion, String timezone, String archivedAt, String lastActivityAt, long version, boolean awaitingAnswer) { }
    record Question(String id, String conversationId, String turnId, String remoteId, String promptJson,
                    String state, String answerKey, String answersJson, String createdAt, String updatedAt, long version) { }
    record Snapshot(String id, String owner, String querySha, String bodyJson, String createdAt) { }
    @Insert("INSERT INTO knowledge_conversation_options(conversation_id,contract_version,timezone,last_activity_at) VALUES(#{id},3,#{timezone},#{now})")
    int create(String id, String now, String timezone);
    @Select("SELECT o.*,EXISTS(SELECT 1 FROM knowledge_question q JOIN knowledge_turn t ON t.id=q.turn_id WHERE q.conversation_id=o.conversation_id AND q.state='PENDING' AND t.state='RUNNING') AS awaiting_answer FROM knowledge_conversation_options o WHERE conversation_id=#{id}")
    Options options(String id);
    @Select("<script>SELECT o.*,EXISTS(SELECT 1 FROM knowledge_question q JOIN knowledge_turn t ON t.id=q.turn_id WHERE q.conversation_id=o.conversation_id AND q.state='PENDING' AND t.state='RUNNING') AS awaiting_answer FROM knowledge_conversation_options o WHERE conversation_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Options> optionsFor(List<String> ids);
    @Update("UPDATE knowledge_conversation_options SET last_activity_at=#{now} WHERE conversation_id=#{id}")
    int activity(String id, String now);
    @Update("UPDATE knowledge_conversation_options SET archived_at=#{archived},version=version+1 WHERE conversation_id=#{id} AND version=#{version}")
    int archive(String id, long version, String archived);
    @Select("""
        <script>SELECT c.* FROM knowledge_conversation c JOIN knowledge_conversation_options o ON o.conversation_id=c.id
        WHERE (#{project}='' OR c.project_id=#{project})
          AND (#{archive}='all' OR (#{archive}='active' AND o.archived_at IS NULL) OR (#{archive}='archived' AND o.archived_at IS NOT NULL))
          AND (#{state}='' OR c.state=#{state} OR (#{state}='WAITING_INPUT' AND EXISTS
              (SELECT 1 FROM knowledge_question q JOIN knowledge_turn t ON t.id=q.turn_id WHERE q.conversation_id=c.id AND q.state='PENDING' AND t.state='RUNNING')))
          AND (#{query}='' OR instr(lower(c.title),lower(#{query}))>0 OR EXISTS
              (SELECT 1 FROM knowledge_turn t WHERE t.conversation_id=c.id AND (instr(lower(t.user_text),lower(#{query}))>0 OR instr(lower(t.answer),lower(#{query}))>0)))
          AND (#{since}='' OR o.last_activity_at &gt;= #{since}) AND (#{until}='' OR o.last_activity_at &lt; #{until})
          AND (o.last_activity_at,c.id) &lt; (#{time},#{id})
        ORDER BY o.last_activity_at DESC,c.id DESC LIMIT #{limit}</script>
        """)
    List<KnowledgeRows.Conversation> history(String project, String archive, String state, String query, String since, String until, String time, String id, int limit);
    @Insert("""
        INSERT OR IGNORE INTO knowledge_question(id,conversation_id,turn_id,remote_id,prompt_json,state,created_at,updated_at)
        SELECT #{id},#{conversationId},#{turnId},#{remoteId},#{promptJson},'PENDING',#{createdAt},#{updatedAt}
        WHERE EXISTS(SELECT 1 FROM knowledge_turn WHERE id=#{turnId} AND conversation_id=#{conversationId} AND state='RUNNING')
        """)
    int insertQuestion(Question question);
    @Select("SELECT * FROM knowledge_question WHERE conversation_id=#{conversation} AND id=#{id}")
    Question question(String conversation, String id);
    @Select("SELECT * FROM knowledge_question WHERE turn_id=#{turn} ORDER BY created_at,id")
    List<Question> questions(String turn);
    @Select("<script>SELECT * FROM knowledge_question WHERE conversation_id=#{conversation} AND turn_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY created_at,id</script>")
    List<Question> questionsFor(String conversation, List<String> ids);
    @Update("""
        UPDATE knowledge_question SET state='PREPARED',answer_key=#{key},answers_json=#{answers},updated_at=#{now},version=version+1
        WHERE id=#{id} AND version=#{version} AND state='PENDING'
        AND EXISTS(SELECT 1 FROM knowledge_turn t WHERE t.id=turn_id AND t.state='RUNNING')
        """)
    int prepareAnswer(String id, long version, String key, String answers, String now);
    @Update("""
        UPDATE knowledge_question SET state=#{next},updated_at=#{now},version=version+1 WHERE id=#{id} AND version=#{version} AND state=#{previous}
        AND EXISTS(SELECT 1 FROM knowledge_turn t WHERE t.id=turn_id AND t.state='RUNNING')
        """)
    int questionState(String id, long version, String previous, String next, String now);
    @Update("UPDATE knowledge_question SET state='CLOSED',version=version+1,updated_at=#{now} WHERE turn_id=#{turn} AND state NOT IN ('ANSWERED','CLOSED')")
    int closeQuestions(String turn, String now);
    @Insert("INSERT INTO knowledge_git_snapshot VALUES(#{id},#{owner},#{querySha},#{bodyJson},#{createdAt}) ON CONFLICT(id) DO NOTHING")
    int insertSnapshot(Snapshot snapshot);
    @Select("SELECT * FROM knowledge_git_snapshot WHERE id=#{id} AND owner=#{owner} AND query_sha=#{query}")
    Snapshot snapshot(String id, String owner, String query);
}
