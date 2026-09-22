package io.opencode.loopper.service.ppt;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.PptKnowledgeMapper.Scope;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.knowledge.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Resolves project sources before the create transaction; later reads use the saved selection. */
@Service
public class PptKnowledge {
    private final PptKnowledgeMapper mapper;
    private final PptMapper documents;
    private final KnowledgeSources sources;
    private final ProjectService projects;
    private final ObjectMapper json;
    public PptKnowledge(PptKnowledgeMapper mapper,PptMapper documents,KnowledgeSources sources,ProjectService projects,ObjectMapper json) {
        this.mapper=mapper;this.documents=documents;this.sources=sources;this.projects=projects;this.json=json;
    }
    public record Project(String id,String name) { }
    public record ProjectChoice(String id,String name,String description) { }
    public record View(Project project,List<KnowledgeSources.View> sources,String detail) { }
    public record Prepared(Scope scope,long projectVersion) { }
    public Prepared prepare(String document,String project) {
        if(project==null)return null;
        var owner=projects.get(project);
        if(owner.managed()!=1)throw PptSupport.bad("PPT_PROJECT_UNAVAILABLE","项目已取消管理，请重新选择项目");
        List<KnowledgeSources.View> available=new ArrayList<>();String cursor=null;
        do {
            var page=sources.list(project,cursor,100);available.addAll(page.items());cursor=page.nextCursor();
            if(available.size()>100)throw PptSupport.bad("PPT_KNOWLEDGE_LIMIT","项目来源超过 100 项，请先在知识库整理来源后重试");
        }while(cursor!=null);
        var ids=available.stream().filter(s->s.state().equals("READY")).map(KnowledgeSources.View::id).toList();
        var selection=ids.isEmpty()?new KnowledgeSources.Selection(List.of(),List.of()):sources.freeze(project,ids);
        return new Prepared(new Scope(document,project,owner.name(),json.writeValueAsString(selection),json.writeValueAsString(available),Instant.now().toString()),owner.version());
    }
    /** Called only inside the document create transaction; this check performs database reads only. */
    public void save(Prepared prepared) {
        if(prepared==null)return;
        var project=projects.get(prepared.scope().projectId());
        if(project.managed()!=1||project.version()!=prepared.projectVersion())throw PptSupport.conflict("项目资料配置已变化，请重新创建作品");
        if(mapper.insertScope(prepared.scope())!=1)throw PptSupport.conflict("项目来源授权保存失败");
    }
    public View view(String document) {
        var doc=documents.document(document).orElseThrow(()->new NotFoundException("PPT 作品不存在"));
        var scope=mapper.scope(document);
        if(scope.isPresent())return new View(new Project(scope.get().projectId(),scope.get().projectName()),
                json.readValue(scope.get().sourcesJson(),new TypeReference<>(){}),"已开放创建作品时选定项目的可用来源");
        if(doc.projectId()==null)return new View(null,List.of(),"未关联项目，可使用上传资料制作");
        return new View(new Project(doc.projectId(),projects.get(doc.projectId()).name()),List.of(),"此作品尚未开放项目来源，请新建作品并选择项目");
    }
    public KnowledgeSources.Selection selection(String document) {
        var scope=mapper.scope(document).orElseThrow(()->PptSupport.bad("PPT_KNOWLEDGE_NOT_AUTHORIZED","当前作品未开放项目来源，请使用上传资料，或新建作品并选择项目"));
        return json.readValue(scope.selectionJson(),KnowledgeSources.Selection.class);
    }
    public Set<String> evidenceIds(String document) { return new HashSet<>(mapper.evidenceIds(document)); }
    public CursorPage<ProjectChoice> projects(String query,String cursor,Integer requested) {
        query=query==null?"":query.strip();if(query.length()>200)throw PptSupport.bad("PPT_PROJECT_QUERY","项目名称搜索不能超过 200 字");
        var page=PageCursor.decode(cursor);int limit=PageCursor.limit(requested);
        var rows=mapper.projects(query,page==null?"":page.value(),page==null?"":page.id(),limit+1);
        var visible=rows.stream().limit(limit).toList();
        return new CursorPage<>(visible.stream().map(p->new ProjectChoice(p.id(),p.name(),p.description())).toList(),
                rows.size()>limit?new PageCursor(visible.getLast().createdAt(),visible.getLast().id()).encode():null);
    }
}
