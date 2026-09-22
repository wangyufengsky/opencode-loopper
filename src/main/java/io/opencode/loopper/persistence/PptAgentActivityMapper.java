package io.opencode.loopper.persistence;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** Optional display snapshots, guarded by the original run and exact prompt identity. */
public interface PptAgentActivityMapper {
    record Snapshot(String runId, String messageId, String thinkingPrefix, String thinking, String callsJson) { }
    @Select("SELECT * FROM ppt_agent_activity WHERE run_id=#{id}") Snapshot get(String id);
    @Select("""
        <script>SELECT * FROM ppt_agent_activity WHERE run_id IN
        <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>
        """) List<Snapshot> forRuns(List<String> ids);
    @Insert("""
        INSERT INTO ppt_agent_activity(run_id,message_id,thinking_prefix,thinking,calls_json)
        SELECT #{snapshot.runId},#{snapshot.messageId},#{snapshot.thinkingPrefix},#{snapshot.thinking},#{snapshot.callsJson}
        FROM ppt_agent_run WHERE id=#{snapshot.runId} AND version=#{version} AND message_id=#{snapshot.messageId}
          AND state IN ('RUNNING','STOPPING')
        ON CONFLICT(run_id) DO UPDATE SET message_id=excluded.message_id,
          thinking_prefix=excluded.thinking_prefix,thinking=excluded.thinking,calls_json=excluded.calls_json
        """) int save(Snapshot snapshot, long version);
}
