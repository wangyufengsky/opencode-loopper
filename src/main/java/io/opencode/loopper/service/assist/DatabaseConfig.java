package io.opencode.loopper.service.assist;

import java.util.List;
import java.util.Map;

/** Non-secret, versioned connection settings. Passwords never form part of a JDBC URL. */
public record DatabaseConfig(Type type, String host, int port, String database, String username,
                             String driverFile, String driverClass, List<String> schemas,
                             Map<String,String> parameters, int timeoutSeconds, int maxRows, String driverProfile, String jdbcUrl) {
    public enum Type { MYSQL, OPENGAUSS, GAUSSDB, GOLDENDB, DAMENG }
    public DatabaseConfig(Type type,String host,int port,String database,String username,String driverFile,String driverClass,
                          List<String> schemas,Map<String,String> parameters,int timeoutSeconds,int maxRows) {
        this(type,host,port,database,username,driverFile,driverClass,schemas,parameters,timeoutSeconds,maxRows,null);
    }
    public DatabaseConfig(Type type,String host,int port,String database,String username,String driverFile,String driverClass,
                          List<String> schemas,Map<String,String> parameters,int timeoutSeconds,int maxRows,String driverProfile) {
        this(type,host,port,database,username,driverFile,driverClass,schemas,parameters,timeoutSeconds,maxRows,driverProfile,null);
    }
    public DatabaseConfig {
        jdbcUrl = jdbcUrl == null || jdbcUrl.isBlank() ? null : jdbcUrl.trim();
        schemas = schemas == null ? List.of() : List.copyOf(schemas);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
    public DatabaseConfig validated() {
        if (jdbcUrl != null) JdbcConnectionUrl.parse(this);
        if (type == null || host == null || !host.matches("[a-zA-Z0-9_.:-]{1,253}")
                || port < 1 || port > 65535 || database == null || !database.matches("[\\p{L}\\p{N}_$-]{1,128}")
                || username == null || username.isBlank() || username.length() > 128
                || driverFile == null || !driverFile.matches("[a-zA-Z0-9_.-]{1,180}\\.jar")
                || driverClass == null || !driverClass.matches("[a-zA-Z_$][a-zA-Z0-9_.$]{1,180}")) {
            throw new AssistFailure("DATABASE_CONFIG_INVALID", "请填写有效的数据库地址、端口、库名、用户名和本地驱动配置");
        }
        if (schemas.isEmpty() || schemas.size() > 32 || schemas.stream().anyMatch(s -> !s.matches("[\\p{L}\\p{N}_$-]{1,128}")))
            throw new AssistFailure("DATABASE_SCHEMA_REQUIRED", "请明确选择允许访问的 schema／数据库，最多 32 项");
        if (timeoutSeconds < 1 || timeoutSeconds > 30 || maxRows < 1 || maxRows > 1000)
            throw new AssistFailure("DATABASE_LIMIT_INVALID", "查询超时应为 1–30 秒，返回行数为 1–1000");
        DatabaseDialect.forType(type).validateParameters(parameters);
        if ((type==Type.MYSQL || type==Type.GOLDENDB) && !schemas.contains(database))
            throw new AssistFailure("DATABASE_SCHEMA_REQUIRED", "默认数据库必须包含在允许访问范围中");
        return this;
    }
}
