package io.opencode.loopper.service.ppt;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.PptMapper;
import io.opencode.loopper.ppt.PptFailure;
import io.opencode.loopper.service.ConflictException;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.opencode.model=fake/model","loopper.scheduling.enabled=false","loopper.startup-recovery.enabled=false"})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class PptWorkspaceIntegrationTest {
    static final Path DATA=data();
    static Path data(){try{return Files.createTempDirectory("ppt-workspace-it-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("loopper.data-dir",DATA::toString);r.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("test.db")+"?foreign_keys=on&journal_mode=WAL&transaction_mode=IMMEDIATE");}
    @Autowired Flyway flyway;
    @Autowired PptDocuments documents;
    @Autowired PptMapper mapper;
    @Autowired PptResources resources;
    @Autowired PptStorage storage;
    @Autowired PptJobs jobs;
    @Autowired PptJobPersistence jobPersistence;
    @Autowired PptAgentWorkspaceAdapter workspace;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    @BeforeEach void reset(){flyway.clean();flyway.migrate();}
    String create(){return documents.create(new PptDocuments.Create(UUID.randomUUID().toString(),"季度中文汇报",null,"fake/model")).id();}
    String key(){return UUID.randomUUID().toString();}
    JsonNode node(String text){return json.readTree(text);}
    PptDocuments.Edit edit(long revision,JsonNode...operations){return new PptDocuments.Edit(key(),revision,List.of(operations));}
    JsonNode page(String id,String text){return node("""
        {"op":"create_slide","slide":{"id":"%s","title":"业务进展","elements":[{"id":"text-%s","type":"text","x":40,"y":40,"width":800,"height":100,"fontSize":28,"text":"%s"}]}}
        """.formatted(id,id,text));}
    void phase(String id,String action){documents.action(id,action,new PptDocuments.Action(key(),documents.get(id).revision(),null,null),()->{});}
    void design(String id){
        documents.savePlan(id,new PptDocuments.PlanEdit(key(),0,node("""
            {"brief":{"purpose":"业务汇报"},"directions":[{"id":"a","title":"结果驱动"},{"id":"b","title":"问题驱动"}],"selectedDirectionId":"a","slides":[{"id":"p1","title":"业务进展"}],"theme":"business"}
            """)),false,()->{});
        phase(id,"finish-planning");phase(id,"confirm-direction");
    }
    String startTwoPageProduction(){
        String id=create();design(id);
        var plan=(tools.jackson.databind.node.ObjectNode)json.readTree(documents.snapshot(id,null).planJson());
        plan.withArray("slides").addObject().put("id","p2").put("title","第二页");
        documents.savePlan(id,new PptDocuments.PlanEdit(key(),documents.get(id).revision(),plan),false,()->{});
        phase(id,"start-production");return id;
    }
    String reviewedTwoPages(){
        String id=startTwoPageProduction();documents.edit(id,edit(documents.get(id).revision(),page("p1","保留页"),page("p2","删除页")),false,()->{});
        phase(id,"finish-production");return id;
    }
    PptJobs.View exportCurrent(String id){
        var job=jobs.create(id,new PptJobs.Create("EXPORT",documents.get(id).revision(),null,key()),()->{});
        jobs.execute(jobs.require(id,job.id()));var result=jobs.get(id,job.id());assertThat(result.state()).isEqualTo("COMPLETED");return result;
    }
    void assertExportBlocked(String id,String code){
        assertThatThrownBy(()->jobs.create(id,new PptJobs.Create("EXPORT",documents.get(id).revision(),null,key()),()->{}))
                .isInstanceOfSatisfying(io.opencode.loopper.service.BadRequestException.class,failure->assertThat(failure.code()).isEqualTo(code));
    }
    @Test void revisionsAreAtomicIdempotentAndCasProtected(){
        String id=create();var request=edit(0,page("p1","初稿"));
        var saved=documents.edit(id,request,false,()->{});assertThat(saved.path("revision").asLong()).isEqualTo(1);
        assertThat(documents.edit(id,request,false,()->{}).toString()).isEqualTo(saved.toString());
        assertThatThrownBy(()->documents.edit(id,new PptDocuments.Edit(request.idempotencyKey(),0,List.of(page("p2","不同请求"))),false,()->{})).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->documents.edit(id,edit(0,page("p2","旧版本")),false,()->{})).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->documents.edit(id,edit(1,page("p2","有效第一步"),node("{\"op\":\"remove_element\",\"slideId\":\"p1\",\"elementId\":\"missing\"}")),false,()->{})).isInstanceOf(PptFailure.class);
        assertThat(documents.deck(id,null).slides()).hasSize(1);assertThat(documents.get(id).revision()).isEqualTo(1);
        assertThat(documents.deck(id,0L).slides()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task",Integer.class)).isZero();
    }
    @Test void lateAgentGuardAndLockFailureDoNotCommitCandidate(){
        String id=create();documents.edit(id,edit(0,page("p1","原内容")),false,()->{});
        var request=edit(1,node("{\"op\":\"update_element\",\"slideId\":\"p1\",\"elementId\":\"text-p1\",\"patch\":{\"text\":\"迟到内容\"}}"));
        assertThatThrownBy(()->documents.edit(id,request,true,()->{throw PptSupport.conflict("已停止");})).isInstanceOf(ConflictException.class);
        assertThat(documents.deck(id,null).slides().getFirst().elements().getFirst().text()).isEqualTo("原内容");
        documents.edit(id,edit(1,node("{\"op\":\"update_slide\",\"slideId\":\"p1\",\"patch\":{\"locked\":true}}")),false,()->{});
        assertThatThrownBy(()->documents.edit(id,edit(2,node("{\"op\":\"delete_slide\",\"slideId\":\"p1\"}")),true,()->{})).isInstanceOf(PptFailure.class);
    }
    @Test void frozenScopeCannotChangeAnotherPageOrWholeTheme(){
        String id=create();documents.edit(id,edit(0,page("p1","一"),page("p2","二")),false,()->{});
        var args=json.createObjectNode().put("idempotencyKey",key()).put("expectedRevision",1);
        args.set("agentScope",node("{\"kind\":\"SLIDE\",\"slideId\":\"p1\"}"));
        args.set("operations",node("[{\"op\":\"update_element\",\"slideId\":\"p2\",\"elementId\":\"text-p2\",\"patch\":{\"text\":\"越权\"}}]"));
        assertThatThrownBy(()->workspace.invoke(id,"ppt_apply_operations",args,()->{})).hasMessageContaining("超出");
        args.set("operations",node("[{\"op\":\"apply_theme\",\"theme\":\"dark\"}]"));
        assertThatThrownBy(()->workspace.invoke(id,"ppt_apply_operations",args,()->{})).hasMessageContaining("超出");
        assertThat(documents.get(id).revision()).isEqualTo(1);
    }
    @Test void selectedPageAndObjectEditsAcceptEqualCanvasValuesAndPreserveOtherContent(){
        String id=create();documents.edit(id,edit(0,page("p1","一"),page("p2","二")),false,()->{});
        var unrelated=documents.deck(id,null).slides().get(1);
        for(String kind:List.of("SLIDE","ELEMENT")) {
            var args=json.createObjectNode().put("idempotencyKey",key()).put("expectedRevision",documents.get(id).revision());
            args.set("agentScope",json.valueToTree(Map.of("kind",kind,"slideId","p1","elementId","text-p1")));
            args.set("operations",node("[{\"op\":\"update_element\",\"slideId\":\"p1\",\"elementId\":\"text-p1\",\"patch\":{\"text\":\"局部修订\"}}]"));
            workspace.invoke(id,"ppt_apply_operations",args,()->{});
            assertThat(documents.deck(id,null).slides().getFirst().elements().getFirst().text()).isEqualTo("局部修订");
            assertThat(documents.deck(id,null).slides().get(1)).isEqualTo(unrelated);
        }
        assertThat(documents.get(id).revision()).isEqualTo(3);
    }
    @Test void sourceReadIsScopedBoundedAndHashVerified()throws Exception{
        String id=create(),other=create(),key=key();byte[] bytes="# 业务\n季度营收 100 万元\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var source=resources.upload(id,"资料.md",bytes,key,false);assertThat(source.state()).isEqualTo("READY");
        assertThat(resources.list(id).get("sources").getFirst().sections()).isEqualTo(1);
        assertThat(resources.upload(id,"资料.md",bytes,key,false).id()).isEqualTo(source.id());
        assertThat(resources.read(id,source.id(),"0",0,20).path("text").asText()).contains("营收");
        assertThatThrownBy(()->resources.source(other,source.id())).hasMessageContaining("不属于");
        assertThatThrownBy(()->resources.upload(id,"../资料.md",bytes,key(),false)).hasMessageContaining("文件名");
        var row=mapper.resource(id,source.id()).orElseThrow();Files.writeString(storage.path(id,row.storageKey()),"tampered");
        assertThatThrownBy(()->resources.source(id,source.id())).hasMessageContaining("校验");
    }
    @Test void previewAndEditableExportBindTheirOwnVersionAndSurviveFurtherEdits()throws Exception{
        String id=create();design(id);phase(id,"start-production");documents.edit(id,edit(1,page("p1","可编辑中文")),false,()->{});phase(id,"finish-production");
        var preview=jobs.create(id,new PptJobs.Create("PREVIEW",2,null,key()),()->{});jobs.execute(jobs.require(id,preview.id()));
        var ready=jobs.get(id,preview.id());assertThat(ready.state()).isEqualTo("COMPLETED");
        byte[] png=jobs.download(id,ready.artifacts().getFirst().id());assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png)).getWidth()).isEqualTo(1440);
        var output=jobs.create(id,new PptJobs.Create("EXPORT",2,null,key()),()->{});jobs.execute(jobs.require(id,output.id()));
        output=jobs.get(id,output.id());assertThat(output.state()).isEqualTo("COMPLETED");assertThat(documents.get(id).phase()).isEqualTo("EXPORTED");
        byte[] original=jobs.download(id,output.artifacts().getFirst().id());
        try(var ppt=new org.apache.poi.xslf.usermodel.XMLSlideShow(new java.io.ByteArrayInputStream(original))){assertThat(ppt.getSlides()).hasSize(1);assertThat(ppt.getSlides().getFirst().getShapes().getFirst()).isInstanceOf(org.apache.poi.xslf.usermodel.XSLFTextBox.class);}
        documents.edit(id,edit(2,node("{\"op\":\"update_element\",\"slideId\":\"p1\",\"elementId\":\"text-p1\",\"patch\":{\"text\":\"新版本\"}}")),false,()->{});
        assertThat(documents.get(id).phase()).isEqualTo("REVIEW");assertThat(jobs.download(id,output.artifacts().getFirst().id())).isEqualTo(original);
    }
    @Test void qualityProblemsRemainDraftAndBlockFormalExport(){
        String id=create();design(id);phase(id,"start-production");documents.edit(id,edit(1,page("p1","内容")),false,()->{});
        documents.edit(id,edit(2,node("{\"op\":\"update_element\",\"slideId\":\"p1\",\"elementId\":\"text-p1\",\"patch\":{\"x\":950}}")),false,()->{});
        assertThat(documents.get(id).revision()).isEqualTo(3);
        assertThatThrownBy(()->phase(id,"finish-production")).hasMessageContaining("阻断");
        assertThatThrownBy(()->jobs.create(id,new PptJobs.Create("EXPORT",3,null,key()),()->{})).hasMessageContaining("制作检查");
    }
    @Test void firstProductionRequiresEveryPlannedPageWithContent(){
        String id=startTwoPageProduction();documents.edit(id,edit(documents.get(id).revision(),page("p1","第一页")),true,()->{});
        assertThatThrownBy(()->phase(id,"finish-production")).hasMessageContaining("部分设计页面尚未制作");
        documents.edit(id,edit(documents.get(id).revision(),node("{\"op\":\"create_slide\",\"slide\":{\"id\":\"p2\",\"title\":\"空的计划页\"}}")),true,()->{});
        assertThatThrownBy(()->phase(id,"finish-production")).hasMessageContaining("部分设计页面尚未制作");
        assertThat(documents.get(id).phase()).isEqualTo("PRODUCING");
        documents.edit(id,edit(documents.get(id).revision(),node("{\"op\":\"delete_slide\",\"slideId\":\"p2\"}"),page("p2","第二页已完成")),true,()->{});
        phase(id,"finish-production");assertThat(documents.get(id).phase()).isEqualTo("REVIEW");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void explicitDeletionAfterProductionExportsCurrentDeckAndPreservesPlanAndHistory(boolean alreadyExported)throws Exception{
        String id=reviewedTwoPages();var originalSnapshot=documents.snapshot(id,null);
        var prior=alreadyExported?exportCurrent(id):null;byte[] oldBytes=prior==null?null:jobs.download(id,prior.artifacts().getFirst().id());
        documents.edit(id,edit(documents.get(id).revision(),node("{\"op\":\"delete_slide\",\"slideId\":\"p2\"}")),alreadyExported,()->{});
        assertThat(documents.get(id).phase()).isEqualTo("REVIEW");
        assertThat(documents.snapshot(id,null).planJson()).isEqualTo(originalSnapshot.planJson());
        assertThat(documents.snapshot(id,originalSnapshot.revision())).isEqualTo(originalSnapshot);
        assertThatThrownBy(()->documents.savePlan(id,new PptDocuments.PlanEdit(key(),documents.get(id).revision(),node(originalSnapshot.planJson())),true,()->{}))
                .hasMessageContaining("制作方案已经确认");
        var result=exportCurrent(id);
        try(var ppt=new org.apache.poi.xslf.usermodel.XMLSlideShow(new java.io.ByteArrayInputStream(jobs.download(id,result.artifacts().getFirst().id())))){
            assertThat(ppt.getSlides()).hasSize(1);
            assertThat(((org.apache.poi.xslf.usermodel.XSLFTextShape)ppt.getSlides().getFirst().getShapes().getFirst()).getText()).isEqualTo("保留页");
        }
        if(prior!=null)assertThat(jobs.download(id,prior.artifacts().getFirst().id())).isEqualTo(oldBytes);
    }
    @Test void postProductionEditsStillRejectEmptyPagesEmptyDeckAndInvalidLayout(){
        String id=reviewedTwoPages();
        documents.edit(id,edit(documents.get(id).revision(),node("{\"op\":\"create_slide\",\"slide\":{\"id\":\"unplanned-empty\"}}")),false,()->{});
        assertExportBlocked(id,"PPT_EMPTY_PAGE");
        documents.edit(id,edit(documents.get(id).revision(),node("{\"op\":\"delete_slide\",\"slideId\":\"unplanned-empty\"}"),
                node("{\"op\":\"update_element\",\"slideId\":\"p1\",\"elementId\":\"text-p1\",\"patch\":{\"x\":950}}")),false,()->{});
        assertExportBlocked(id,"PPT_LAYOUT_INVALID");
        documents.edit(id,edit(documents.get(id).revision(),node("{\"op\":\"remove_element\",\"slideId\":\"p1\",\"elementId\":\"text-p1\"}")),false,()->{});
        assertExportBlocked(id,"PPT_EMPTY_PAGE");
        documents.edit(id,edit(documents.get(id).revision(),node("{\"op\":\"delete_slide\",\"slideId\":\"p1\"}"),node("{\"op\":\"delete_slide\",\"slideId\":\"p2\"}")),false,()->{});
        assertExportBlocked(id,"PPT_EMPTY");
        assertThat(documents.get(id).phase()).isEqualTo("REVIEW");
    }
    @Test void editedFrozenExportDoesNotRecheckThePlanAgainstALaterProductionPhase(){
        String id=reviewedTwoPages();documents.edit(id,edit(documents.get(id).revision(),node("{\"op\":\"delete_slide\",\"slideId\":\"p2\"}")),false,()->{});
        var pending=jobs.create(id,new PptJobs.Create("EXPORT",documents.get(id).revision(),null,key()),()->{});
        phase(id,"reopen");phase(id,"finish-planning");phase(id,"confirm-direction");phase(id,"start-production");
        jobs.execute(jobs.require(id,pending.id()));
        assertThat(jobs.get(id,pending.id()).state()).isEqualTo("COMPLETED");
        assertThat(documents.get(id).phase()).isEqualTo("PRODUCING");
        assertThatThrownBy(()->phase(id,"finish-production")).hasMessageContaining("部分设计页面尚未制作");
    }
    @Test void postProductionExportStillVerifiesImageBytes()throws Exception{
        String id=reviewedTwoPages();var bytes=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(32,24,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        var image=resources.upload(id,"素材.png",bytes.toByteArray(),key(),true);
        documents.edit(id,edit(documents.get(id).revision(),node("""
            {"op":"add_element","slideId":"p1","element":{"id":"picture","type":"image","assetId":"%s","x":40,"y":180,"width":200,"height":150}}
            """.formatted(image.id()))),false,()->{});
        var asset=mapper.resource(id,image.id()).orElseThrow();Files.write(storage.path(id,asset.storageKey()),new byte[]{1});
        var job=jobs.create(id,new PptJobs.Create("EXPORT",documents.get(id).revision(),null,key()),()->{});jobs.execute(jobs.require(id,job.id()));
        assertThat(jobs.get(id,job.id()).state()).isEqualTo("FAILED");assertThat(jobs.get(id,job.id()).artifacts()).isEmpty();
        assertThat(documents.get(id).phase()).isEqualTo("REVIEW");
    }
    @Test void restartResumesSavedPagesAndCancelledJobNeverPublishesNewArtifacts(){
        String id=create();documents.edit(id,edit(0,page("p1","一"),page("p2","二")),false,()->{});
        var view=jobs.create(id,new PptJobs.Create("PREVIEW",1,null,key()),()->{});var running=jobPersistence.state(jobs.require(id,view.id()),"RUNNING",0,"");
        jobs.execute(running);var ready=jobs.get(id,view.id());assertThat(ready.artifacts()).hasSize(2);
        jobs.execute(running);assertThat(jobs.get(id,view.id()).artifacts()).hasSize(2);
        var cancel=jobs.create(id,new PptJobs.Create("PREVIEW",1,"p1",key()),()->{});jobs.cancel(id,cancel.id());jobs.execute(jobs.require(id,cancel.id()));
        assertThat(jobs.get(id,cancel.id()).artifacts()).isEmpty();
    }
    @Test void restoringHistoryCreatesNewRevisionAndDoesNotRewriteThePast(){
        String id=create();documents.edit(id,edit(0,page("p1","第一版")),false,()->{});documents.edit(id,edit(1,page("p2","第二版")),false,()->{});
        documents.action(id,"restore",new PptDocuments.Action(key(),2,null,1L),()->{});
        assertThat(documents.get(id).revision()).isEqualTo(3);assertThat(documents.deck(id,2L).slides()).hasSize(2);assertThat(documents.deck(id,3L).slides()).hasSize(1);
    }
    @Test void failedPreviewResumesOnlyMissingPagesAfterResourceRecovery()throws Exception{
        String id=create();var out=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(32,24,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
        byte[] bytes=out.toByteArray();var asset=resources.upload(id,"示例.png",bytes,key(),true);
        var second=node("""
            {"op":"create_slide","slide":{"id":"p2","title":"图片","elements":[{"id":"pic","type":"image","assetId":"%s","x":50,"y":50,"width":200,"height":160}]}}
            """.formatted(asset.id()));
        documents.edit(id,edit(0,page("p1","第一张已完成"),second),false,()->{});
        var source=mapper.resource(id,asset.id()).orElseThrow();var path=storage.path(id,source.storageKey());Files.write(path,new byte[]{1});
        var job=jobs.create(id,new PptJobs.Create("PREVIEW",1,null,key()),()->{});jobs.execute(jobs.require(id,job.id()));
        var failed=jobs.get(id,job.id());assertThat(failed.state()).isEqualTo("FAILED");assertThat(failed.completed()).isEqualTo(1);
        var first=failed.artifacts().getFirst();byte[] saved=jobs.download(id,first.id());
        Files.write(path,bytes);jobs.retry(id,job.id());jobs.execute(jobs.require(id,job.id()));
        var complete=jobs.get(id,job.id());assertThat(complete.state()).isEqualTo("COMPLETED");assertThat(complete.artifacts()).hasSize(2);
        assertThat(jobs.download(id,first.id())).isEqualTo(saved);
    }
    @Test void restRequiresLocalMutationHeaderAndServesExactDeckEnvelope()throws Exception{
        var input=json.writeValueAsString(new PptDocuments.Create(UUID.randomUUID().toString(),"HTTP作品",null,"fake/model"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ppt/documents").contentType("application/json").content(input))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        var response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ppt/documents").header("X-Loopper-Local-UI","1").contentType("application/json").content(input))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        String id=json.readTree(response.getResponse().getContentAsString()).path("id").asText();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/ppt/documents/"+id+"/deck"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.width").value(960))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.slides").isArray());
    }
}
