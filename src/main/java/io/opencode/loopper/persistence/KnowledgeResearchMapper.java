package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.*;

/** A follow-up has its own durable identity; the user turn remains RUNNING throughout. */
public interface KnowledgeResearchMapper {
    record Round(String turnId, int ordinal, String messageId, String state, String requestJson, String requestSha,
                 String thinkingPrefix, String createdAt, String updatedAt, long version) { }
    @Select("SELECT * FROM knowledge_research_round WHERE turn_id=#{turn} ORDER BY ordinal DESC LIMIT 1")
    Round latest(String turn);
    @Insert("""
        INSERT OR IGNORE INTO knowledge_research_round
        SELECT #{row.turnId},#{row.ordinal},#{row.messageId},'PREPARED',#{row.requestJson},#{row.requestSha},
               #{row.thinkingPrefix},#{row.createdAt},#{row.updatedAt},0
        WHERE EXISTS(SELECT 1 FROM knowledge_turn WHERE id=#{row.turnId} AND state='RUNNING' AND version=#{turnVersion})
          AND coalesce((SELECT max(ordinal) FROM knowledge_research_round WHERE turn_id=#{row.turnId}),0)=#{previous}
          AND NOT EXISTS(SELECT 1 FROM knowledge_research_round WHERE turn_id=#{row.turnId} AND state!='COMPLETED')
        """)
    int prepare(Round row, long turnVersion, int previous);
    @Update("""
        UPDATE knowledge_research_round SET state=#{next},version=version+1,updated_at=#{now}
        WHERE turn_id=#{row.turnId} AND ordinal=#{row.ordinal} AND version=#{row.version} AND state=#{row.state}
          AND EXISTS(SELECT 1 FROM knowledge_turn WHERE id=#{row.turnId} AND state='RUNNING')
        """)
    int state(Round row, String next, String now);
    @Insert("""
        INSERT INTO knowledge_call(id,conversation_id,turn_id,tool,state,detail,created_at,updated_at)
        SELECT #{row.id},#{row.conversationId},#{row.turnId},#{row.tool},#{row.state},#{row.detail},#{row.createdAt},#{row.updatedAt}
        WHERE EXISTS(SELECT 1 FROM knowledge_turn WHERE id=#{row.turnId} AND conversation_id=#{row.conversationId} AND state='RUNNING')
        ON CONFLICT(id) DO UPDATE SET state=excluded.state,detail=excluded.detail,updated_at=excluded.updated_at
        WHERE knowledge_call.turn_id=excluded.turn_id AND knowledge_call.state IN ('RUNNING','UNKNOWN')
          AND (knowledge_call.state<>excluded.state OR knowledge_call.detail<>excluded.detail)
        """)
    int nativeCall(@Param("row") KnowledgeRows.Call row);
}
