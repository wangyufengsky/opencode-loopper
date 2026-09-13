package io.opencode.loopper.service.assist;

import jakarta.annotation.PreDestroy;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Bounded external I/O outside SQLite transactions. A timed-out worker retains its concurrency slot. */
@Service
public class DatabaseQueryService implements AutoCloseable {
    private final DatabaseDriverRegistry drivers;
    private final DatabaseSecretStore secrets;
    private final ObjectMapper json;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(8,8,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),
            Thread.ofPlatform().daemon().name("database-query-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService cancellations=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),
            Thread.ofPlatform().daemon().name("database-cancel-",0).factory(),new ThreadPoolExecutor.DiscardPolicy());
    private final ConcurrentHashMap<String,Semaphore> slots=new ConcurrentHashMap<>();
    public DatabaseQueryService(DatabaseDriverRegistry drivers,DatabaseSecretStore secrets,ObjectMapper json) {
        this.drivers=drivers; this.secrets=secrets; this.json=json;
    }
    public Map<String,Object> test(DatabaseConnectionService.Bound bound) {return test(bound,null);}
    public Map<String,Object> test(DatabaseConnectionService.Bound bound,String draftPassword) {
        return execute(bound,(opened,active)->{
            DatabaseMetaData md=opened.connection().getMetaData();
            boolean readOnly;
            try {DatabaseDialect.forType(bound.config().type()).prepare(opened.connection(),bound.config());readOnly=opened.connection().isReadOnly();}
            catch(SQLException unsupported){readOnly=false;}
            return Map.of("connected",true,"sessionReadOnly",readOnly,"serverProduct",md.getDatabaseProductName(),
                    "serverVersion",md.getDatabaseProductVersion(),"driverVersion",opened.driverVersion(),"driverSha256",opened.info().sha256(),
                    "compatibilityVerified",false,"detail",readOnly?"连接与只读标记已检查；账号权限和完整兼容性须使用现场测试库验收":"连接成功但只读控制不可用，查询将被拒绝；请检查匹配驱动与配置");
        },true,()->draftPassword==null?secrets.read(bound.credentialRef()):draftPassword);
    }
    public Map<String,Object> query(DatabaseConnectionService.Bound bound,String sql) {
        String validated=ReadOnlySqlPolicy.validate(sql,bound.config());
        return execute(bound,(opened,active)->{
            try(Statement statement=opened.connection().createStatement()) {
                active.set(statement); statement.setQueryTimeout(bound.config().timeoutSeconds());
                statement.setMaxRows(bound.config().maxRows()+1);
                try(ResultSet rs=statement.executeQuery(validated)) { return results(rs,bound.config().maxRows()); }
            } finally { active.set(null); }
        });
    }
    public Map<String,Object> inspect(DatabaseConnectionService.Bound bound,String schema,String table,String kind,int offset) {
        if(!bound.config().schemas().contains(schema)) throw new AssistFailure("DATABASE_SCHEMA_FORBIDDEN","请选择连接允许的 schema／数据库");
        if(table!=null && !table.isBlank() && !table.matches("[\\p{L}\\p{N}_$-]{1,128}")) throw new AssistFailure("DATABASE_TABLE_INVALID","请使用结构查询返回的精确表名");
        if(offset<0 || offset>10000) throw new AssistFailure("DATABASE_CURSOR_INVALID","结构游标越界，请缩小查询范围");
        return execute(bound,(opened,active)->{
            DatabaseMetaData md=opened.connection().getMetaData(); String catalog=DatabaseDialect.forType(bound.config().type()).catalog(bound.config());
            boolean catalogSchema=catalog!=null; String selectedCatalog=catalogSchema?schema:null; String selectedSchema=catalogSchema?null:schema;
            String escape=md.getSearchStringEscape();
            if((schema.contains("_")||table!=null&&table.contains("_"))&&(escape==null||escape.isEmpty()))throw new AssistFailure("DATABASE_METADATA_UNSUPPORTED","驱动不能精确转义结构名称，请检查匹配版本","CONFIGURE");
            String escaped=table==null?null:table.replace("_",escape+"_");
            String schemaPattern=selectedSchema==null?null:selectedSchema.replace("_",escape+"_");
            try(ResultSet rs=switch(kind==null?"tables":kind) {
                case "tables" -> md.getTables(selectedCatalog,schemaPattern,escaped,new String[]{"TABLE","VIEW"});
                case "columns" -> md.getColumns(selectedCatalog,schemaPattern,required(escaped),null);
                case "indexes" -> md.getIndexInfo(selectedCatalog,selectedSchema,required(table),false,true);
                case "keys" -> md.getImportedKeys(selectedCatalog,selectedSchema,required(table));
                default -> throw new AssistFailure("DATABASE_INSPECTION_INVALID","结构类型只能是 tables、columns、indexes、keys");
            }) {
                for(int i=0;i<offset;i++) if(!rs.next()) return Map.of("columns",List.of(),"rows",List.of(),"truncated",false);
                Map<String,Object> result=results(rs,100); result.put("nextOffset",Boolean.TRUE.equals(result.get("truncated"))?offset+(int)result.get("rowCount"):-1); return result;
            }
        });
    }
    private Map<String,Object> results(ResultSet rs,int maxRows) throws Exception {
        ResultSetMetaData md=rs.getMetaData(); int count=md.getColumnCount();
        if(count>256) throw new AssistFailure("DATABASE_COLUMN_LIMIT","查询列数超过 256，请明确选择所需字段");
        List<Map<String,Object>> columns=new ArrayList<>();
        for(int i=1;i<=count;i++) columns.add(Map.of("name",md.getColumnLabel(i),"type",md.getColumnTypeName(i)));
        List<List<Object>> rows=new ArrayList<>(); int size=json.writeValueAsBytes(columns).length; boolean truncated=false;
        while(rs.next()) {
            if(rows.size()==maxRows) {truncated=true;break;}
            List<Object> row=new ArrayList<>();
            for(int i=1;i<=count;i++) {
                int type=md.getColumnType(i);
                if(Set.of(Types.BLOB,Types.CLOB,Types.NCLOB,Types.LONGVARBINARY,Types.BINARY,Types.VARBINARY,Types.SQLXML).contains(type)) {
                    row.add(Map.of("omitted",true,"reason","大对象请使用受限文本／长度查询"));truncated=true;
                }else if(Set.of(Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.DECIMAL,Types.NUMERIC).contains(type))row.add(rs.getBigDecimal(i));
                else if(type==Types.BOOLEAN){boolean value=rs.getBoolean(i);row.add(rs.wasNull()?null:value);}
                else {
                    try(java.io.Reader reader=rs.getCharacterStream(i)) {
                        if(reader==null)row.add(null);else {
                            char[] buffer=new char[16385];int sizeRead=0,n;
                            while(sizeRead<buffer.length&&(n=reader.read(buffer,sizeRead,buffer.length-sizeRead))>0)sizeRead+=n;
                            row.add(new String(buffer,0,Math.min(sizeRead,16384)));if(sizeRead>16384)truncated=true;
                        }
                    }
                }
            }
            int bytes=json.writeValueAsBytes(row).length;
            if(size+bytes>1000000) {truncated=true;break;} size+=bytes; rows.add(row);
        }
        Map<String,Object> result=new LinkedHashMap<>(); result.put("columns",columns);result.put("rows",rows);
        result.put("truncated",truncated);result.put("rowCount",rows.size());
        result.put("detail",truncated?"结果不完整，请增加筛选或缩小字段范围":"结果已完整读取"); return result;
    }
    private Map<String,Object> execute(DatabaseConnectionService.Bound bound,Operation operation) {
        return execute(bound,operation,false);
    }
    private Map<String,Object> execute(DatabaseConnectionService.Bound bound,Operation operation,boolean diagnostic) {
        return execute(bound,operation,diagnostic,()->secrets.read(bound.credentialRef()));
    }
    private Map<String,Object> execute(DatabaseConnectionService.Bound bound,Operation operation,boolean diagnostic,java.util.function.Supplier<String> credential) {
        if(slots.size()>1024 && !slots.containsKey(bound.id())) throw busy();
        Semaphore slot=slots.computeIfAbsent(bound.id(),ignored->new Semaphore(2)); if(!slot.tryAcquire()) throw busy();
        AtomicReference<Statement> active=new AtomicReference<>(); Future<Map<String,Object>> future;
        var expired=new java.util.concurrent.atomic.AtomicBoolean();
        long start=System.nanoTime();
        try { future=workers.submit(()->{
            try {
              if(expired.get())throw expired();
              try(var opened=diagnostic?drivers.diagnose(bound.config(),credential.get()):drivers.open(bound.config(),credential.get())) {
                if(expired.get())throw expired();
                var result=new LinkedHashMap<>(operation.run(opened,active));result.put("driverSha256",opened.info().sha256());result.put("driverVersion",opened.driverVersion());return result;
              }
            }
            finally {slot.release();}
        }); } catch(RejectedExecutionException rejected) {slot.release();throw busy();}
        try {
            Map<String,Object> result=new LinkedHashMap<>(future.get(bound.config().timeoutSeconds()+5L,TimeUnit.SECONDS));
            result.put("collectedAt",Instant.now().toString()); result.put("elapsedMs",(System.nanoTime()-start)/1000000);
            result.put("connectionVersion",bound.version()); return result;
        } catch(TimeoutException timeout) {
            expired.set(true);
            Statement statement=active.get(); if(statement!=null) cancellations.submit(()->{try{statement.cancel();}catch(Exception ignored){}});
            throw new AssistFailure("DATABASE_QUERY_TIMEOUT","查询超时，已请求取消；结果未知，请勿自动重复执行","STOP_AND_INSPECT");
        } catch(InterruptedException interrupted) { expired.set(true);Thread.currentThread().interrupt(); throw new AssistFailure("DATABASE_QUERY_INTERRUPTED","查询等待已中断，请检查连接状态后再决定是否重试","STOP_AND_INSPECT"); }
        catch(ExecutionException failure) { if(failure.getCause() instanceof AssistFailure safe) throw safe;
            throw new AssistFailure("DATABASE_QUERY_FAILED","数据库读取失败，请检查驱动、只读账号、连接及 SQL 字段；底层异常已隐藏","CONFIGURE_OR_FIX_QUERY"); }
    }
    private static String required(String table) { if(table==null || table.isBlank()) throw new AssistFailure("DATABASE_TABLE_REQUIRED","请指定表名"); return table; }
    private static AssistFailure busy() {return new AssistFailure("DATABASE_BUSY","数据库查询容量已满，请等待已有调用结束","WAIT");}
    private static AssistFailure expired(){return new AssistFailure("DATABASE_QUERY_EXPIRED","调用等待已结束，不再启动查询","STOP_AND_INSPECT");}
    @PreDestroy public void close() {workers.shutdownNow(); cancellations.shutdownNow();}
    private interface Operation {Map<String,Object> run(DatabaseDriverRegistry.Opened opened,AtomicReference<Statement> active) throws Exception;}
}
