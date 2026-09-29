package io.opencode.loopper.service.workflow;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.RoleConfigurationMapper;
import io.opencode.loopper.runtime.WorkflowModelProfile;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.roles.RoleConfigurationService;
import io.opencode.loopper.workflow.*;
import java.io.IOException;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Versioned server-owned preset content. Reading one resolves a specific role version without creating a run. */
@Service
public class WorkflowNodePresets {
    private final List<WorkflowNodePreset> presets;
    private final String digest;
    private final RoleConfigurationMapper mapper;
    private final RoleConfigurationService roles;
    public WorkflowNodePresets(ObjectMapper json,WorkflowEncoding encoding,RoleConfigurationMapper mapper,RoleConfigurationService roles) {
        this.mapper=mapper;this.roles=roles;
        try(var input=new ClassPathResource("workflows/node-presets.json").getInputStream()) {
            byte[] bytes=input.readNBytes(256*1024+1);if(bytes.length>256*1024)throw new IllegalStateException("Workflow preset resource exceeds limit");
            var document=json.readValue(bytes,WorkflowNodePreset.Document.class);
            if(document.schemaVersion()!=1 || document.presets().isEmpty() || document.presets().size()>100)throw new IllegalStateException("Invalid workflow preset document");
            var ids=new HashSet<String>();
            for(var preset:document.presets()) {
                if(preset.id()==null || !preset.id().matches("[a-z][a-z0-9.-]{0,79}") || !ids.add(preset.id()) || preset.version()<1
                        || preset.inputs().size()>64 || preset.node()==null || !preset.node().inputs().isEmpty())throw new IllegalStateException("Invalid workflow preset identity or inputs");
                if(!WorkflowGraphValidator.validate(new WorkflowGraph(1,List.of(preset.node()),List.of(),List.of()),WorkflowGraphValidator.Mode.EXECUTION).isEmpty())
                    throw new IllegalStateException("Invalid workflow preset node: "+preset.id());
                var ports=new HashSet<String>();
                for(var port:preset.inputs())if(port.name()==null || !port.name().matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !ports.add(port.name())
                        || port.kind()==null || port.title()==null || port.title().isBlank())throw new IllegalStateException("Invalid workflow preset port");
            }
            presets=document.presets().stream().sorted(Comparator.comparing(WorkflowNodePreset::id)).toList();
            digest=encoding.digest("WORKFLOW_NODE_PRESETS",null,document);
        }catch(IOException failure){throw new IllegalStateException("Cannot read workflow presets",failure);}
    }
    public record Summary(String id,int version,String title,String description) { }
    public record Detail(String id,int version,String title,String description,List<WorkflowNodePreset.InputPort> inputs,
                         WorkflowGraph.Node node,String roleName,Integer roleRevisionNumber) { }
    public CursorPage<Summary> list(String query,String cursor,Integer limit) {
        String search=query==null?"":query.strip().toLowerCase(Locale.ROOT);
        if(search.length()>200)throw new BadRequestException("WORKFLOW_FILTER_INVALID","搜索内容最多 200 字");
        int count=PageCursor.limit(limit);var after=PageCursor.decode(cursor);
        String scope=digest+":"+search;
        if(after!=null && !after.value().equals(scope))throw new ConflictException("WORKFLOW_PRESET_CATALOG_CHANGED","预设目录或搜索条件已更新，请重新搜索。");
        var found=presets.stream().filter(value->after==null || value.id().compareTo(after.id())>0)
                .filter(value->(value.title()+value.description()).toLowerCase(Locale.ROOT).contains(search)).limit(count+1L).toList();
        var items=found.stream().limit(count).map(value->new Summary(value.id(),value.version(),value.title(),value.description())).toList();
        return new CursorPage<>(items,found.size()>count?new PageCursor(scope,items.getLast().id()).encode():null);
    }
    @Transactional(readOnly=true)
    public Detail get(String id,int version) {
        var preset=presets.stream().filter(value->value.id().equals(id) && value.version()==version).findFirst()
                .orElseThrow(()->new NotFoundException("预设版本不存在，请重新选择。"));
        var node=preset.node();String revisionId=null,name=null;Integer revisionNumber=null;
        if(node.kind()==WorkflowGraph.NodeKind.WORK) {
            var revision=mapper.latest(node.roleId());var definition=mapper.definition(node.roleId());
            if(revision==null || definition==null)throw new ConflictException("WORKFLOW_PRESET_ROLE_UNAVAILABLE","预设角色尚未初始化，请检查角色配置后重试。");
            String slot=WorkflowModelProfile.writeModule(node.moduleId())?WorkflowModelProfile.WRITE_SLOT:WorkflowModelProfile.SLOT;
            var resolved=roles.resolveRevision(revision.revisionId(),slot);
            if(resolved.workInstructions()==null || resolved.workInstructions().isBlank())throw new ConflictException("WORKFLOW_ROLE_INSTRUCTIONS_REQUIRED","预设角色版本缺少专业工作说明，请先调整角色配置。");
            revisionId=revision.revisionId();revisionNumber=revision.revisionNumber();name=definition.displayName();
        }
        var selected=new WorkflowGraph.Node(node.id(),node.title(),node.kind(),node.moduleId(),node.moduleVersion(),node.roleId(),node.task(),node.inputs(),
                node.outputs(),node.outcomes(),node.completion(),node.maxRetries(),node.pauseAfter(),node.parameters(),revisionId);
        return new Detail(preset.id(),preset.version(),preset.title(),preset.description(),preset.inputs(),selected,name,revisionNumber);
    }
}
