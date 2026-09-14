package io.opencode.loopper.service.assist;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Separates vendor URL, session and metadata semantics. Only explicitly supported properties pass. */
public sealed interface DatabaseDialect permits DatabaseDialect.MySql, DatabaseDialect.Gauss,
        DatabaseDialect.Golden, DatabaseDialect.Dameng {
    String prefix();
    default String url(DatabaseConfig config) {
        if (config.jdbcUrl() != null) return JdbcConnectionUrl.parse(config).driverUrl();
        String host = config.host().contains(":") ? "[" + config.host() + "]" : config.host();
        return prefix() + host + ":" + config.port() + "/" + config.database();
    }
    default void validateParameters(Map<String,String> parameters) {
        Set<String> allowed = Set.of("ssl", "sslmode", "useSSL", "requireSSL", "verifyServerCertificate", "serverTimezone", "characterEncoding", "targetServerType", "loadBalanceHosts", "hostRecheckSeconds");
        if (parameters.size() > 8 || parameters.entrySet().stream().anyMatch(e -> !allowed.contains(e.getKey())
                || e.getValue() == null || !e.getValue().matches("[a-zA-Z0-9_+/:.-]{1,80}")))
            throw new AssistFailure("DATABASE_PARAMETER_FORBIDDEN", "连接参数不在允许列表；不能覆盖只读、超时或本地文件访问保护");
    }
    default Properties properties(DatabaseConfig c, String password) {
        Properties p = new Properties(); p.putAll(c.parameters());
        p.setProperty("user", c.username()); p.setProperty("password", password);
        p.setProperty("connectTimeout", "5000"); p.setProperty("socketTimeout", "30000");
        return p;
    }
    default void prepare(Connection connection, DatabaseConfig config) throws SQLException {
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        if (!connection.isReadOnly()) throw new SQLException("Read-only session unavailable");
    }
    default String catalog(DatabaseConfig config) { return null; }
    static DatabaseDialect forType(DatabaseConfig.Type type) {
        return switch (type) { case MYSQL -> new MySql(); case GAUSSDB, OPENGAUSS -> new Gauss();
            case GOLDENDB -> new Golden(); case DAMENG -> new Dameng(); };
    }
    final class MySql implements DatabaseDialect {
        public String prefix() { return "jdbc:mysql://"; }
        public String catalog(DatabaseConfig c) { return c.database(); }
        public Properties properties(DatabaseConfig c, String password) {
            Properties p = DatabaseDialect.super.properties(c,password);
            p.setProperty("allowMultiQueries","false"); p.setProperty("allowLoadLocalInfile","false");
            p.setProperty("allowUrlInLocalInfile","false"); p.setProperty("readOnlyPropagatesToServer","true"); return p;
        }
    }
    final class Gauss implements DatabaseDialect {
        public String prefix() { return "jdbc:gaussdb://"; }
        public String url(DatabaseConfig c) {
            if (c.jdbcUrl() != null) return DatabaseDialect.super.url(c);
            String url = DatabaseDialect.super.url(c);
            if (c.driverClass().startsWith("org.opengauss.")) return url.replace("jdbc:gaussdb:","jdbc:opengauss:");
            if (c.driverClass().startsWith("org.postgresql.")) return url.replace("jdbc:gaussdb:","jdbc:postgresql:");
            return url;
        }
        public Properties properties(DatabaseConfig c, String password) {
            Properties p = DatabaseDialect.super.properties(c,password); p.setProperty("allowReadOnly","true");
            p.setProperty("connectTimeout","5"); p.setProperty("socketTimeout","30");
            p.setProperty("readOnlyMode","always"); return p;
        }
        public void prepare(Connection connection, DatabaseConfig config) throws SQLException {
            DatabaseDialect.super.prepare(connection, config); connection.setSchema(config.schemas().getFirst());
        }
    }
    final class Golden implements DatabaseDialect {
        public String prefix() { return "jdbc:goldendb://"; }
        public String url(DatabaseConfig c) {
            String url=DatabaseDialect.super.url(c);
            return c.driverClass().startsWith("com.mysql.")?url.replace("jdbc:goldendb:","jdbc:mysql:"):url;
        }
        public String catalog(DatabaseConfig c) { return c.database(); }
        public Properties properties(DatabaseConfig c,String password) {
            Properties p=DatabaseDialect.super.properties(c,password); p.setProperty("allowMultiQueries","false");
            p.setProperty("allowLoadLocalInfile","false"); p.setProperty("readOnlyPropagatesToServer","true"); return p;
        }
    }
    final class Dameng implements DatabaseDialect {
        public String prefix() { return "jdbc:dm://"; }
        public String url(DatabaseConfig c) { return c.jdbcUrl() != null ? DatabaseDialect.super.url(c) : DatabaseDialect.super.url(c).replace("/"+c.database(),""); }
        public Properties properties(DatabaseConfig c,String password) {
            Properties p=DatabaseDialect.super.properties(c,password); p.setProperty("schema",c.schemas().getFirst());
            p.setProperty("loginTimeout","5"); p.setProperty("socketTimeout","30000"); return p;
        }
        public void prepare(Connection connection,DatabaseConfig config) throws SQLException {
            DatabaseDialect.super.prepare(connection, config); connection.setSchema(config.schemas().getFirst());
        }
    }
}
