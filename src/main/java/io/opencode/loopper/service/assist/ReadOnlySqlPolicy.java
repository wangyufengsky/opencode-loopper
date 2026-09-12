package io.opencode.loopper.service.assist;

import java.util.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

/** AST-gated conservative SQL subset. Unsupported syntax is never sent to a driver. */
public final class ReadOnlySqlPolicy {
    private static final Set<String> FORBIDDEN=Set.of("INSERT","UPDATE","DELETE","MERGE","UPSERT","REPLACE","CREATE","ALTER","DROP",
            "TRUNCATE","GRANT","REVOKE","INTO","OUTFILE","DUMPFILE","COPY","LOAD","CALL","EXEC","EXECUTE","DO","SET","RESET",
            "FOR","LOCK","LOCKED","UNLOCK","RETURNING","NEXTVAL","CURRVAL","OPERATOR","COLLATE","PROCEDURE","ANALYZE","EXPLAIN",
            "PG_SLEEP","SLEEP","BENCHMARK","CONNECT","START","COMMIT","ROLLBACK","PRAGMA");
    private static final Set<String> FUNCTIONS=Set.of("COUNT","SUM","MIN","MAX","AVG","ABS","ROUND","FLOOR","CEIL","CEILING",
            "LOWER","UPPER","LENGTH","CHAR_LENGTH","SUBSTRING","SUBSTR","TRIM","LTRIM","RTRIM","COALESCE","NULLIF","CONCAT",
            "CAST","EXTRACT","DATE","YEAR","MONTH","DAY");
    private static final Set<String> GROUPS=Set.of("SELECT","WITH","AS","IN","EXISTS","NOT","AND","OR","ON","OVER","PARTITION","BY");
    private ReadOnlySqlPolicy() { }
    public static String validate(String sql,DatabaseConfig config) {
        if(sql==null || sql.isBlank() || sql.length()>16384) throw invalid("SQL 为空或超过 16 Ki 字符，请缩小查询");
        List<String> tokens=tokens(sql);
        for(int i=0;i<tokens.size();i++) {
            String t=tokens.get(i).toUpperCase(Locale.ROOT);
            if(FORBIDDEN.contains(t)) throw invalid("只允许单条只读 SELECT／WITH，不允许写入、锁定或会话操作");
            if(t.equals("(") && i>0) {
                String previous=tokens.get(i-1).toUpperCase(Locale.ROOT);
                if(previous.matches("[\\p{L}_].*") && !FUNCTIONS.contains(previous) && !GROUPS.contains(previous))
                    throw invalid("函数或语法不在只读白名单："+previous+"；请改用基础查询表达式");
                if(previous.startsWith("\"") || previous.startsWith("`") || i>1 && tokens.get(i-2).equals("."))
                    throw invalid("不支持限定名称或带引号的函数调用");
            }
        }
        try {
            var statements=CCJSqlParserUtil.parseStatements(sql,p->p.withTimeOut(2000));
            if(statements.size()!=1 || !(statements.get(0) instanceof Select select)) throw invalid("只允许单条 SELECT／只读 WITH");
            for(String table:new TablesNamesFinder<Void>().getTables((net.sf.jsqlparser.statement.Statement) select)) {
                String[] parts=table.replace("\"","").replace("`","").split("\\.");
                if(parts.length>2) throw invalid("不能跨服务器或跨数据库限定查询");
                if(parts.length==2 && config.schemas().stream().noneMatch(s->s.equals(parts[0])))
                    throw invalid("SQL 引用了未授权的 schema／数据库，请先浏览允许的结构");
            }
            return sql.strip().replaceAll(";\\s*$","");
        } catch(AssistFailure failure) { throw failure; }
        catch(Exception failure) { throw invalid("SQL 无法按支持的只读语法解析，请改用单条 SELECT 或只读 WITH"); }
    }
    private static List<String> tokens(String sql) {
        List<String> result=new ArrayList<>();
        for(int i=0;i<sql.length();) {
            char c=sql.charAt(i);
            if(Character.isWhitespace(c)) { i++; continue; }
            if(c=='\\' || c=='$' || c=='@' || c=='#' || c==':' || c=='[' || c==']') throw invalid("不支持该 SQL 扩展语法");
            if(i+1<sql.length() && (sql.startsWith("--",i)||sql.startsWith("/*",i))) throw invalid("请移除 SQL 注释后重试");
            if(c=='\'' || c=='\"' || c=='`') {
                char quote=c; int start=i++; boolean closed=false;
                while(i<sql.length()) { char next=sql.charAt(i++); if(next=='\\') throw invalid("请使用标准 SQL 引号转义");
                    if(next==quote) { if(i<sql.length() && sql.charAt(i)==quote) i++; else {closed=true;break;} } }
                if(!closed) throw invalid("SQL 引号未闭合");
                result.add(quote=='\''?"<literal>":sql.substring(start,i)); continue;
            }
            if(Character.isLetterOrDigit(c)||c=='_') {
                int start=i++; while(i<sql.length() && (Character.isLetterOrDigit(sql.charAt(i))||sql.charAt(i)=='_'))i++;
                result.add(sql.substring(start,i));
            } else { if("(),.;*+-/%=<>!|".indexOf(c)<0) throw invalid("不支持该 SQL 运算符"); result.add(String.valueOf(c)); i++; }
        }
        return result;
    }
    private static AssistFailure invalid(String detail) { return new AssistFailure("DATABASE_SQL_REJECTED",detail); }
}
