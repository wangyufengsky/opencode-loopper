package io.opencode.loopper.service.assist;

import io.opencode.loopper.persistence.AssistMapper;
import io.opencode.loopper.service.ConflictException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssistToolPolicyService {
    private final AssistMapper mapper;
    public AssistToolPolicyService(AssistMapper mapper) {this.mapper=mapper;}
    public record View(String name,boolean configurable,boolean writes,boolean globalEnabled,String projectOverride,
                       boolean enabled,String source,long globalVersion,long projectVersion,String description) {
        public View described(String text) {return new View(name,configurable,writes,globalEnabled,projectOverride,enabled,source,globalVersion,projectVersion,text);}
    }
    @Transactional
    public List<View> catalog(String project,String server,List<String> tools,boolean complete) {
        boolean protectedServer=server.equals("@loopper-internal");
        String now=Instant.now().toString();
        if(complete && !protectedServer) {
            boolean first=mapper.catalogRegistered(server)==0;
            for(String tool:tools) {validate(server,tool);mapper.insertPolicy(new AssistMapper.Policy("",server,tool,first?1:0,0,now));}
            mapper.registerCatalog(server,now);
        }
        var policies=mapper.policies(project,server);
        return tools.stream().map(tool->{
            var global=policies.stream().filter(p->p.scope().isEmpty()&&p.toolName().equals(tool)).findFirst().orElse(null);
            var local=project.isEmpty()?null:policies.stream().filter(p->p.scope().equals(project)&&p.toolName().equals(tool)).findFirst().orElse(null);
            boolean defaultOn=protectedServer || global!=null&&global.enabled()==1;
            boolean overridden=local!=null&&local.enabled()!=-1;
            boolean writes=AssistToolCatalog.tools().stream().anyMatch(t->server.equals(AssistToolCatalog.SERVER)&&t.name().equals(tool)&&t.writes());
            return new View(tool,!protectedServer&&complete,writes,defaultOn,overridden?(local.enabled()==1?"ENABLED":"DISABLED"):"INHERIT",
                    protectedServer || (overridden?local.enabled()==1:defaultOn),protectedServer?"SYSTEM":overridden?"PROJECT":"GLOBAL",
                    global==null?-1:global.version(),local==null?-1:local.version(), "");
        }).toList();
    }
    @Transactional
    public void update(String scope,String server,String tool,int enabled,long version) {
        validate(server,tool);
        if(server.equals("@loopper-internal"))throw new AssistFailure("MCP_TOOL_REQUIRED","系统流程必需工具不能关闭");
        if(enabled< -1 || enabled>1 || scope.isEmpty()&&enabled==-1)throw new AssistFailure("MCP_POLICY_INVALID","请选择有效的工具状态");
        if(mapper.policies("",server).stream().noneMatch(p->p.toolName().equals(tool)))
            throw new AssistFailure("MCP_TOOL_UNKNOWN","请先读取完整工具清单后再修改");
        var policy=new AssistMapper.Policy(scope,server,tool,enabled,Math.max(0,version),Instant.now().toString());
        int changed=version<0?mapper.insertPolicy(policy):mapper.updatePolicy(policy);
        if(changed!=1)throw new ConflictException("MCP_POLICY_CONFLICT","工具配置已改变，请刷新后重试");
        mapper.audit(UUID.randomUUID().toString(),scope,server,tool,Integer.toString(enabled),Instant.now().toString());
    }
    private static void validate(String server,String tool) {
        if(server==null || server.length()>160 || tool==null || !tool.matches("[a-zA-Z0-9_-]{1,128}"))
            throw new AssistFailure("MCP_TOOL_ID_INVALID","工具名称无法精确授权，请检查服务配置");
    }
}
