package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowWriterMigrationTest {
    @TempDir Path directory;
    @Test void upgradeRetainsLegacyHeldReleasedAndPendingLeasesAndEveryQueuedIdentity() throws Exception {
        String url="jdbc:sqlite:"+directory.resolve("writers-upgrade.db")+"?foreign_keys=on";
        Flyway.configure().dataSource(url,null,null).target("144").load().migrate();
        Map<String,Object> before;
        try (var db=DriverManager.getConnection(url); var sql=db.createStatement()) {
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','历史项目','/tmp/history','t','t')");
            for (String state:List.of("HELD","RELEASE_PENDING","RELEASED")) {
                sql.execute("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('"+state+"','p','历史任务','READY','t','t')");
                sql.execute("INSERT INTO workspace_lease(canonical_root,root_fingerprint,mode,holder_task_id,state,acquired_at,heartbeat_at,released_at,release_reason,version) "
                        +"VALUES('/tmp/"+state+"','original-fingerprint','DIRECT',"+(state.equals("RELEASED")?"NULL":"'"+state+"'")+",'"+state+"','acquired','heartbeat','released','原始说明',7)");
                sql.execute("INSERT INTO task_queue(task_id,canonical_root,root_fingerprint,position,source,state,enqueued_at,admitted_at,finished_at,version) "
                        +"VALUES('"+state+"','/tmp/"+state+"','original-fingerprint',3,'MANUAL','"+(state.equals("RELEASED")?"FINISHED":"ADMITTED")+"','enqueued','admitted','finished',4)");
            }
            sql.execute("INSERT INTO task(id,project_id,title,state,created_at,updated_at) VALUES('waiter','p','排队任务','QUEUED','t','t')");
            sql.execute("INSERT INTO task_queue VALUES('waiter','/tmp/HELD','original-fingerprint',4,'RECOVERY','QUEUED','later',NULL,NULL,2)");
            before=history(sql);
        }
        var flyway=Flyway.configure().dataSource(url,null,null).load(); flyway.migrate(); flyway.validate();
        try (var db=DriverManager.getConnection(url); var sql=db.createStatement()) {
            assertThat(history(sql)).isEqualTo(before);
            try (var rows=sql.executeQuery("SELECT count(*) FROM workspace_lease WHERE holder_workflow_attempt_id IS NOT NULL OR holder_writeback_id IS NOT NULL")) { assertThat(rows.getInt(1)).isZero(); }
            try (var rows=sql.executeQuery("SELECT count(*) FROM workflow_writer_queue")) { assertThat(rows.getInt(1)).isZero(); }
            try (var rows=sql.executeQuery("PRAGMA foreign_key_check")) { assertThat(rows.next()).isFalse(); }
            try (var rows=sql.executeQuery("PRAGMA integrity_check")) { assertThat(rows.getString(1)).isEqualTo("ok"); }
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    private Map<String,Object> history(Statement sql) throws Exception {
        var values=new LinkedHashMap<String,Object>();
        for (String table:List.of("task","task_queue","workspace_lease")) {
            try (var rows=sql.executeQuery("SELECT * FROM "+table+" ORDER BY 1")) {
                while (rows.next()) for (int column=1;column<=rows.getMetaData().getColumnCount();column++) {
                    String name=rows.getMetaData().getColumnName(column);
                    if (!Set.of("holder_workflow_attempt_id","holder_writeback_id").contains(name)) values.put(table+":"+rows.getString(1)+":"+name,rows.getObject(column));
                }
            }
        }
        return values;
    }
}
