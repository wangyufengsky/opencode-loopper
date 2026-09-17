package io.opencode.loopper.service.assist;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.InternalMcpRuntimeAccess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Signed, generation-bound grants are injected only at transport time, never persisted with prompts. */
@Service
public class AssistScopeService {
    private final AssistMapper mapper;
    private final LoopperMapper domain;
    private final InternalMcpRuntimeAccess runtime;
    private final DatabaseConnectionService databases;
    private final ObjectMapper json;
    private final BatchAssistConfigService batch;
    private final KnowledgeMapper knowledge;
    public AssistScopeService(AssistMapper mapper,LoopperMapper domain,InternalMcpRuntimeAccess runtime,
                              DatabaseConnectionService databases,ObjectMapper json,BatchAssistConfigService batch,KnowledgeMapper knowledge) {
        this.mapper=mapper;this.domain=domain;this.runtime=runtime;this.databases=databases;this.json=json;this.batch=batch;this.knowledge=knowledge;
    }
    public record Scope(String externalSessionId,String ownerKey,String projectId,String taskId,String stageId,
                        String attemptId,String designerId,String profile,Path directory,List<String> tools,
                        List<DatabaseConnectionService.Bound> connections) { }
    public String grant(String session) {
        AssistMapper.Session snapshot=mapper.session(session); if(snapshot==null)return "";
        if(json.readTree(snapshot.toolsJson()).isEmpty())return "";
        try { resolve(session); } catch(AssistFailure absent) {return "";}
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(session.getBytes(StandardCharsets.UTF_8));
        return "lpa_"+encoded+"."+signature(encoded);
    }
    public Scope authorize(String grant,String tool) {
        if(grant==null || grant.length()>1000 || !grant.startsWith("lpa_"))throw denied();
        grant=grant.substring(4);
        String[] parts=grant.split("\\."); if(parts.length!=2 || !MessageDigest.isEqual(signature(parts[0]).getBytes(StandardCharsets.UTF_8),parts[1].getBytes(StandardCharsets.UTF_8)))throw denied();
        String session;try{session=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);}catch(Exception e){throw denied();}
        Scope scope=resolve(session); if(!scope.tools().contains(tool))throw denied(); return scope;
    }
    public void requireDeclaredCapabilities(String session) {
        var snapshot=mapper.session(session);if(snapshot==null||!"IMPLEMENTATION".equals(snapshot.profile()))return;
        var owner=mapper.executionOwner(session);if(owner==null)return;
        var stage=domain.findStage(owner.stageId()).orElseThrow(AssistScopeService::denied);
        boolean word=json.readTree(stage.deliverablesJson()).valueStream().anyMatch(n->n.asText().toLowerCase(Locale.ROOT).contains(".docx"));
        if(word && !json.readTree(snapshot.toolsJson()).valueStream().anyMatch(n->n.asText().equals("generate_word")))
            throw new io.opencode.loopper.domain.SessionFailure("ASSIST_REQUIRED_TOOL_DISABLED","阶段要求 DOCX，但 Word 工具未获授权；请启用工具后创建新的尝试");
    }
    public Scope resolve(String session) {
        var snapshot=mapper.session(session);var current=runtime.current().orElseThrow(AssistScopeService::denied);
        if(snapshot==null || !snapshot.generation().equals(current.generation()))throw denied();
        if (snapshot.profile().startsWith("KNOWLEDGE_")) return knowledgeScope(session, snapshot);
        AssistMapper.Owner owner;
        if(snapshot.profile().equals("IMPLEMENTATION") || snapshot.profile().startsWith("TEMPLATE_ANALYSIS"))owner=mapper.executionOwner(session);
        else if(snapshot.profile().contains("CANDIDATE"))owner=mapper.candidateOwner(session);
        else if(snapshot.profile().contains("JUDGE"))owner=mapper.judgeOwner(session);
        else {owner=mapper.designerOwner(session);if(owner==null)owner=mapper.designerRoleOwner(session);}
        if(owner==null || owner.projectId()==null)throw denied();
        String ownerIdentity=json.writeValueAsString(Arrays.asList(owner.projectId(),owner.taskId(),owner.stageId(),owner.attemptId(),owner.designerId()));
        mapper.bindScopeOwner(session,ownerIdentity);if(!ownerIdentity.equals(mapper.scopeOwner(session)))throw denied();
        var project=domain.findProject(owner.projectId()).orElseThrow(AssistScopeService::denied);
        String expected=owner.taskId()==null?project.rootPath():domain.findTask(owner.taskId()).orElseThrow(AssistScopeService::denied).worktreePath();
        try {if(expected==null || !Path.of(expected).toRealPath().equals(Path.of(snapshot.directory()).toRealPath()))throw denied();}
        catch(java.io.IOException e){throw denied();}
        String key=owner.taskId()!=null?"TASK:"+owner.taskId():owner.designerId()!=null?"DESIGNER:"+owner.designerId():"PROJECT:"+owner.projectId()+":"+session;
        String resources=mapper.resources(key);
        if(resources==null) {
            mapper.bindResources(key,json.writeValueAsString(databases.forProject(owner.projectId())),Instant.now().toString());resources=mapper.resources(key);
        }
        batch.frozen(key,owner.projectId());
        return new Scope(session,key,owner.projectId(),owner.taskId(),owner.stageId(),owner.attemptId(),owner.designerId(),snapshot.profile(),
                Path.of(snapshot.directory()),json.readValue(snapshot.toolsJson(),new TypeReference<>(){}),json.readValue(resources,new TypeReference<>(){}));
    }
    private Scope knowledgeScope(String session, AssistMapper.Session snapshot) {
        var conversation = knowledge.remote(session).filter(c -> c.state().equals("RUNNING")).orElseThrow(AssistScopeService::denied);
        if (knowledge.active(conversation.id()).filter(t -> Set.of("SENDING", "UNKNOWN", "RUNNING").contains(t.state())).isEmpty()) throw denied();
        var project = domain.findProject(conversation.projectId()).orElseThrow(AssistScopeService::denied);
        try {
            var expected = Path.of(conversation.rootPath()).toRealPath();
            if (!expected.equals(Path.of(snapshot.directory()).toRealPath()) || !expected.equals(Path.of(project.rootPath()).toRealPath())) throw denied();
        } catch (java.io.IOException failure) { throw denied(); }
        String key = "KNOWLEDGE:" + conversation.id();
        mapper.bindScopeOwner(session, key); if (!key.equals(mapper.scopeOwner(session))) throw denied();
        return new Scope(session, key, conversation.projectId(), null, null, null, null, snapshot.profile(), Path.of(snapshot.directory()),
                json.readValue(snapshot.toolsJson(), new TypeReference<>() {}), json.readValue(conversation.connectionsJson(), new TypeReference<>() {}));
    }
    private String signature(String data) {
        try {var current=runtime.current().orElseThrow(AssistScopeService::denied); Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(current.bearerToken().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((current.generation()+":"+data).getBytes(StandardCharsets.UTF_8)));
        }catch(Exception e){throw denied();}
    }
    private static AssistFailure denied(){return new AssistFailure("ASSIST_SCOPE_DENIED","辅助工具作用域失效或不属于当前会话，请由 Loopper 重新建立有效会话","REAUTHORIZE");}
}
