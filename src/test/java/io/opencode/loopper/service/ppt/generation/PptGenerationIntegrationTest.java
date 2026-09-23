package io.opencode.loopper.service.ppt.generation;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.PptAgentRows.Run;
import io.opencode.loopper.persistence.PptGenerationRows.Generation;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.ppt.*;
import io.opencode.loopper.service.ppt.agent.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.opencode.model=fake/model",
        "loopper.scheduling.enabled=false","loopper.startup-recovery.enabled=false"})
class PptGenerationIntegrationTest {
    private static final Path DATA=data();
    static Path data(){try{return Files.createTempDirectory("ppt-generation-it-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("loopper.data-dir",DATA::toString);
        r.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("test.db")+"?foreign_keys=on&journal_mode=WAL&transaction_mode=IMMEDIATE");
    }
    @Autowired Flyway flyway;
    @Autowired PptGenerationService service;
    @Autowired PptGenerationCoordinator coordinator;
    @Autowired PptGenerationPersistence persistence;
    @Autowired PptGenerationMapper generations;
    @Autowired PptAgentService agent;
    @Autowired PptDiscussionTranscript discussion;
    @Autowired PptAgentPersistence agentPersistence;
    @Autowired PptAgentMapper agents;
    @Autowired PptAgentTools tools;
    @Autowired PptDocuments documents;
    @Autowired PptAgentWorkspaceAdapter workspace;
    @Autowired PptJobs jobs;
    @Autowired PptJobPersistence jobPersistence;
    @Autowired PptMapper mapper;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean PptAgentCoordinator agentScheduler;
    @MockitoBean io.opencode.loopper.runtime.PptRuntimeSupport runtime;
    @BeforeEach void reset(){flyway.clean();flyway.migrate();}
    String key(){return UUID.randomUUID().toString();}
    String document(){return documents.create(new PptDocuments.Create(key(),"自动生成验收",null,"fake/model")).id();}
    JsonNode node(String value){return json.readTree(value);}
    Generation start(String document){return persistence.require(service.generate(document,new PptGenerationService.Generate(key(),documents.get(document).revision(),"生成两页中文项目汇报")).id());}
    Run running(Generation generation) {
        if(generation.runId()==null){coordinator.tick(generation.id());generation=persistence.require(generation.id());}
        var run=agents.run(generation.runId()).orElseThrow();
        if(run.state().equals("PREPARED")) {
            agentPersistence.dispatch(run,"{}",PptSupport.hash("fixture"));run=agents.run(run.id()).orElseThrow();
            agentPersistence.state(run,PptAgentState.RUNNING,"");
        }
        return agents.run(run.id()).orElseThrow();
    }
    void receipt(Run run,String tool,JsonNode result) {
        agents.insertReceipt(new PptAgentRows.Receipt(run.id(),key(),tool,PptSupport.hash("fixture"),json.writeValueAsString(result),Instant.now().toString()));
    }
    void complete(Run run){agentPersistence.proven(agents.run(run.id()).orElseThrow(),PptAgentState.COMPLETED,"REMOTE_TERMINAL","本轮完成");}
    Run completeDiscussion(PptAgentService.Message message,String answer) {
        var run=agents.run(message.id()).orElseThrow();agentPersistence.dispatch(run,"{}",PptSupport.hash("discussion-"+run.id()));
        run=agents.run(run.id()).orElseThrow();agentPersistence.state(run,PptAgentState.RUNNING,"");
        run=agents.run(run.id()).orElseThrow();agents.answer(run.id(),run.version(),answer,Instant.now().toString());
        run=agents.run(run.id()).orElseThrow();agentPersistence.proven(run,PptAgentState.COMPLETED,"REMOTE_TERMINAL","讨论回复已完成");
        return agents.run(run.id()).orElseThrow();
    }
    JsonNode plan(){return node("""
        {"brief":{"purpose":"项目汇报","audience":"团队","pageCount":2},
         "directions":[{"id":"result","title":"结果驱动"}],"selectedDirectionId":"result","theme":"business",
         "narrative":{"story":"先结果再行动"},"visualRules":{"style":"商务"},"assets":{"requirements":"无需外部素材"},
         "delivery":{"fileName":"自动汇报.pptx","includeNotes":true},
         "slides":[{"id":"p1","title":"结论","message":"项目进展正常","content":"已完成阶段工作","sourceIds":[],"notes":"介绍项目"},
                   {"id":"p2","title":"下一步","message":"继续执行","content":"明确后续行动","sourceIds":[],"notes":"介绍行动"}]}
        """);}
    Run savePlan(Generation row) {
        var run=running(row);confirmedRequirements(run);var plan=plan();PptAutomaticPlan.validate(plan);
        var result=documents.savePlan(row.documentId(),new PptDocuments.PlanEdit(key(),run.sourceRevision(),plan),true,()->persistence.validateRun(run));
        receipt(run,"ppt_submit_plan",result);complete(run);return agents.run(run.id()).orElseThrow();
    }
    void confirmedRequirements(Run run) {
        if(!json.readTree(run.contextJson()).has("requirementsProtocol"))return;
        // Durable answered-question fixture: actual UI reply/CAS paths are covered by PptAgentIntegrationTest.
        for(String kind:List.of("CLARIFICATION","REQUIREMENTS_CONFIRMATION")) {
            String question=key();
            agents.insertQuestion(new PptAgentRows.Question(question,run.id(),run.documentId(),"内容与设计要求","[]","PENDING",null,null,null,
                    Instant.now().toString(),0,kind,null));
            agents.reply(question,0,"部门领导、两页、商务风",key(),"fixture",kind.equals("REQUIREMENTS_CONFIRMATION")?true:null);
        }
    }
    JsonNode page(String id){return node("""
        {"op":"create_slide","slide":{"id":"%s","title":"中文标题","notes":"讲稿内容","elements":[
          {"id":"t-%s","type":"text","x":50,"y":50,"width":750,"height":100,"fontSize":28,"text":"已完成的工作与下一步"}]}}
        """.formatted(id,id));}
    Generation produce(Generation initial) {
        savePlan(initial);coordinator.tick(initial.id());var row=persistence.require(initial.id());
        assertThat(row.state()).isEqualTo("PRODUCING");assertThat(documents.get(row.documentId()).phase()).isEqualTo("PRODUCING");
        var run=running(row);
        var result=documents.edit(row.documentId(),new PptDocuments.Edit(key(),run.sourceRevision(),List.of(page("p1"),page("p2"))),true,()->persistence.validateRun(run));
        receipt(run,"ppt_apply_operations",result);complete(run);coordinator.tick(row.id());return persistence.require(row.id());
    }
    Generation outputs(Generation row) {
        for(int i=0;i<5&&!row.state().equals("COMPLETED");i++) {
            coordinator.tick(row.id());row=persistence.require(row.id());
            if(row.jobId()!=null)jobs.execute(jobs.require(row.documentId(),row.jobId()));
        }
        coordinator.tick(row.id());return persistence.require(row.id());
    }
    String legacyReviewedDocument() {
        String doc=document();documents.savePlan(doc,new PptDocuments.PlanEdit(key(),0,plan()),false,()->{});
        for(String action:List.of("finish-planning","confirm-direction","start-production"))
            documents.action(doc,action,new PptDocuments.Action(key(),1,null,null,false),()->{});
        documents.edit(doc,new PptDocuments.Edit(key(),1,List.of(page("p1"),page("p2"))),false,()->{});
        documents.action(doc,"finish-production",new PptDocuments.Action(key(),2,null,null,false),()->{});
        assertThat(generations.latest(doc)).isEmpty();return doc;
    }
    @Test void oneAuthorizationPlansProducesAndCompletesOnlyAfterSameRevisionPreviewAndExport() {
        String doc=document();String key=key();var request=new PptGenerationService.Generate(key,0,"制作两页项目汇报");
        var view=service.generate(doc,request);assertThat(service.generate(doc,request).id()).isEqualTo(view.id());
        var initial=persistence.require(view.id());assertThat(agents.run(initial.runId()).orElseThrow().contextJson()).contains("generationAuthorization","CREATE");
        var producing=produce(initial);assertThat(producing.state()).isEqualTo("PREVIEW");
        assertThat(documents.get(doc).phase()).isEqualTo("REVIEW");assertThat(mapper.jobs(doc)).isEmpty();
        var done=outputs(producing);assertThat(done.state()).isEqualTo("COMPLETED");
        assertThat(done.previewJobId()).isNotNull();assertThat(documents.get(doc).phase()).isEqualTo("EXPORTED");
        assertThat(jobs.get(doc,done.previewJobId()).revision()).isEqualTo(done.outputRevision());
        assertThat(jobs.get(doc,done.jobId()).revision()).isEqualTo(done.outputRevision());
        assertThat(jobs.get(doc,done.previewJobId()).artifacts()).hasSize(2);
        var previews=mapper.artifacts(doc,done.previewJobId());
        assertThat(previews.stream().map(PptRows.Artifact::sha256).distinct().count()).isEqualTo(1);
        assertThat(previews.stream().map(PptRows.Artifact::storageKey).distinct().count()).isEqualTo(2);
        assertThat(jobs.download(doc,jobs.get(doc,done.jobId()).artifacts().getFirst().id())).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task",Integer.class)).isZero();
    }
    @Test void crashBetweenDirectionAndDesignContinuesCommittedPlanWithoutAnotherModelRun() {
        var row=start(document());var run=savePlan(row);row=persistence.freeze(persistence.require(row.id()),run);
        final var frozen=row;
        documents.action(row.documentId(),"finish-planning",new PptDocuments.Action(row.agentKey()+"_finish-planning",row.outputRevision(),null,null,false),()->persistence.guard(frozen));
        assertThat(documents.get(row.documentId()).phase()).isEqualTo("DIRECTION");
        coordinator.tick(row.id());assertThat(persistence.require(row.id()).state()).isEqualTo("PRODUCING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ppt_agent_run",Integer.class)).isEqualTo(1);
        coordinator.tick(row.id());coordinator.tick(row.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ppt_agent_run",Integer.class)).isEqualTo(2);
    }
    @Test void stopImmediatelyBeforeContinuationCancelsAuthorityAndNoProductionIsDispatched() {
        var row=start(document());var run=savePlan(row);row=persistence.freeze(persistence.require(row.id()),run);final var frozen=row;
        agent.stop(row.documentId());
        assertThatThrownBy(()->documents.action(frozen.documentId(),"finish-planning",
                new PptDocuments.Action(key(),frozen.outputRevision(),null,null),()->persistence.completed(frozen,run))).isInstanceOf(ConflictException.class);
        coordinator.tick(row.id());coordinator.tick(row.id());
        assertThat(persistence.require(row.id()).state()).isEqualTo("STOPPED");
        assertThat(documents.get(row.documentId()).phase()).isEqualTo("BRIEFING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ppt_agent_run",Integer.class)).isEqualTo(1);
    }
    @Test void manualWritesAreBlockedDuringAutomaticGenerationAndStaleResumeCannotOverrideAfterStop() {
        var row=start(document());savePlan(row);
        assertThatThrownBy(()->documents.savePlan(row.documentId(),new PptDocuments.PlanEdit(key(),1,plan()),false,()->{})).isInstanceOf(ConflictException.class);
        agent.stop(row.documentId());coordinator.tick(row.id());
        documents.savePlan(row.documentId(),new PptDocuments.PlanEdit(key(),1,plan()),false,()->{});
        coordinator.tick(row.id());assertThat(persistence.require(row.id()).state()).isEqualTo("STOPPED");
        assertThat(documents.get(row.documentId()).revision()).isEqualTo(2);
        assertThat(documents.get(row.documentId()).phase()).isEqualTo("BRIEFING");
        assertThatThrownBy(()->service.resume(row.documentId(),new PptGenerationService.Resume(key(),1))).isInstanceOf(ConflictException.class);
    }
    @Test void outputInProgressStillRejectsManualDraftChangesUntilStopped() {
        var row=produce(start(document()));coordinator.tick(row.id());row=persistence.require(row.id());
        jobs.execute(jobs.require(row.documentId(),row.jobId()));coordinator.tick(row.id());coordinator.tick(row.id());row=persistence.require(row.id());
        jobs.execute(jobs.require(row.documentId(),row.jobId()));
        final var frozen=row;
        assertThatThrownBy(()->documents.edit(frozen.documentId(),new PptDocuments.Edit(key(),frozen.outputRevision(),List.of(node("{\"op\":\"update_slide\",\"slideId\":\"p1\",\"patch\":{\"title\":\"人工新标题\"}}"))),false,()->{})).isInstanceOf(ConflictException.class);
        coordinator.tick(row.id());assertThat(persistence.require(row.id()).state()).isEqualTo("COMPLETED");
        assertThat(documents.deck(row.documentId(),null).slides().getFirst().title()).isEqualTo("中文标题");
    }
    @Test void failedExportResumeReusesSuccessfulPreviewAndTheSameDurableJob() {
        var row=produce(start(document()));coordinator.tick(row.id());row=persistence.require(row.id());
        String preview=row.jobId();jobs.execute(jobs.require(row.documentId(),preview));coordinator.tick(row.id());coordinator.tick(row.id());row=persistence.require(row.id());
        String export=row.jobId();var job=jobs.require(row.documentId(),export);job=jobPersistence.state(job,"RUNNING",0,"");jobPersistence.state(job,"FAILED",0,"临时错误");
        coordinator.tick(row.id());assertThat(persistence.require(row.id()).state()).isEqualTo("FAILED");
        var input=new PptGenerationService.Resume(key(),documents.get(row.documentId()).revision());
        var resumed=service.resume(row.documentId(),input);assertThat(service.resume(row.documentId(),input).id()).isEqualTo(resumed.id());
        var fresh=persistence.require(row.id());assertThat(fresh.step()).isEqualTo("EXPORT");assertThat(fresh.previewJobId()).isEqualTo(preview);assertThat(fresh.jobId()).isEqualTo(export);
        assertThat(mapper.jobs(row.documentId())).hasSize(2);assertThat(outputs(fresh).state()).isEqualTo("COMPLETED");
    }
    @Test void revisionMessageKeepsFrozenScopeReexportsAndDoesNotReplaceOldArtifact() {
        var original=outputs(produce(start(document())));String doc=original.documentId();
        var oldArtifact=jobs.get(doc,original.jobId()).artifacts().getFirst();String oldHash=PptSupport.hash(jobs.download(doc,oldArtifact.id()));
        var scope=node("{\"kind\":\"SLIDE\",\"slideId\":\"p1\"}");
        var input=new PptAgentService.Send(key(),"把第一页标题改为进展总览",documents.get(doc).revision(),scope);
        var message=service.send(doc,input);assertThat(service.send(doc,input).id()).isEqualTo(message.id());
        var row=generations.latest(doc).orElseThrow();assertThat(row.mode()).isEqualTo("REVISE");
        var run=running(row);var before=documents.deck(doc,null).slides().get(1);
        var args=json.createObjectNode().put("idempotencyKey",key()).put("expectedRevision",run.sourceRevision());args.set("agentScope",scope);
        args.set("operations",node("[{\"op\":\"update_slide\",\"slideId\":\"p2\",\"patch\":{\"title\":\"越界修改\"}}]"));
        assertThatThrownBy(()->workspace.invoke(doc,"ppt_apply_operations",args,()->persistence.validateRun(run))).hasMessageContaining("超出");
        args.set("operations",node("[{\"op\":\"update_slide\",\"slideId\":\"p1\",\"patch\":{\"title\":\"进展总览\"}}]"));
        JsonNode result=json.valueToTree(workspace.invoke(doc,"ppt_apply_operations",args,()->persistence.validateRun(run)));
        receipt(run,"ppt_apply_operations",result);complete(run);coordinator.tick(row.id());var done=outputs(persistence.require(row.id()));
        assertThat(done.state()).isEqualTo("COMPLETED");assertThat(documents.deck(doc,null).slides().get(1)).isEqualTo(before);
        assertThat(PptSupport.hash(jobs.download(doc,oldArtifact.id()))).isEqualTo(oldHash);
        assertThat(service.send(doc,input).id()).isEqualTo(message.id());assertThat(generations.latest(doc).orElseThrow().id()).isEqualTo(row.id());
    }
    @Test void stopUnknownBlocksResumeUntilIndependentAgentProofThenOneResumeKeyCreatesOnlyOneNewRun() {
        var row=start(document());running(row);agent.stop(row.documentId());coordinator.tick(row.id());
        assertThat(persistence.require(row.id()).state()).isEqualTo("STOPPING");
        assertThatThrownBy(()->service.resume(row.documentId(),new PptGenerationService.Resume(key(),0))).isInstanceOf(ConflictException.class);
        var stopped=agents.active(row.documentId()).orElseThrow();agentPersistence.proven(stopped,PptAgentState.STOPPED,"OWNED_PROCESS_EXITED:fixture","已停止");
        coordinator.tick(row.id());var request=new PptGenerationService.Resume(key(),0);
        service.resume(row.documentId(),request);service.resume(row.documentId(),request);coordinator.tick(row.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ppt_agent_run",Integer.class)).isEqualTo(2);
        assertThat(persistence.require(row.id()).attempt()).isEqualTo(1);
    }
    @Test void oldManualDraftCanEnterAutomaticModeAndUnrelatedManualRunsKeepTheirPermissionContract() {
        String doc=document();documents.savePlan(doc,new PptDocuments.PlanEdit(key(),0,plan()),false,()->{});
        documents.action(doc,"finish-planning",new PptDocuments.Action(key(),1,null,null),()->{});
        documents.action(doc,"confirm-direction",new PptDocuments.Action(key(),1,null,null),()->{});
        var row=start(doc);assertThat(documents.get(doc).phase()).isEqualTo("BRIEFING");assertThat(row.runId()).isNotNull();
        String manual=document();var input=new PptAgentService.Send(key(),"先讨论方向",0,node("{\"kind\":\"DOCUMENT\"}"));
        var message=service.send(manual,input);assertThat(agents.run(message.id()).orElseThrow().contextJson()).doesNotContain("generationAuthorization");
        assertThat(service.send(manual,input).id()).isEqualTo(message.id());assertThat(generations.latest(manual)).isEmpty();
    }
    @Test void freeformDiscussionCarriesEarlierTurnsBlocksWritesAndOnlyExplicitConfirmationCreatesGeneration() {
        String doc=document();var scope=node("{\"kind\":\"DOCUMENT\"}");
        var first=agent.send(doc,new PptAgentService.Send(key(),"制作一个介绍项目框架的 PPT，约 10 页",0,scope));
        var firstRun=agents.run(first.id()).orElseThrow();
        assertThat(firstRun.contextJson()).contains(PptDiscussionTranscript.PROTOCOL).doesNotContain("generationAuthorization");
        when(runtime.authorize(anyString(),eq(firstRun.id()),eq(doc))).thenReturn(firstRun);
        assertThatThrownBy(()->tools.call("ppt_submit_plan",Map.of("scope","fixture","runId",firstRun.id(),"documentId",doc,
                "args",Map.of("idempotencyKey",key(),"plan",Map.of())))).hasMessageContaining("讨论阶段不能写入");
        completeDiscussion(first,"面向开发人员，突出架构和核心 API，使用清晰的技术风格。");
        var second=agent.send(doc,new PptAgentService.Send(key(),"再加一页常见开发场景，不需要逐项讲每个接口",0,scope));
        var secondRun=completeDiscussion(second,"好的，增加开发场景页，并把 API 介绍控制在核心用法。");
        assertThat(PptDiscussionTranscript.before(agents,secondRun,json)).contains("突出架构和核心 API","使用清晰的技术风格");
        var snapshot=discussion.freeze(doc);
        assertThat(snapshot.text()).contains("介绍项目框架","突出架构和核心 API","常见开发场景","核心用法");
        String confirmationKey=key();var confirmation=new PptGenerationService.Confirm(confirmationKey,0);
        var generation=service.confirmRequirements(doc,confirmation);
        assertThat(generation.requirementsConfirmed()).isTrue();
        var row=persistence.require(generation.id());assertThat(row.prompt()).isEqualTo(snapshot.text());
        var run=agents.run(row.runId()).orElseThrow();assertThat(run.contextJson()).contains("requirementsConfirmedByUser","generationAuthorization");
        assertThat(run.userText()).contains("介绍项目框架","核心 API","常见开发场景");
        assertThat(service.confirmRequirements(doc,confirmation).id()).isEqualTo(generation.id());
    }
    @Test void confirmedGenerationRequiresAtLeastOneCompletedDiscussionTurn() {
        String doc=document();
        assertThatThrownBy(()->service.confirmRequirements(doc,new PptGenerationService.Confirm(key(),0)))
                .hasMessageContaining("先和 PPT 助手完成一轮需求讨论");
    }
    @Test void questionReplyKeepsAutomaticAuthorityAndLongAnswersAreContextNotRepeatedUserText() {
        String doc=document(),original="需".repeat(20000);
        var row=persistence.require(service.generate(doc,new PptGenerationService.Generate(key(),0,original)).id());var run=running(row);
        String questionId=key();agents.insertQuestion(new PptAgentRows.Question(questionId,run.id(),doc,"关键数据是什么？","[]","PENDING",null,null,null,Instant.now().toString(),0));
        agentPersistence.stop(doc,"INPUT");agentPersistence.proven(agents.run(run.id()).orElseThrow(),PptAgentState.WAITING_INPUT,"ACK","请回答");
        coordinator.tick(row.id());assertThat(persistence.require(row.id()).state()).isEqualTo("WAITING_INPUT");
        agent.reply(doc,questionId,new PptAgentService.Reply(key(),"答".repeat(5000),0,0));
        coordinator.tick(row.id());row=persistence.require(row.id());assertThat(row.state()).isEqualTo("PLANNING");
        assertThat(agents.run(run.id()).orElseThrow().contextJson()).contains("generationAuthorization");
        savePlan(row);coordinator.tick(row.id());coordinator.tick(row.id());
        var production=persistence.require(row.id());var next=agents.run(production.runId()).orElseThrow();
        assertThat(next.userText()).isEqualTo(original);assertThat(next.contextJson()).contains("generationAnswers","答".repeat(5000));
        assertThat(production.state()).isEqualTo("PRODUCING");
    }
    @Test void outputBindingFailureRollsBackJobAndStopAfterAtomicBindingCancelsIt() {
        var row=produce(start(document()));final var frozen=row;
        assertThatThrownBy(()->jobs.create(frozen.documentId(),new PptJobs.Create("PREVIEW",frozen.outputRevision(),null,key()),
                ()->persistence.guard(frozen),job->{throw PptSupport.conflict("模拟绑定失败");})).isInstanceOf(ConflictException.class);
        assertThat(mapper.jobs(row.documentId())).isEmpty();
        coordinator.tick(row.id());row=persistence.require(row.id());String job=row.jobId();assertThat(job).isNotBlank();
        agent.stop(row.documentId());coordinator.tick(row.id());jobs.execute(jobs.require(row.documentId(),job));
        assertThat(persistence.require(row.id()).state()).isEqualTo("STOPPED");assertThat(jobs.get(row.documentId(),job).state()).isEqualTo("CANCELLED");
        assertThat(mapper.artifacts(row.documentId(),job)).isEmpty();assertThat(documents.get(row.documentId()).phase()).isEqualTo("REVIEW");
    }
    @Test void automaticToolsCannotCreateUnownedOutputJobsButExactOldReceiptsRemainReplayable() {
        var original=outputs(produce(start(document())));String doc=original.documentId();
        service.send(doc,new PptAgentService.Send(key(),"仅微调第一页",documents.get(doc).revision(),node("{\"kind\":\"DOCUMENT\"}")));
        var row=generations.latest(doc).orElseThrow();var run=running(row);
        when(runtime.authorize(anyString(),eq(run.id()),eq(doc))).thenReturn(run);
        var capabilities=json.valueToTree(tools.call("ppt_get_capabilities",Map.of("scope","fixture","runId",run.id(),"documentId",doc,"args",Map.of())));
        assertThat(capabilities.path("allowedTools").valueStream().map(JsonNode::asText)).doesNotContain("ppt_render_preview","ppt_export");
        for(String tool:List.of("ppt_render_preview","ppt_export")) {
            var envelope=Map.<String,Object>of("scope","fixture","runId",run.id(),"documentId",doc,"args",Map.of("idempotencyKey",key(),"revision",run.sourceRevision()));
            assertThatThrownBy(()->tools.call(tool,envelope)).hasMessageContaining("服务端统一处理");
        }
        assertThat(mapper.jobs(doc)).hasSize(2);
        String oldKey=key();var args=Map.of("idempotencyKey",oldKey);
        agents.insertReceipt(new PptAgentRows.Receipt(run.id(),oldKey,"ppt_render_preview",PptSupport.hash(json.writeValueAsString(args)),"{\"id\":\"historical-job\"}",Instant.now().toString()));
        agent.stop(doc);
        JsonNode replay=json.valueToTree(tools.call("ppt_render_preview",Map.of("scope","fixture","runId",run.id(),"documentId",doc,"args",args)));
        assertThat(replay.path("id").asText()).isEqualTo("historical-job");assertThat(mapper.jobs(doc)).hasSize(2);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void firstNewMessageOnPreUpgradeCompletedDeckAutomaticallyCreatesFreshPreviewAndExport(boolean alreadyExported) {
        String doc=legacyReviewedDocument();
        if(alreadyExported) {
            var job=jobs.create(doc,new PptJobs.Create("EXPORT",2,null,key()),()->{});jobs.execute(jobs.require(doc,job.id()));
            assertThat(jobs.get(doc,job.id()).state()).isEqualTo("COMPLETED");
        }
        var input=new PptAgentService.Send(key(),"仅把第一页标题改为升级后的标题",2,node("{\"kind\":\"SLIDE\",\"slideId\":\"p1\"}"));
        var message=service.send(doc,input);assertThat(service.send(doc,input).id()).isEqualTo(message.id());
        var row=generations.latest(doc).orElseThrow();assertThat(row.mode()).isEqualTo("REVISE");assertThat(row.step()).isEqualTo("PRODUCING");
        var run=running(row);assertThat(run.contextJson()).contains("generationAuthorization","REVISE");
        var untouched=documents.deck(doc,null).slides().get(1);
        var args=json.createObjectNode().put("idempotencyKey",key()).put("expectedRevision",2);
        args.set("agentScope",input.scope());args.set("operations",node("[{\"op\":\"update_slide\",\"slideId\":\"p1\",\"patch\":{\"title\":\"升级后的标题\"}}]"));
        JsonNode result=json.valueToTree(workspace.invoke(doc,"ppt_apply_operations",args,()->persistence.validateRun(run)));
        receipt(run,"ppt_apply_operations",result);complete(run);coordinator.tick(row.id());var done=outputs(persistence.require(row.id()));
        assertThat(done.state()).isEqualTo("COMPLETED");assertThat(done.outputRevision()).isEqualTo(3);
        assertThat(jobs.get(doc,done.previewJobId()).revision()).isEqualTo(3);assertThat(jobs.get(doc,done.jobId()).revision()).isEqualTo(3);
        assertThat(jobs.download(doc,jobs.get(doc,done.jobId()).artifacts().getFirst().id())).isNotEmpty();
        assertThat(documents.deck(doc,null).slides().get(1)).isEqualTo(untouched);assertThat(service.send(doc,input).id()).isEqualTo(message.id());
    }
    @Test void preUpgradeManualMessageKeyReplaysOriginalRunAfterPhaseAndRevisionChangedWithoutGeneration() {
        String doc=legacyReviewedDocument();
        var input=new PptAgentService.Send(key(),"旧版的手工修改请求",2,node("{\"kind\":\"DOCUMENT\"}"));
        var original=agent.send(doc,input);agent.stop(doc);
        agentPersistence.proven(agents.run(original.id()).orElseThrow(),PptAgentState.STOPPED,"NOT_DISPATCHED","未投递");
        documents.edit(doc,new PptDocuments.Edit(key(),2,List.of(node("{\"op\":\"update_slide\",\"slideId\":\"p1\",\"patch\":{\"title\":\"用户新内容\"}}"))),false,()->{});
        var export=jobs.create(doc,new PptJobs.Create("EXPORT",3,null,key()),()->{});jobs.execute(jobs.require(doc,export.id()));
        assertThat(documents.get(doc).phase()).isEqualTo("EXPORTED");
        assertThat(service.send(doc,input).id()).isEqualTo(original.id());assertThat(generations.latest(doc)).isEmpty();
        assertThat(agents.run(original.id()).orElseThrow().contextJson()).doesNotContain("generationAuthorization");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ppt_agent_run",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->service.send(doc,new PptAgentService.Send(input.idempotencyKey(),"不同请求",3,input.scope()))).isInstanceOf(ConflictException.class);
        assertThat(generations.latest(doc)).isEmpty();
    }
}
