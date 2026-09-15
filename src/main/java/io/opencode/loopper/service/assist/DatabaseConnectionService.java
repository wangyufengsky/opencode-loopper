package io.opencode.loopper.service.assist;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.AssistMapper;
import io.opencode.loopper.persistence.AssistMapper.DatabaseRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.service.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class DatabaseConnectionService {
    private final AssistMapper mapper;
    private final LoopperMapper projects;
    private final DatabaseSecretStore secrets;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public DatabaseConnectionService(AssistMapper mapper,LoopperMapper projects,DatabaseSecretStore secrets,
                                     ObjectMapper json,PlatformTransactionManager manager) {
        this.mapper=mapper; this.projects=projects; this.secrets=secrets; this.json=json;
        this.transactions=new TransactionTemplate(manager);
    }
    public record Request(String name,DatabaseConfig config,String password,boolean enabled,boolean archived,
                          List<String> projectIds,long version) {
        @Override public String toString() { return "DatabaseConnectionRequest[redacted]"; }
    }
    public record View(String id,String name,DatabaseConfig config,boolean passwordConfigured,boolean enabled,
                       boolean archived,List<String> projectIds,long version,String createdAt) { }
    public record Bound(String id,String name,DatabaseConfig config,String credentialRef,long version) { }
    public CursorPage<View> list(String cursor,Integer requested) {return list(cursor,requested,"","","ALL");}
    public CursorPage<View> list(String cursor,Integer requested,String query,String type,String state) {
        query=query==null?"":query.trim();type=type==null?"":type;state=state==null?"AVAILABLE":state;
        if(query.length()>100 || !type.isEmpty() && !Arrays.stream(DatabaseConfig.Type.values()).map(Enum::name).toList().contains(type))
            throw new AssistFailure("DATABASE_FILTER_INVALID","筛选条件无效，请重新选择");
        if(!Set.of("ALL","AVAILABLE","ENABLED","DISABLED","ARCHIVED").contains(state))throw new AssistFailure("DATABASE_FILTER_INVALID","请选择有效状态");
        int limit=PageCursor.limit(requested); PageCursor page=PageCursor.decode(cursor);
        var rows=mapper.filteredDatabases(page==null?"":page.value(),page==null?"":page.id(),limit+1,query,type,state);
        var visible=rows.stream().limit(limit).toList(); Map<String,List<String>> bindings=new HashMap<>();
        if(!visible.isEmpty()) mapper.databaseBindings(visible.stream().map(DatabaseRow::id).toList())
                .forEach(b->bindings.computeIfAbsent(b.get("connection_id"),ignored->new ArrayList<>()).add(b.get("project_id")));
        String next=rows.size()>limit?new PageCursor(visible.getLast().createdAt(),visible.getLast().id()).encode():null;
        return new CursorPage<>(visible.stream().map(r->view(r,bindings.getOrDefault(r.id(),List.of()))).toList(),next);
    }
    public View get(String id) { return view(require(id),mapper.databaseProjects(id)); }
    public View save(String id,Request request) {
        if(request==null || request.config()==null || request.name()==null || request.name().isBlank() || request.name().length()>100)
            throw new AssistFailure("DATABASE_CONFIG_INVALID","请填写连接名称和完整配置");
        DatabaseRow old=id==null?null:require(id);
        DatabaseConfig config=configuration(request.config(),old); List<String> bindings=List.copyOf(new LinkedHashSet<>(request.projectIds()==null?List.of():request.projectIds()));
        if(old!=null && config.type()==DatabaseConfig.Type.GOLDENDB
                && (request.password()!=null || !request.name().equals(old.name()) || !new HashSet<>(bindings).equals(new HashSet<>(mapper.databaseProjects(id)))
                    || request.enabled() && old.enabled()==0 || old.archived()==1 && !request.archived()))
            throw new AssistFailure("DATABASE_TYPE_UNAVAILABLE","此历史类型目前只支持查看、停用和归档；请使用已内置驱动的类型新增连接","CONFIGURE");
        if(bindings.size()>100) throw new AssistFailure("DATABASE_PROJECT_LIMIT","一个连接最多绑定 100 个项目");
        bindings.forEach(p->projects.findProject(p).orElseThrow(()->new NotFoundException("项目不存在")));
        if(old!=null && old.version()!=request.version()) throw conflict();
        String credential=request.password()!=null?secrets.save(request.password()):old==null?null:old.credentialRef();
        if(credential==null) throw new AssistFailure("DATABASE_PASSWORD_REQUIRED","首次保存请填写数据库密码");
        String key=id==null?UUID.randomUUID().toString():id; String now=Instant.now().toString();
        DatabaseRow row=new DatabaseRow(key,request.name().trim(),json.writeValueAsString(config),credential,
                request.enabled()?1:0,request.archived()?1:0,request.version(),old==null?now:old.createdAt(),now);
        transactions.executeWithoutResult(status->{
            if(old==null) mapper.insertDatabase(row); else if(mapper.updateDatabase(row)!=1) throw conflict();
            mapper.clearDatabaseProjects(key); bindings.forEach(p->mapper.bindDatabase(key,p));
            mapper.audit(UUID.randomUUID().toString(),"","database",key,old==null?"CREATE":"UPDATE",now);
        });
        return get(key);
    }
    private DatabaseConfig configuration(DatabaseConfig input,DatabaseRow old) {
        if(input==null)throw new AssistFailure("DATABASE_CONFIG_INVALID","请填写完整配置");
        // Metadata-only edits retain the concrete legacy driver and frozen identity.
        if(old!=null && bound(old).config().equals(input))return input.validated();
        return BundledDatabaseDrivers.resolve(input);
    }
    public Bound draft(String id,Request request) {
        if(request==null)throw new AssistFailure("DATABASE_CONFIG_INVALID","请填写完整配置");
        DatabaseRow old=id==null?null:require(id);
        if(old!=null && old.version()!=request.version())throw conflict();
        DatabaseConfig config=configuration(request.config(),old);
        if(old==null && request.password()==null)throw new AssistFailure("DATABASE_PASSWORD_REQUIRED","测试连接请填写数据库密码");
        return new Bound(old==null?"draft":old.id(),"连接测试",config,old==null?null:old.credentialRef(),old==null?0:old.version());
    }
    public List<Bound> forProject(String project) {
        List<DatabaseRow> rows=mapper.projectDatabases(project);
        if(rows.size()>100) throw new AssistFailure("DATABASE_CONNECTION_LIMIT","项目绑定超过 100 个可用连接，请缩小范围","CONFIGURE");
        return rows.stream().map(this::bound).toList();
    }
    public Bound forTest(String id) { return bound(require(id)); }
    private Bound bound(DatabaseRow row) { return new Bound(row.id(),row.name(),json.readValue(row.configJson(),DatabaseConfig.class),row.credentialRef(),row.version()); }
    private View view(DatabaseRow row,List<String> bindings) { return new View(row.id(),row.name(),bound(row).config(),true,row.enabled()==1,row.archived()==1,bindings,row.version(),row.createdAt()); }
    private DatabaseRow require(String id) { var row=mapper.database(id); if(row==null) throw new NotFoundException("数据库连接不存在"); return row; }
    private ConflictException conflict() { return new ConflictException("DATABASE_VERSION_CONFLICT","连接已被修改，请刷新后重试"); }
}
