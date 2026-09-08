package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

public interface DesignerConversationMapper {
    @Select("SELECT snapshot_markdown FROM design_discussion_revision WHERE designer_session_id=#{id} AND scope_key='REQUIREMENT' AND revision<#{revision} AND trim(coalesce(snapshot_markdown,''))!='' ORDER BY revision DESC LIMIT 1")
    Optional<String> previousRequirementSnapshot(@Param("id") String id, @Param("revision") int revision);
    @Select("SELECT COUNT(DISTINCT external_session_id) FROM ai_candidate_submission_run WHERE owner_type='DESIGN_WORK_PACKAGE' AND owner_id=#{id}")
    int designerPackageCandidateSessions(String id);
    @Select("SELECT COUNT(*) FROM ai_candidate_submission_attempt a JOIN ai_candidate_submission_run r ON r.id=a.run_id WHERE r.owner_type='DESIGN_WORK_PACKAGE' AND r.owner_id=#{id}")
    int designerPackageCandidateSubmissions(String id);
    @Select("SELECT package_id FROM design_work_package WHERE id=#{id}")
    Optional<String> designerConversationPackageName(String id);
    @Insert("INSERT INTO designer_conversation_policy VALUES(#{designerId},'PER_PACKAGE_V1')")
    int enableDesignerConversations(String designerId);
    @Select("SELECT EXISTS(SELECT 1 FROM designer_conversation_policy WHERE designer_session_id=#{id})")
    boolean designerConversationsEnabled(String id);
    @Select("SELECT * FROM designer_conversation WHERE designer_session_id=#{designerId} AND scope_key=#{scope} ORDER BY generation DESC LIMIT 1")
    Optional<DesignerConversationRow> latestDesignerConversation(@Param("designerId") String designerId, @Param("scope") String scope);
    @Select("SELECT * FROM designer_conversation WHERE external_session_id=#{remoteId}")
    Optional<DesignerConversationRow> designerConversationForRemote(String remoteId);
    @Select("SELECT * FROM designer_conversation WHERE designer_session_id=#{designerId} ORDER BY created_at,id")
    List<DesignerConversationRow> designerConversations(String designerId);
    @Insert("""
        INSERT INTO designer_conversation(id,designer_session_id,scope_key,generation,root_path,profile,model_json,state,created_at,updated_at)
        VALUES(#{id},#{designerSessionId},#{scopeKey},#{generation},#{rootPath},#{profile},#{modelJson},'CREATING',#{createdAt},#{updatedAt})
        """)
    int insertDesignerConversation(DesignerConversationRow row);
    @Update("""
        UPDATE designer_conversation SET external_session_id=#{remoteId},runtime_generation_id=#{runtime},
          internal_mcp_server=#{server},state='OPEN',version=version+1 WHERE id=#{id} AND state='CREATING' AND version=0
        """)
    int bindDesignerConversation(@Param("id") String id, @Param("remoteId") String remoteId,
        @Param("runtime") String runtime, @Param("server") String server);
    @Update("UPDATE designer_conversation SET state='RETIRED',reason=#{reason},updated_at=#{now},version=version+1 WHERE id=#{id} AND state!='RETIRED'")
    int retireDesignerConversation(@Param("id") String id, @Param("reason") String reason, @Param("now") String now);
    @Update("UPDATE designer_conversation SET scope_key=#{scope},version=version+1 WHERE id=#{id} AND scope_key='REQUIREMENT' AND state='OPEN'")
    int adoptDesignerConversation(@Param("id") String id, @Param("scope") String scope);
    @Select("SELECT * FROM designer_conversation_turn WHERE conversation_id=#{id} ORDER BY created_at DESC,id DESC LIMIT 1")
    Optional<DesignerConversationTurnRow> latestDesignerTurn(String id);
    @Select("SELECT t.* FROM designer_conversation_turn t JOIN designer_conversation c ON c.id=t.conversation_id WHERE c.external_session_id=#{id} ORDER BY t.created_at DESC,t.id DESC LIMIT 1")
    Optional<DesignerConversationTurnRow> designerTurnForRemote(String id);
    @Insert("""
        INSERT INTO designer_conversation_turn(id,conversation_id,message_id,phase,candidate_run_id,state,created_at,updated_at)
        VALUES(#{id},#{conversationId},#{messageId},#{phase},#{candidateRunId},'PREPARED',#{createdAt},#{updatedAt})
        """)
    int insertDesignerTurn(DesignerConversationTurnRow row);
    @Update("""
        UPDATE designer_conversation_turn SET state='SENDING',request_json=#{request},request_sha256=#{sha},version=version+1
        WHERE id=#{id} AND state='PREPARED' AND version=0
        """)
    int claimDesignerTurn(@Param("id") String id, @Param("request") String request, @Param("sha") String sha);
    @Update("UPDATE designer_conversation_turn SET state=#{state},updated_at=#{now},version=version+1 WHERE id=#{id} AND (#{state}='SETTLED' AND state!='SETTLED' OR #{state}='SENT' AND state IN ('SENDING','UNKNOWN') OR #{state}='UNKNOWN' AND state='SENDING')")
    int finishDesignerTurn(@Param("id") String id, @Param("state") String state, @Param("now") String now);
}
