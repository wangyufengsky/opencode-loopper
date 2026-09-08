package io.opencode.loopper.persistence;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AutomationPollHealthMapper {
    @Select("""
            SELECT h.* FROM automation_poll_health h JOIN automation_rule r ON r.id=h.rule_id
            WHERE r.version=h.rule_version
            """)
    List<AutomationPollHealthRow> currentHealth();

    @Select("""
            SELECT h.* FROM automation_poll_health h JOIN automation_rule r ON r.id=h.rule_id
            WHERE r.id=#{id} AND r.version=h.rule_version
            """)
    Optional<AutomationPollHealthRow> currentRuleHealth(String id);

    @Insert("""
            INSERT INTO automation_poll_health(rule_id,rule_version,status,last_checked_at,last_success_at,
              consecutive_failures,error_code,error_message)
            SELECT id,version,#{status},#{at},CASE WHEN #{status}='CHECKED' THEN #{at} ELSE NULL END,
              CASE WHEN #{status}='FAILED' THEN 1 ELSE 0 END,#{code},#{message}
            FROM automation_rule WHERE id=#{id} AND version=#{version}
            ON CONFLICT(rule_id) DO UPDATE SET rule_version=excluded.rule_version,status=excluded.status,
              last_checked_at=excluded.last_checked_at,
              last_success_at=CASE WHEN excluded.status='CHECKED' THEN excluded.last_success_at
                WHEN automation_poll_health.rule_version=excluded.rule_version THEN automation_poll_health.last_success_at ELSE NULL END,
              consecutive_failures=CASE WHEN excluded.status='CHECKED' THEN 0
                WHEN automation_poll_health.rule_version=excluded.rule_version THEN automation_poll_health.consecutive_failures+1 ELSE 1 END,
              error_code=excluded.error_code,error_message=excluded.error_message
            """)
    int record(@Param("id") String id, @Param("version") long version, @Param("status") String status,
               @Param("at") String at, @Param("code") String code, @Param("message") String message);
}
