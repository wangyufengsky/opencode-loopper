package io.opencode.loopper.service.assist;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Parses addresses separately from driver properties so URL options cannot override read-only guards. */
public final class JdbcConnectionUrl {
    private JdbcConnectionUrl() { }
    public record Parsed(String driverUrl, String host, int port, String database, Map<String,String> parameters) { }

    public static DatabaseConfig normalize(DatabaseConfig c) {
        if (c.jdbcUrl() == null) return c;
        Parsed p = parse(c);
        return new DatabaseConfig(c.type(),p.host(),p.port(),p.database(),c.username(),c.driverFile(),c.driverClass(),
                c.schemas(),p.parameters(),c.timeoutSeconds(),c.maxRows(),c.driverProfile(),c.jdbcUrl());
    }

    public static Parsed parse(DatabaseConfig c) {
        String url = c.jdbcUrl();
        if (c.type() == null || url == null || url.length() > 4096 || url.chars().anyMatch(Character::isWhitespace) || url.contains("#")) throw invalid();
        String prefix = switch (c.type()) {
            case MYSQL -> "jdbc:mysql://";
            case OPENGAUSS -> url.startsWith("jdbc:opengauss://") ? "jdbc:opengauss://" : "jdbc:postgresql://";
            case DAMENG -> "jdbc:dm://";
            default -> throw invalid();
        };
        if (!url.startsWith(prefix)) throw invalid();
        String[] parts = url.substring(prefix.length()).split("\\?", -1);
        if (parts.length > 2) throw invalid();
        int slash = parts[0].indexOf('/');
        String addresses = slash < 0 ? parts[0] : parts[0].substring(0,slash);
        String database = slash < 0 ? (c.type() == DatabaseConfig.Type.DAMENG && !c.schemas().isEmpty() ? c.schemas().getFirst() : "") : parts[0].substring(slash+1);
        if (!database.matches("[\\p{L}\\p{N}_$-]{1,128}")) throw invalid();
        String[] nodes = addresses.split(",", -1);
        if (nodes.length > 16 || c.type() == DatabaseConfig.Type.DAMENG && nodes.length != 1) throw invalid();
        String firstHost = null; int firstPort = 0;
        for (String node : nodes) {
            var match = java.util.regex.Pattern.compile("(\\[[0-9a-fA-F:]+\\]|[a-zA-Z0-9_.-]{1,253})(?::([0-9]{1,5}))?").matcher(node);
            if (!match.matches()) throw invalid();
            int port = match.group(2) == null ? switch(c.type()) {case MYSQL -> 3306; case DAMENG -> 5236; default -> 5432;} : Integer.parseInt(match.group(2));
            if (port < 1 || port > 65535) throw invalid();
            if (firstHost == null) { firstHost = match.group(1).replace("[", "").replace("]", ""); firstPort = port; }
        }
        Map<String,String> parameters = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>();
        if (parts.length == 2) for (String pair : parts[1].split("&", -1)) {
            String[] item = pair.split("=", -1);
            if (item.length != 2) throw invalid();
            String key = decode(item[0]), value = decode(item[1]);
            if (!keys.add(key)) throw invalid();
            parameters.put(key,value);
        }
        DatabaseDialect.forType(c.type()).validateParameters(parameters);
        // Match the frozen driver class, including the historical 6.0.3 PostgreSQL namespace.
        String driverPrefix = c.type() == DatabaseConfig.Type.OPENGAUSS
                ? (c.driverClass() != null && c.driverClass().startsWith("org.postgresql.") ? "jdbc:postgresql://" : "jdbc:opengauss://") : prefix;
        return new Parsed(driverPrefix + parts[0], firstHost, firstPort, database, Map.copyOf(parameters));
    }
    private static String decode(String text) {
        try { return URLDecoder.decode(text, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw invalid(); }
    }
    private static AssistFailure invalid() {
        return new AssistFailure("DATABASE_URL_INVALID", "请填写与数据库类型匹配的 JDBC URL；用户名和密码请单独填写，地址支持以逗号分隔多个节点");
    }
}
