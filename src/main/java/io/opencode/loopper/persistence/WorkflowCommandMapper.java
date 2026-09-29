package io.opencode.loopper.persistence;

import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkflowCommandMapper {
    @Select("SELECT * FROM workflow_command WHERE request_key=#{key}")
    Optional<WorkflowRows.Command> find(String key);
    @Insert("""
        INSERT INTO workflow_command(request_key,request_sha256,entity_type,entity_id,action,receipt_json,created_at)
        VALUES(#{requestKey},#{requestSha256},#{entityType},#{entityId},#{action},#{receiptJson},#{createdAt})
        """)
    int insert(WorkflowRows.Command row);
}
