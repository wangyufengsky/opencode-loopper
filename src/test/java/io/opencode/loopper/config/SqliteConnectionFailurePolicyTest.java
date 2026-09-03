package io.opencode.loopper.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.SQLExceptionOverride;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

class SqliteConnectionFailurePolicyTest {
    @TempDir Path directory;

    @Test
    void failedImmediateBeginCannotPoisonTheNextBorrower() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + directory.resolve("lock.db")
                + "?journal_mode=WAL&busy_timeout=50&transaction_mode=IMMEDIATE");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(2);
        var properties = new YamlPropertySourceLoader().load("application", new FileSystemResource("src/main/resources/application.yml"));
        config.setExceptionOverrideClassName((String) properties.getFirst()
                .getProperty("spring.datasource.hikari.exception-override-class-name"));
        assertThat(config.getExceptionOverrideClassName()).isEqualTo(SqliteConnectionFailurePolicy.class.getName());
        try (var pool = new HikariDataSource(config); var writer = pool.getConnection()) {
            writer.setAutoCommit(false);
            try (var blocked = pool.getConnection()) {
                assertThatThrownBy(() -> blocked.setAutoCommit(false))
                        .isInstanceOf(SQLException.class).hasMessageContaining("SQLITE_BUSY");
            }
            writer.rollback();
            writer.setAutoCommit(true);
            try (var next = pool.getConnection()) {
                assertThat(next.getAutoCommit()).as("a failed BEGIN must not leak transaction state").isTrue();
                try (var statement = next.createStatement(); var result = statement.executeQuery("SELECT 1")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }
                next.setAutoCommit(false);
                next.commit();
                next.setAutoCommit(true);
            }
        }
    }

    @Test
    void ordinaryConstraintAndNonSqliteFailuresKeepTheDefaultPoolPolicy() {
        var policy = new SqliteConnectionFailurePolicy();
        assertThat(policy.adjudicate(new SQLiteException("constraint failed", SQLiteErrorCode.SQLITE_CONSTRAINT)))
                .isEqualTo(SQLExceptionOverride.Override.CONTINUE_EVICT);
        assertThat(policy.adjudicate(new SQLException("different driver", null, 5)))
                .isEqualTo(SQLExceptionOverride.Override.CONTINUE_EVICT);
        assertThat(policy.adjudicate(new SQLiteException("no such table", SQLiteErrorCode.SQLITE_ERROR)))
                .isEqualTo(SQLExceptionOverride.Override.CONTINUE_EVICT);
    }

    @Test
    void extendedLockCodesAndAlreadyBrokenTransactionsCannotBeReused() {
        var policy = new SqliteConnectionFailurePolicy();
        assertThat(policy.adjudicate(new SQLiteException("busy snapshot", SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT)))
                .isEqualTo(SQLExceptionOverride.Override.MUST_EVICT);
        assertThat(policy.adjudicate(new SQLiteException("table locked", SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE)))
                .isEqualTo(SQLExceptionOverride.Override.MUST_EVICT);
        assertThat(policy.adjudicate(new SQLiteException("cannot commit - no transaction is active", SQLiteErrorCode.SQLITE_ERROR)))
                .isEqualTo(SQLExceptionOverride.Override.MUST_EVICT);
    }
}
