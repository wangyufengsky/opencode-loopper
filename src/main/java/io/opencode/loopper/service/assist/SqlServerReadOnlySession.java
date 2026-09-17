package io.opencode.loopper.service.assist;

import java.sql.Connection;
import java.sql.SQLException;

/** SQL Server has no JDBC read-only session: reject effective write/execute permissions before use. */
final class SqlServerReadOnlySession {
    private SqlServerReadOnlySession() { }
    static void prepare(Connection connection, DatabaseConfig config) throws SQLException {
        connection.setAutoCommit(false);
        // ReadOnly application intent is routing, not a permission boundary. Never claim isReadOnly=true.
        String sql = """
                SELECT CASE WHEN
                  EXISTS (SELECT 1 FROM sys.fn_my_permissions(NULL, 'SERVER')
                    WHERE permission_name <> 'CONNECT SQL' AND permission_name NOT LIKE 'VIEW %')
                  OR EXISTS (SELECT 1 FROM sys.fn_my_permissions(NULL, 'DATABASE')
                    WHERE permission_name NOT IN ('CONNECT', 'SELECT') AND permission_name NOT LIKE 'VIEW %')
                  OR EXISTS (SELECT 1 FROM sys.fn_my_permissions(?, 'SCHEMA')
                    WHERE permission_name <> 'SELECT' AND permission_name NOT LIKE 'VIEW %')
                  OR EXISTS (SELECT 1 FROM sys.objects o
                    CROSS APPLY sys.fn_my_permissions(QUOTENAME(SCHEMA_NAME(o.schema_id))+'.'+QUOTENAME(o.name), 'OBJECT') p
                    WHERE SCHEMA_NAME(o.schema_id)=? AND p.permission_name <> 'SELECT' AND p.permission_name NOT LIKE 'VIEW %')
                  THEN 0 ELSE 1 END
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(config.timeoutSeconds());
            statement.setMaxRows(1);
            for (String schema : config.schemas()) {
                statement.setString(1, schema); statement.setString(2, schema);
                try (var result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1)
                        throw new AssistFailure("DATABASE_READONLY_REQUIRED", "SQL Server 账号拥有写入、执行或管理权限，请使用仅有 SELECT 权限的专用账号");
                }
            }
        }
    }
}
