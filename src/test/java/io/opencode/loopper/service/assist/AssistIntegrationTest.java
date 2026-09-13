package io.opencode.loopper.service.assist;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=LoopperApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loopper.opencode.mode=fake","loopper.monitor-delay=1h","loopper.data-dir=target/assist-integration"})
class AssistIntegrationTest {
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    private String mcpSession;
    @Autowired Flyway flyway;@Autowired AssistMapper mapper;@Autowired LoopperMapper domain;
    @Autowired ProjectService projects;@Autowired LoopDraftService drafts;@Autowired TaskService tasks;
    @Autowired AssistToolPolicyService policies;@Autowired DatabaseConnectionService databases;@Autowired AssistScopeService scopes;
    @Autowired AssistToolService tools;@Autowired ObjectMapper json;@Autowired InternalMcpRuntimeAccess runtime;
    @MockitoBean DatabaseSecretStore secrets;
    @TempDir Path temp;
    @BeforeEach void reset(){mcpSession=null;flyway.clean();flyway.migrate();when(secrets.save(anyString())).thenAnswer(i->UUID.randomUUID().toString());}
    @Test void policiesFreezeAdmissionAndConnectionVersions() throws Exception {
        var project=projects.create("data",Files.createDirectory(temp.resolve("project")).toString());
        var saved=databases.save(null,new DatabaseConnectionService.Request("内网",new DatabaseConfig(DatabaseConfig.Type.MYSQL,"localhost",3306,"app","reader","","",List.of("app"),Map.of(),10,200),"private",true,false,List.of(project.id()),0));
        assertThat(databases.list(null,50).items()).singleElement().satisfies(row->assertThat(row.projectIds()).containsExactly(project.id()));
        var task=task(project.id(),"report.md");assertThat(mapper.resources("TASK:"+task.id())).contains(saved.id()).doesNotContain("private");
        databases.save(saved.id(),new DatabaseConnectionService.Request("renamed",saved.config(),null,false,true,List.of(),saved.version()));
        assertThat(mapper.resources("TASK:"+task.id())).contains("内网").doesNotContain("renamed");
        assertThatThrownBy(()->databases.save(saved.id(),new DatabaseConnectionService.Request("stale",saved.config(),null,true,false,List.of(),saved.version()))).isInstanceOf(ConflictException.class);
        var first=policies.catalog("", "third-party",List.of("search"),true).getFirst();assertThat(first.enabled()).isTrue();
        assertThat(policies.catalog("","third-party",List.of("search","new_tool"),true)).anySatisfy(p->{if(p.name().equals("new_tool"))assertThat(p.enabled()).isFalse();});
        policies.update("","third-party","search",0,first.globalVersion());policies.update(project.id(),"third-party","search",1,-1);
        assertThat(policies.catalog(project.id(),"third-party",List.of("search"),true).getFirst().enabled()).isTrue();
        assertThatThrownBy(()->policies.update("","@loopper-internal","submit_candidate",0,0)).isInstanceOf(AssistFailure.class);
    }
    @Test void draftDoesNotPersistSecretsAndFiltersUseStableCursor() {
        clearInvocations(secrets);
        var input=new DatabaseConnectionService.Request("业务库",BundledDatabaseDriversTest.input(DatabaseConfig.Type.MYSQL),"private",true,false,List.of(),0);
        var draft=databases.draft(null,input);
        assertThat(draft.config().driverProfile()).isEqualTo("mysql-8.0.33");
        verifyNoInteractions(secrets);assertThat(databases.list(null,50).items()).isEmpty();
        var first=databases.save(null,input);
        databases.save(null,new DatabaseConnectionService.Request("第二个",input.config(),"private",false,false,List.of(),0));
        assertThat(databases.list(null,50,"业务","MYSQL","ENABLED").items()).extracting(DatabaseConnectionService.View::id).containsExactly(first.id());
        var page=databases.list(null,1,"","MYSQL","AVAILABLE");
        assertThat(page.nextCursor()).isNotNull();
        assertThat(databases.list(page.nextCursor(),1,"","MYSQL","AVAILABLE").items()).hasSize(1).noneMatch(x->x.id().equals(page.items().getFirst().id()));
        clearInvocations(secrets);assertThat(databases.draft(first.id(),new DatabaseConnectionService.Request(first.name(),first.config(),null,true,false,List.of(),first.version())).credentialRef()).isNotNull();verifyNoInteractions(secrets);
    }
    @Test void historicalVendorConfigRemainsReadableAndCanBeDisabledWithoutRewritingDriver() {
        String now=Instant.now().toString();var config=AssistSafetyTest.config(DatabaseConfig.Type.GOLDENDB);
        mapper.insertDatabase(new AssistMapper.DatabaseRow("legacy","旧库",json.writeValueAsString(config),"old-secret",1,0,0,now,now));
        var saved=databases.save("legacy",new DatabaseConnectionService.Request("旧库",config,null,false,false,List.of(),0));
        assertThat(saved.config()).isEqualTo(config);assertThat(saved.config().driverProfile()).isNull();
        assertThatThrownBy(()->databases.save("legacy",new DatabaseConnectionService.Request("旧库",config,null,true,false,List.of(),saved.version()))).isInstanceOf(AssistFailure.class);
        assertThat(databases.forTest("legacy").credentialRef()).isEqualTo("old-secret");
    }
    @Test void failedAttemptEvidenceGuidesNextAttemptAndWordRemainsFormallyVerified() throws Exception {
        var project=projects.create("word",Files.createDirectory(temp.resolve("word")).toString());
        var task=task(project.id(),"report.docx");tasks.start(task.id());String initial=bind(task.id());
        var wrong=call("read_document",Map.of("scope",initial,"source","workspace:missing.md"));assertThat(wrong.error()).isTrue();assertThat(tasks.get(task.id()).state()).isEqualTo("RUNNING");
        var firstScope=scopes.authorize(initial,"generate_word");Files.writeString(firstScope.directory().resolve("source.md"),"# 错误标题\n\n初次文档。");
        var firstOutput=call("generate_word",Map.of("scope",initial,"source","workspace:source.md","target","report.docx","idempotencyKey","first-word"));
        assertThat(firstOutput.error()).as(firstOutput.content().toString()).isFalse();
        assertThat(tasks.verify(task.id()).state()).as(tasks.errors(task.id()).toString()).isEqualTo("RETRY_WAIT");
        jdbc.update("UPDATE task_retry_schedule SET due_at=? WHERE task_id=? AND state='SCHEDULED'",Instant.EPOCH.toString(),task.id());tasks.startDueRetries();String next=bind(task.id());
        assertThat(call("get_execution_context",Map.of("scope",next)).error()).isFalse();
        var failure=call("get_failure_evidence",Map.of("scope",next));assertThat(failure.error()).isFalse();assertThat(json.writeValueAsString(failure.content())).contains("FAIL","verification");
        assertThat(call("get_execution_context",Map.of("scope",initial)).error()).isTrue();
        var current=scopes.authorize(next,"generate_word");Files.writeString(current.directory().resolve("source.md"),"# 验收报告\n\n修复后的文档内容。");
        String sha=AssistFiles.sha(Files.readAllBytes(current.directory().resolve("source.md")));
        var input=Map.<String,Object>of("scope",next,"source","workspace:source.md","expectedSha",sha,"target","report.docx","idempotencyKey","word-1");
        var generated=call("generate_word",input);assertThat(generated.error()).as(generated.content().toString()).isFalse();
        assertThat(call("generate_word",input).content().get("sha256")).isEqualTo(generated.content().get("sha256"));
        assertThat(domain.listBinaryArtifacts(task.id())).hasSize(2);
        assertThat(tasks.verify(task.id()).state()).isIn("JUDGING","AWAITING_DECISION");
        assertThat(domain.listVerifications(current.attemptId())).allSatisfy(v->assertThat(v.state()).isEqualTo("PASS"));
    }
    @Test void scopeRejectsCrossProjectAndGenerationChanges() throws Exception {
        var project=projects.create("scope",Files.createDirectory(temp.resolve("scope")).toString());var task=task(project.id(),"report.md");tasks.start(task.id());String grant=bind(task.id());
        assertThat(call("query_database_readonly",Map.of("scope",grant,"connectionId","outside","sql","SELECT 1")).content().get("code")).isEqualTo("DATABASE_SCOPE_DENIED");
        runtime.activate(new InternalMcpCredentialProvider(()->19000).issue());assertThat(call("get_execution_context",Map.of("scope",grant)).content().get("code")).isEqualTo("ASSIST_SCOPE_DENIED");
    }
    @Test void wordReplayProtectsSourceChangesAndUserEdits() throws Exception {
        var project=projects.create("protect",Files.createDirectory(temp.resolve("protect")).toString());var task=task(project.id(),"report.docx");tasks.start(task.id());String grant=bind(task.id());
        var scope=scopes.authorize(grant,"generate_word");Path source=scope.directory().resolve("source.md"),output=scope.directory().resolve("report.docx");Files.writeString(source,"# 验收报告");
        var arguments=Map.<String,Object>of("scope",grant,"source","workspace:source.md","target","report.docx","idempotencyKey","same");
        assertThat(call("generate_word",arguments).error()).isFalse();
        Files.writeString(output,"user modification");assertThat(call("generate_word",arguments).content().get("code")).isEqualTo("WORD_OUTPUT_MODIFIED");assertThat(Files.readString(output)).isEqualTo("user modification");
        Files.writeString(source,"new content");assertThat(call("generate_word",arguments).content().get("code")).isEqualTo("WORD_IDEMPOTENCY_CONFLICT");
    }
    private AssistToolService.Result call(String name,Map<String,Object> arguments) {
        try(var client=java.net.http.HttpClient.newHttpClient()) {
            if(mcpSession==null) {
                var initialized=send(client,"initialize",Map.of("protocolVersion","2025-03-26","capabilities",Map.of(),"clientInfo",Map.of("name","offline-acceptance","version","1")));
                mcpSession=initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
            }
            var response=send(client,"tools/call",Map.of("name",name,"arguments",arguments));assertThat(response.statusCode()).isEqualTo(200);
            String body=response.body();if(!body.stripLeading().startsWith("{"))body=body.lines().filter(line->line.startsWith("data:")).map(line->line.substring(5).strip()).filter(line->line.contains("result")).findFirst().orElseThrow();
            var result=json.readTree(body).path("result");
            return new AssistToolService.Result(json.convertValue(result.path("structuredContent"),new tools.jackson.core.type.TypeReference<>(){}),result.path("isError").asBoolean());
        }catch(Exception failure){throw new AssertionError("MCP wire call failed",failure);}
    }
    private java.net.http.HttpResponse<String> send(java.net.http.HttpClient client,String method,Map<String,Object> params) throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+AssistToolCatalog.ENDPOINT))
                .timeout(java.time.Duration.ofSeconds(15)).header("Authorization","Bearer "+runtime.current().orElseThrow().bearerToken())
                .header("Content-Type","application/json").header("Accept","application/json, text/event-stream");
        if(mcpSession!=null)request.header("Mcp-Session-Id",mcpSession);
        return client.send(request.POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("jsonrpc","2.0","id",1,"method",method,"params",params)))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }
    private TaskRow task(String project,String output) {
        var verifier=output.endsWith(".docx")?json.readValue("{\"type\":\"DOCUMENT_STRUCTURE\",\"path\":\"report.docx\",\"documentAssertions\":[{\"type\":\"HEADING_EXISTS\",\"value\":\"验收报告\",\"headingLevel\":1}]}",LoopSpec.VerifierSpec.class):new LoopSpec.VerifierSpec("FILE_EXISTS",null,output,null,null,null,null);
        var spec=new LoopSpec("v1",project,"生成并验证报告",null,List.of(new LoopSpec.StageSpec("生成报告",List.of(output,"source.md"),List.of(),List.of(output),List.of(verifier))),null,null,null,null);
        return drafts.confirm(drafts.create(spec).id(),"辅助能力验收");
    }
    private String bind(String task) {
        var session=domain.activeSessions(task).getFirst();var credentials=runtime.current().orElseGet(()->new InternalMcpCredentialProvider(()->19000).issue());runtime.activate(credentials);
        mapper.insertSession(new AssistMapper.Session(session.externalSessionId(),credentials.generation(),tasks.get(task).worktreePath(),"IMPLEMENTATION","[]",json.writeValueAsString(AssistToolCatalog.allowed("IMPLEMENTATION")),Instant.now().toString()));
        String grant=scopes.grant(session.externalSessionId());assertThat(grant).startsWith("lpa_");return grant;
    }
}
