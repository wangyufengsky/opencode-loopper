package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

public interface DesignerTimeoutMapper {
    record Policy(boolean enabled, long seconds) { }
    @Insert("INSERT INTO designer_timeout_policy(designer_id,enabled,seconds) VALUES(#{id},#{enabled},#{seconds})")
    int freezeDesignerTimeout(@Param("id") String id,@Param("enabled") boolean enabled,@Param("seconds") long seconds);
    @Select("SELECT enabled,seconds FROM designer_timeout_policy WHERE designer_id=#{id}")
    Optional<Policy> designerTimeout(String id);
    @Delete("DELETE FROM designer_session WHERE loop_draft_id=#{draftId}")
    int deleteDesignerSessionRowsByDraft(String draftId);
    @Delete("DELETE FROM designer_timeout_policy WHERE designer_id IN (SELECT id FROM designer_session WHERE loop_draft_id=#{draftId})")
    int deleteDesignerTimeoutsByDraft(String draftId);
    default int deleteDesignerSessionsByDraft(String draftId) {
        deleteDesignerTimeoutsByDraft(draftId);
        return deleteDesignerSessionRowsByDraft(draftId);
    }
}
