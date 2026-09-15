package io.opencode.loopper.service.assist;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Parses vendor addresses separately from properties so URLs cannot override connection guards. */
public final class JdbcConnectionUrl {
    private JdbcConnectionUrl() { }
    public record Parsed(String driverUrl, String host, int port, String database, Map<String,String> parameters) { }
    private record Address(String host,int port) { }

    public static DatabaseConfig normalize(DatabaseConfig c) {
        if (c.jdbcUrl() == null) return c;
        Parsed p = parse(c);
        return new DatabaseConfig(c.type(),p.host(),p.port(),p.database(),c.username(),c.driverFile(),c.driverClass(),
                c.schemas(),p.parameters(),c.timeoutSeconds(),c.maxRows(),c.driverProfile(),c.jdbcUrl());
    }

    public static Parsed parse(DatabaseConfig c) {
        String url = c.jdbcUrl();
        if (c.type() == null || url == null || url.length() > 4096 || url.chars().anyMatch(Character::isWhitespace) || url.contains("#")) throw invalid();
        if (c.type() == DatabaseConfig.Type.ORACLE) return oracle(c,url);
        String prefix = switch (c.type()) {
            case MYSQL -> "jdbc:mysql://";
            case OPENGAUSS -> url.startsWith("jdbc:opengauss://") ? "jdbc:opengauss://" : "jdbc:postgresql://";
            case GAUSSDB -> "jdbc:postgresql://";
            case DAMENG -> "jdbc:dm://";
            case DB2 -> "jdbc:db2://";
            default -> throw invalid();
        };
        if (!url.startsWith(prefix)) throw invalid();
        String body=url.substring(prefix.length());
        String[] parts=c.type()==DatabaseConfig.Type.DB2 ? db2Parts(body) : body.split("\\?",-1);
        if (parts.length > 2) throw invalid();
        int slash=parts[0].indexOf('/');
        String addresses=slash<0 ? parts[0] : parts[0].substring(0,slash);
        String database=slash<0 ? (c.type()==DatabaseConfig.Type.DAMENG && !c.schemas().isEmpty() ? c.schemas().getFirst() : "") : parts[0].substring(slash+1);
        if (!database.matches("[\\p{L}\\p{N}_$-]{1,128}")) throw invalid();
        String[] nodes=addresses.split(",",-1);
        if (nodes.length>16 || (c.type()==DatabaseConfig.Type.DAMENG || c.type()==DatabaseConfig.Type.DB2) && nodes.length!=1) throw invalid();
        int defaultPort=switch(c.type()) {case MYSQL -> 3306; case DAMENG -> 5236; case DB2 -> 50000; default -> 5432;};
        Address first=address(nodes[0],defaultPort);
        for(String node:nodes) address(node,defaultPort);
        Map<String,String> parameters=parameters(c,parts.length==2 ? parts[1] : null,c.type()==DatabaseConfig.Type.DB2 ? ";" : "&");
        String driverPrefix=prefix;
        if(c.type()==DatabaseConfig.Type.OPENGAUSS) {
            boolean modern=c.driverClass()!=null && c.driverClass().startsWith("org.opengauss.");
            driverPrefix=modern ? "jdbc:opengauss://" : "jdbc:postgresql://";
        }
        return new Parsed(driverPrefix+parts[0],first.host(),first.port(),database,parameters);
    }
    private static Address address(String text,int defaultPort) {
        var match=java.util.regex.Pattern.compile("(\\[[0-9a-fA-F:]+\\]|[a-zA-Z0-9_.-]{1,253})(?::([0-9]{1,5}))?").matcher(text);
        if(!match.matches())throw invalid();
        int port=match.group(2)==null ? defaultPort : Integer.parseInt(match.group(2));
        if(port<1 || port>65535)throw invalid();
        return new Address(match.group(1).replace("[","").replace("]",""),port);
    }
    private static String[] db2Parts(String body) {
        int slash=body.indexOf('/');
        if(slash<0)throw invalid();
        int colon=body.indexOf(':',slash);
        if(colon<0)return new String[]{body};
        String options=body.substring(colon+1);
        if(!options.endsWith(";"))throw invalid();
        return new String[]{body.substring(0,colon),options.substring(0,options.length()-1)};
    }
    private static Parsed oracle(DatabaseConfig c,String url) {
        String prefix="jdbc:oracle:thin:@";
        if(!url.startsWith(prefix))throw invalid();
        String[] parts=url.substring(prefix.length()).split("\\?",-1);
        if(parts.length>2)throw invalid();
        String body=parts[0]; String endpoint; String database;
        int slash=body.lastIndexOf('/');
        if(slash>=0) {
            int start=body.startsWith("//")?2:0;
            if(slash<=start)throw invalid();
            endpoint=body.substring(start,slash);
            database=body.substring(slash+1);
        } else {
            int colon=body.lastIndexOf(':');
            if(colon<0)throw invalid();
            endpoint=body.substring(0,colon); database=body.substring(colon+1);
        }
        if(!database.matches("[\\p{L}\\p{N}_$.-]{1,128}"))throw invalid();
        Address node=address(endpoint,1521);
        return new Parsed(prefix+body,node.host(),node.port(),database,parameters(c,parts.length==2?parts[1]:null,"&"));
    }
    private static Map<String,String> parameters(DatabaseConfig c,String query,String separator) {
        Map<String,String> result=new LinkedHashMap<>();
        if(query!=null)for(String pair:query.split(separator,-1)) {
            String[] item=pair.split("=",-1);
            if(item.length!=2)throw invalid();
            String key=decode(item[0]),value=decode(item[1]);
            if(result.putIfAbsent(key,value)!=null)throw invalid();
        }
        DatabaseDialect.forType(c.type()).validateParameters(result);
        return Map.copyOf(result);
    }
    private static String decode(String text) {
        try { return URLDecoder.decode(text,StandardCharsets.UTF_8); }
        catch(IllegalArgumentException invalid) { throw invalid(); }
    }
    private static AssistFailure invalid() {
        return new AssistFailure("DATABASE_URL_INVALID","请填写与数据库类型匹配的 JDBC URL；用户名和密码请单独填写。GaussDB 使用 jdbc:postgresql://，Oracle 使用 thin 服务名或 SID，DB2 使用 jdbc:db2://");
    }
}
