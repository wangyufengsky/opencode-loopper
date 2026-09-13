package io.opencode.loopper.service.assist;

import io.opencode.loopper.config.LoopperProperties;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Standalone offline runner, with no Spring context, web server, scheduler or provider calls. */
public final class DatabaseOfflineAcceptance {
    private DatabaseOfflineAcceptance() { }
    public record Input(DatabaseConfig connection,String readSql,String timeoutSql) { }
    public static void main(String[] args) throws Exception {
        if(args.length!=3){System.err.println("Usage: DatabaseOfflineAcceptance <data-directory> <probe.json> <report.json>");System.exit(2);}
        ObjectMapper json=new ObjectMapper();Input input=json.readValue(AssistFiles.read(Path.of(args[1]),65536),Input.class);
        DatabaseConfig config=input.connection().driverFile()==null || input.connection().driverFile().isBlank() ? BundledDatabaseDrivers.resolve(input.connection()) : input.connection().validated();String password=System.getenv("LOOPPER_DATABASE_PROBE_PASSWORD");
        if(password==null){var console=System.console();if(console==null)throw new IllegalArgumentException("请使用环境变量 LOOPPER_DATABASE_PROBE_PASSWORD 提供只读账号密码");char[] value=console.readPassword("数据库只读账号密码: ");password=new String(value);Arrays.fill(value,'\0');}
        Path temporary=Files.createTempDirectory("loopper-db-probe-").toRealPath();Path secretDir=temporary.resolve("secrets"),key=temporary.resolve("keys/master");String reference=null;
        Map<String,Object> report=new LinkedHashMap<>();report.put("collectedAt",Instant.now().toString());report.put("type",config.type());report.put("writeProbesExecuted",false);
        try {
            var properties=new LoopperProperties();properties.setDataDir(Path.of(args[0]));var registry=new DatabaseDriverRegistry(properties);
            var secrets=new DatabaseSecretStore(secretDir,key,null);reference=secrets.save(password);password=null;
            var bound=new DatabaseConnectionService.Bound("offline-probe","现场验收",config,reference,0);
            try(var queries=new DatabaseQueryService(registry,secrets,json)) {
                report.put("connection",queries.test(bound));report.put("schema",summary(queries.inspect(bound,config.schemas().getFirst(),null,"tables",0)));
                String sql=input.readSql()==null?(config.type()==DatabaseConfig.Type.DAMENG?"SELECT 1 FROM DUAL":"SELECT 1"):input.readSql();
                report.put("read",summary(queries.query(bound,sql)));
                List<String> rejected=new ArrayList<>();for(String negative:List.of("DELETE FROM forbidden_table","SELECT 1; SELECT 2","SELECT evil(1)","SELECT * FROM forbidden_schema.example")) {
                    try{ReadOnlySqlPolicy.validate(negative,config);throw new IllegalStateException("Readonly boundary probe unexpectedly accepted");}catch(AssistFailure expected){rejected.add(expected.code());}
                }
                report.put("localBoundaryRejections",rejected);
                if(input.timeoutSql()!=null){try{report.put("timeoutProbe",summary(queries.query(bound,input.timeoutSql())));}catch(AssistFailure failure){report.put("timeoutProbe",Map.of("code",failure.code(),"action",failure.action()));}}
                else report.put("timeoutProbe",Map.of("executed",false,"detail","提供预先准备的只读 timeoutSql 后单独验收；不会自动执行延时函数或写入夹具"));
                report.put("result","PROBES_COMPLETED");report.put("compatibilityVerified",false);
            }
        }catch(AssistFailure failure){report.put("result","BLOCKED");report.put("code",failure.code());report.put("detail",failure.getMessage());}
        finally {
            if(reference!=null)Files.deleteIfExists(secretDir.resolve(reference));Files.deleteIfExists(secretDir);Files.deleteIfExists(key);Files.deleteIfExists(key.getParent());Files.deleteIfExists(temporary);
        }
        Files.writeString(Path.of(args[2]),json.writerWithDefaultPrettyPrinter().writeValueAsString(report),StandardOpenOption.CREATE_NEW);
        System.out.println("脱敏验收报告已保存；报告只证明其中列出的探针，不自动宣称产品版本完整兼容。");
        if(!"PROBES_COMPLETED".equals(report.get("result")))System.exit(1);
    }
    private static Map<String,Object> summary(Map<String,Object> result) {
        Map<String,Object> safe=new LinkedHashMap<>();for(String field:List.of("elapsedMs","collectedAt","rowCount","truncated","connectionVersion","nextOffset"))if(result.containsKey(field))safe.put(field,result.get(field));
        safe.put("dataValuesOmitted",true);return safe;
    }
}
