package io.opencode.loopper.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Select;

/** Scheduler admission only; terminal writer cleanup uses its independent ledger query. */
public interface MonitorReadMapper {
    @Select("SELECT * FROM task WHERE state IN ('STOPPING','JUDGING','RUNNING') ORDER BY created_at DESC,id")
    List<TaskRow> tasksForMonitoring();
}
