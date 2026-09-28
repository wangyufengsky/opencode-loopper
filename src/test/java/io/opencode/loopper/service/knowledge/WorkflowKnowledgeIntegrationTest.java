package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.assist.*;
import io.opencode.loopper.service.roles.RoleConfigurationService;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=LoopperApplication.class, properties={"loopper.opencode.mode=fake", "loopper.monitor-delay=1h", "loopper.data-dir=target/workflow-knowledge-test"})
class WorkflowKnowledgeIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired ProjectService projects;
    @Autowired LoopDraftService drafts;
    @Autowired TaskService tasks;
    @Autowired LoopperMapper domain;
    @Autowired AssistMapper assist;
    @Autowired WorkflowKnowledgeMapper bindings;
    @Autowired KnowledgeSources sources;
    @Autowired AssistToolService tools;
    @Autowired AssistScopeService scopes;
    @Autowired RoleConfigurationService roles;
    @Autowired InternalMcpRuntimeAccess runtime;
    @Autowired ObjectMapper json;
    @TempDir Path temp;
    @BeforeEach void reset() throws Exception { flyway.clean(); flyway.migrate(); temp = temp.toRealPath(); }

    @Test void businessRoleSearchesFrozenSourcesAndKeepsOriginalEvidenceAfterFileChanges() throws Exception {
        Path root=Files.createDirectory(temp.resolve("project"));
        Files.writeString(root.resolve("rules.md"), "# 业务约定\n退款申请需要核对订单状态。\n");
        var project=projects.create("取证项目",root.toString());
        var task=task(project.id());
        assertThat(bindings.binding("TASK",task.id())).isNotNull();
        var late=sources.addDirectory(project.id(),Files.createDirectory(temp.resolve("late")).toString());
        tasks.start(task.id()); String grant=bind(task.id());
        var found=tools.call("search_project_knowledge",Map.of("scope",grant,"query","退款申请"));
        assertThat(found.error()).as(found.content().toString()).isFalse();
        assertThat(json.writeValueAsString(found.content())).contains("rules.md");
        var read=tools.call("read_knowledge_source",Map.of("scope",grant,"sourceId","documents","path","rules.md","section",0));
        assertThat(read.error()).as(read.content().toString()).isFalse();
        assertThat(read.content()).containsKeys("citationId","sha256","collectedAt");
        String citation=read.content().get("citationId").toString();
        var directory=tools.call("list_knowledge_evidence",Map.of("scope",grant));
        assertThat(directory.error()).as(directory.content().toString()).isFalse(); assertThat(directory.content().toString()).contains(citation);
        Files.writeString(root.resolve("rules.md"),"# 新约定\n资料已变更。\n");
        assertThat(tools.call("read_knowledge_source",Map.of("scope",grant,"sourceId","documents","path","rules.md","section",0,"expectedSha",read.content().get("sha256"))).error()).isTrue();
        var saved=tools.call("read_knowledge_evidence",Map.of("scope",grant,"reference",citation));
        assertThat(saved.error()).isFalse();
        assertThat(saved.content().get("content").toString()).contains("退款申请").doesNotContain("新约定");
        assertThat(tools.call("browse_knowledge_source",Map.of("scope",grant,"sourceId",late.id())).error()).isTrue();
        assertThat(tools.call("read_knowledge_source",Map.of("scope",grant,"sourceId","code","path","../outside.txt")).error()).isTrue();
        Files.writeString(root.resolve(".env"),"SYNTHETIC_ONLY=true");
        assertThat(tools.call("read_knowledge_source",Map.of("scope",grant,"sourceId","code","path",".env")).error()).isTrue();
        var otherProject=projects.create("另一项目",Files.createDirectory(temp.resolve("other")).toString());
        var other=task(otherProject.id());tasks.start(other.id());String otherGrant=bind(other.id());
        assertThat(tools.call("read_knowledge_evidence",Map.of("scope",otherGrant,"reference",citation)).error()).isTrue();
        tasks.cancel(task.id());
        assertThat(tools.call("list_knowledge_sources",Map.of("scope",grant)).error()).isTrue();
    }

    @Test void childInheritsOriginalSourcesAndLegacyParentDoesNotAcquireKnowledge() throws Exception {
        var project=projects.create("继承项目",Files.createDirectory(temp.resolve("inherit")).toString());
        var task=task(project.id());
        var parent=new RoleConfigurationService.OwnerRef("TASK",task.id());
        var child=new RoleConfigurationService.OwnerRef("LOOP_DRAFT",UUID.randomUUID().toString());
        sources.addDirectory(project.id(),Files.createDirectory(temp.resolve("added")).toString());
        roles.freezeOwner(child,parent);
        assertThat(bindings.binding(child.type(),child.id()).sourcesJson()).isEqualTo(bindings.binding(parent.type(),parent.id()).sourcesJson());
        var legacyChild=new RoleConfigurationService.OwnerRef("TASK",UUID.randomUUID().toString());
        roles.freezeOwner(legacyChild,new RoleConfigurationService.OwnerRef("TASK","pre-upgrade"));
        assertThat(bindings.binding(legacyChild.type(),legacyChild.id())).isNull();
    }
    private TaskRow task(String project) {
        var spec=new LoopSpec("v1",project,"生成报告",null,List.of(new LoopSpec.StageSpec("报告",List.of("report.md"),List.of(),List.of("report.md"),
                List.of(new LoopSpec.VerifierSpec("FILE_EXISTS",null,"report.md",null,null,null,null)))),null,null,null,null);
        return drafts.confirm(drafts.create(spec).id(),"取证验收");
    }
    private String bind(String task) {
        var session=domain.activeSessions(task).getFirst();
        var credentials=runtime.current().orElseGet(()->new InternalMcpCredentialProvider(()->19000).issue());runtime.activate(credentials);
        assist.insertSession(new AssistMapper.Session(session.externalSessionId(),credentials.generation(),tasks.get(task).worktreePath(),
                "IMPLEMENTATION","[]",json.writeValueAsString(AssistToolCatalog.allowed("IMPLEMENTATION")),Instant.now().toString()));
        String grant=scopes.grant(session.externalSessionId());assertThat(grant).startsWith("lpa_");return grant;
    }
}
