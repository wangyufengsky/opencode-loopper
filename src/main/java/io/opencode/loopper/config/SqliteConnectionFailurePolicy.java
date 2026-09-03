package io.opencode.loopper.config;

import com.zaxxer.hikari.SQLExceptionOverride;
import java.sql.SQLException;
import org.sqlite.SQLiteException;

/** A failed SQLite BEGIN can change JDBC auto-commit before it acquires the database lock. */
public final class SqliteConnectionFailurePolicy implements SQLExceptionOverride {
    @java.lang.Override
    public Override adjudicate(SQLException failure) {
        if (!(failure instanceof SQLiteException)) return Override.CONTINUE_EVICT;
        int primaryCode = failure.getErrorCode() & 0xff;
        // SQLite JDBC also starts a new transaction after commit/rollback. A lock failure
        // at any of these boundaries leaves its connection state uncertain. Never recycle it.
        if (primaryCode == 5 || primaryCode == 6
                || primaryCode == 1 && failure.getMessage() != null
                && failure.getMessage().contains("no transaction is active")) {
            return Override.MUST_EVICT;
        }
        return Override.CONTINUE_EVICT;
    }
}
