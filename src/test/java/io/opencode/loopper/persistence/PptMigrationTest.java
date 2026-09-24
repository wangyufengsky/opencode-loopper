package io.opencode.loopper.persistence;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PptMigrationTest {
    @TempDir Path root;
    @Test void upgradesRelease118WithoutChangingProjectsAndEnforcesPptOwnership()throws Exception{
        String url="jdbc:sqlite:"+root.resolve("upgrade.db")+"?foreign_keys=on";
        Flyway.configure().dataSource(url,null,null).target("118").load().migrate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()){
            sql.execute("INSERT INTO project(id,name,root_path,created_at,updated_at) VALUES('p','保留项目','/existing','now','now')");
        }
        var flyway=Flyway.configure().dataSource(url,null,null).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(21);flyway.validate();
        try(var db=DriverManager.getConnection(url);var sql=db.createStatement()){
            try(var rows=sql.executeQuery("SELECT name FROM project WHERE id='p'")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("保留项目");}
            sql.execute("INSERT INTO ppt_document(id,title,project_id,model,phase,revision,version,archived,create_digest,created_at,updated_at) VALUES('d','作品','p','fake/model','BRIEFING',0,0,0,'hash','now','now')");
            sql.execute("INSERT INTO ppt_revision VALUES('d',0,'{}','{}','创建','now')");
            assertThatThrownBy(()->sql.execute("INSERT INTO ppt_revision VALUES('other',0,'{}','{}','非法','now')")).isInstanceOf(java.sql.SQLException.class);
            try(var rows=sql.executeQuery("SELECT count(*) FROM task")){assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero();}
            try(var rows=sql.executeQuery("PRAGMA foreign_key_check")){assertThat(rows.next()).isFalse();}
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    @Test void freshDatabaseContainsBothPptLifecycles(){
        var flyway=Flyway.configure().dataSource("jdbc:sqlite:"+root.resolve("fresh.db"),null,null).load();flyway.migrate();flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("139");
    }
}
