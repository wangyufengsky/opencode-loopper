package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

@Mapper
public interface StoryAccountingActivityMapper {
    @Select("""
            SELECT call.* FROM story_accounting_call call
            LEFT JOIN story_accounting_activity activity ON activity.call_id=call.id
            WHERE activity.dismissed_at IS NULL
            ORDER BY CASE WHEN call.state IN ('PREPARED','CANCELLING') THEN 0 ELSE 1 END,
              call.started_at,call.id LIMIT 100
            """)
    List<StoryAccountingCallRow> visibleCalls();

    @Select("SELECT parts_json FROM story_accounting_activity WHERE call_id=#{id}")
    Optional<String> parts(String id);

    @Insert("""
            INSERT INTO story_accounting_activity(call_id,parts_json)
            SELECT id,#{parts} FROM story_accounting_call WHERE id=#{id} AND state IN ('PREPARED','CANCELLING')
            ON CONFLICT(call_id) DO UPDATE SET parts_json=excluded.parts_json
            """)
    int saveParts(@Param("id") String id, @Param("parts") String parts);

    @Insert("""
            INSERT INTO story_accounting_activity(call_id,dismissed_at)
            SELECT id,#{now} FROM story_accounting_call WHERE id=#{id} AND state NOT IN ('PREPARED','CANCELLING')
            ON CONFLICT(call_id) DO UPDATE SET dismissed_at=excluded.dismissed_at
            """)
    int dismiss(@Param("id") String id, @Param("now") String now);
}
