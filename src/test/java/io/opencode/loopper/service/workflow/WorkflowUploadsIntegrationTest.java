package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.roles.RolePublishingService;
import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=LoopperApplication.class,properties={"loopper.opencode.mode=fake","loopper.workflow-monitor-enabled=false","loopper.monitor-delay=1h"})
class WorkflowUploadsIntegrationTest {
    private static final Path DATA=data();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("loopper.data-dir",()->DATA.toString());p.add("spring.datasource.url",()->"jdbc:sqlite:"+DATA.resolve("uploads.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&transaction_mode=IMMEDIATE");}
    @Autowired Flyway flyway;
    @Autowired WorkflowTemplates templates;
    @Autowired WorkflowPlans plans;
    @Autowired WorkflowUploads uploads;
    @Autowired WorkflowUploadMapper mapper;
    @Autowired WorkflowCodeFiles files;
    @Autowired WorkflowModelAdmission admission;
    @Autowired WorkflowModelExecution execution;
    @Autowired WorkflowModelStore models;
    @Autowired WorkflowModelTools tools;
    @Autowired WorkflowRuntimeSupport identity;
    @Autowired InternalMcpRuntimeAccess access;
    @Autowired OpenCodeClient client;
    @Autowired WorkflowNodeRuns nodes;
    @Autowired RolePublishingService roles;
    @Autowired RoleConfigurationMapper roleMapper;
    @Autowired ProjectService projects;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    String project;FakeOpenCodeClient fake;
    @BeforeEach void prepare() throws Exception {
        flyway.clean();flyway.migrate();roles.seedBuiltin();fake=(FakeOpenCodeClient)client;fake.reset();
        var credentials=new InternalMcpCredentialProvider(()->18083).issue();access.activate(credentials);access.connected(credentials.generation());
        fake.setManagedRuntime(credentials.generation(),credentials.serverName());fake.holdProfileOpen(OpenCodeClient.SessionProfile.WORKFLOW_READ_ONLY,true);
        project=projects.create("原文阅读",Files.createDirectory(directory.resolve("project")).toString(),"").id();
    }
    @Test void uploadsArePinnedAndModelReadsOnlyTheSelectedRequirementsDocuments() {
        String id=requirement(),other=requirement();var request=request(id);var incoming=List.of(file("需求.md","# 订单\n提交必须鉴权。\n# 金额\n金额必须大于零。"));
        var result=uploads.upload(id,request,incoming);assertThat(result.ready()).isTrue();assertThat(result.parserVersion()).isEqualTo("ASSIST_DOCUMENT_V2");
        assertThat(result.originals().getFirst().limitations()).isNotEmpty();assertThat(uploads.upload(id,request,incoming)).isEqualTo(result);
        assertThat(fake.createSessionCalls()+fake.createReadOnlySessionCalls()).isZero();assertThat(count("workflow_node_attempt")).isZero();
        var bound=files.uploaded(id,result.reference());assertThat(files.bytes(bound,result.originals().getFirst().path())).isEqualTo(incoming.getFirst().bytes());
        assertThatThrownBy(()->files.uploaded(other,result.reference())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->start(other,result.reference())).isInstanceOf(ConflictException.class);assertThat(count("workflow_node_attempt")).isZero();
        String run=start(id,result.reference());running(run);
        var list=(io.opencode.loopper.api.CursorPage<?>)call(run,WorkflowModelProfile.FILES,Map.of("name","source","limit",1));
        assertThat(list.nextCursor()).isNotNull();assertThat(list.items()).hasSize(1);
        var text=(WorkflowCodeFiles.Text)call(run,WorkflowModelProfile.FILE,Map.of("name","source","path","parsed/01/0001.md","limit",3));
        assertThat(text.nextOffset()).isEqualTo(3);
        assertThat(files.text(files.input(id,"work",run,"source"),"parsed/01/0001.md",3,12000).text()).contains("提交必须鉴权");
        assertThatThrownBy(()->call(run,WorkflowModelProfile.FILE,Map.of("name","other","path","parsed/01/0001.md"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->uploads.upload(id,request(id),incoming)).isInstanceOf(ConflictException.class);
        assertThat(uploads.upload(id,request,incoming)).isEqualTo(result);
        var delivery=new WorkflowDelivery("完成阅读",null,Map.of("result",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("金额和身份校验"))));
        call(run,WorkflowModelProfile.SUBMIT,Map.of("requestKey",key(),"expectedAttemptVersion",nodes.attempt(run).version(),"delivery",delivery));
        fake.setSessionState(nodes.attempt(run).externalSessionId(),"COMPLETED");execution.advance(run);
        assertThat(plans.require(id).state()).isEqualTo("COMPLETED");
        assertThat(files.text(files.input(id,"work",run,"source"),"parsed/01/0001.md",0,12000).text()).contains("提交必须鉴权");
        assertThatThrownBy(()->call(run,WorkflowModelProfile.FILE,Map.of("name","source","path","parsed/01/0001.md"))).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }
    @Test void interruptedAvailabilityRetriesSameIdentityWithoutReparsingOrOverwriting() {
        String id=requirement();var request=request(id);var incoming=List.of(file("资料.md","# 原文\n完整正文"));
        jdbc.execute("CREATE TRIGGER fail_upload_ready BEFORE INSERT ON workflow_upload_ready BEGIN SELECT RAISE(ABORT,'ready rollback'); END");
        assertThatThrownBy(()->uploads.upload(id,request,incoming)).hasStackTraceContaining("ready rollback");
        var pending=uploads.list(id,null,50).items().getFirst();assertThat(pending.ready()).isFalse();assertThat(pending.resume().requestKey()).isEqualTo(request.requestKey());
        assertThatThrownBy(()->start(id,pending.reference())).isInstanceOf(ConflictException.class);assertThat(count("workflow_input_snapshot")).isZero();
        assertThatThrownBy(()->uploads.upload(id,request,List.of(file("资料.md","更改内容")))).isInstanceOf(ConflictException.class);
        jdbc.execute("DROP TRIGGER fail_upload_ready");var complete=uploads.upload(id,request,incoming);
        assertThat(complete.id()).isEqualTo(pending.id());assertThat(complete.reference()).isEqualTo(pending.reference());assertThat(complete.resume()).isNull();
        assertThat(count("workflow_upload")).isEqualTo(1);assertThat(count("workflow_upload_ready")).isEqualTo(1);
        for(String table:List.of("workflow_upload","workflow_upload_file","workflow_upload_ready"))assertThatThrownBy(()->jdbc.update("DELETE FROM "+table)).hasStackTraceContaining("history");
    }
    @Test void cancelledRequirementDoesNotBecomeReadyFromLateUploadOrRetry() {
        String id=requirement();var request=request(id);var incoming=List.of(file("资料.md","原文"));
        jdbc.execute("CREATE TRIGGER fail_upload_ready BEFORE INSERT ON workflow_upload_ready BEGIN SELECT RAISE(ABORT,'ready rollback'); END");
        assertThatThrownBy(()->uploads.upload(id,request,incoming)).hasStackTraceContaining("ready rollback");jdbc.execute("DROP TRIGGER fail_upload_ready");
        plans.cancelPlanning(id,new WorkflowRequests.VersionCommand(key(),plans.require(id).version()));
        assertThatThrownBy(()->uploads.upload(id,request,incoming)).isInstanceOf(ConflictException.class);
        assertThat(uploads.list(id,null,50).items().getFirst().ready()).isFalse();assertThat(count("workflow_upload_ready")).isZero();
    }
    @Test void admissionRollbackHasNoPartialDocumentsAndStaleMetadataCannotStartUpload() {
        String id=requirement();var request=request(id);var incoming=List.of(file("资料.md","原文"));
        jdbc.execute("CREATE TRIGGER fail_upload_file BEFORE INSERT ON workflow_upload_file BEGIN SELECT RAISE(ABORT,'file rollback'); END");
        assertThatThrownBy(()->uploads.upload(id,request,incoming)).hasStackTraceContaining("file rollback");assertThat(count("workflow_upload")).isZero();
        jdbc.execute("DROP TRIGGER fail_upload_file");
        assertThatThrownBy(()->uploads.upload(id,new WorkflowUploadStore.Request(key(),request.expectedVersion()-1,1),incoming)).isInstanceOf(ConflictException.class);
        assertThat(uploads.upload(id,request,incoming).ready()).isTrue();
    }
    @Test void originalsAndParsedHashesRejectTamperingAndUnsafeReads() throws Exception {
        String id=requirement();var result=uploads.upload(id,request(id),List.of(file("说明.md","# 原文\n证据")));var bound=files.uploaded(id,result.reference());
        assertThatThrownBy(()->files.bytes(bound,"../../elsewhere")).isInstanceOf(NotFoundException.class);
        var original=mapper.file(result.id(),result.originals().getFirst().path()).orElseThrow();
        Files.writeString(DATA.resolve("document-templates").resolve(original.storagePath()),"被修改");
        assertThatThrownBy(()->files.bytes(bound,original.path())).isInstanceOf(BadRequestException.class).hasMessageContaining("校验失败");
        assertThat(files.text(bound,"parsed/01/0001.md",0,12000).text()).contains("证据");
        assertThatThrownBy(()->files.uploaded(id,new WorkflowUpload.Reference(1,WorkflowUpload.TYPE,result.id(),"0".repeat(64)))).isInstanceOf(ConflictException.class);
    }
    @Test void uploadedDocxAndPdfKeepTheirOriginalsAndReadableSections() throws Exception {
        byte[] docx,pdf;
        try(var document=new org.apache.poi.xwpf.usermodel.XWPFDocument();var bytes=new java.io.ByteArrayOutputStream()){
            document.createParagraph().createRun().setText("Approval requires permission.");document.write(bytes);docx=bytes.toByteArray();
        }
        try(var document=new org.apache.pdfbox.pdmodel.PDDocument();var bytes=new java.io.ByteArrayOutputStream()){
            var page=new org.apache.pdfbox.pdmodel.PDPage();document.addPage(page);
            try(var stream=new org.apache.pdfbox.pdmodel.PDPageContentStream(document,page)){stream.beginText();stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(20,700);stream.showText("Amount must be positive.");stream.endText();}
            document.save(bytes);pdf=bytes.toByteArray();
        }
        String id=requirement();var result=uploads.upload(id,request(id),List.of(new DocumentTemplateStorage.Incoming("需求.docx",docx),new DocumentTemplateStorage.Incoming("需求.pdf",pdf)));
        var bound=files.uploaded(id,result.reference());assertThat(files.text(bound,"parsed/01/0001.md",0,12000).text()).contains("Approval requires");
        assertThat(files.text(bound,"parsed/02/0001.md",0,12000).text()).contains("Amount must");assertThat(files.bytes(bound,result.originals().get(1).path())).isEqualTo(pdf);
    }
    @Test void longSourcesAreReadInPagesAndUploadHistoryIsScopedAndPaginated() {
        String id=requirement();var result=uploads.upload(id,request(id),List.of(file("长文.md","# 范围\n"+"要求完整覆盖。\n".repeat(16000))));
        var bound=files.uploaded(id,result.reference());assertThat(result.originals().getFirst().sections()).isGreaterThan(5);
        var page=files.list(bound,null,2);assertThat(page.items()).hasSize(2);assertThat(files.list(bound,page.nextCursor(),2).items()).doesNotContainAnyElementsOf(page.items());
        var second=uploads.upload(id,request(id),List.of(file("第二版.md","独立历史")));
        var history=uploads.list(id,null,1);assertThat(history.items()).extracting(WorkflowUpload.Summary::id).containsExactly(second.id());
        assertThat(uploads.list(id,history.nextCursor(),1).items()).extracting(WorkflowUpload.Summary::id).containsExactly(result.id());
        assertThat(uploads.list(requirement(),null,50).items()).isEmpty();assertThatThrownBy(()->files.list(files.uploaded(id,second.reference()),page.nextCursor(),2)).isInstanceOf(BadRequestException.class);
    }
    private String requirement() {
        var first=new Node("work","阅读原文",NodeKind.WORK,WorkflowModelProfile.MODULE,1,"builtin.general","完整阅读上传的需求，交付结论",
                List.of(new Input("source",InputSource.REQUIREMENT,"documents",null,DataKind.DOCUMENT,true)),List.of(new Output("result","阅读结论",DataKind.TEXT,true)),List.of(),
                new Completion(CompletionKind.DELIVERABLES,"交付阅读结果",null),0,false,Map.of(),roleMapper.latest("builtin.general").revisionId());
        var template=templates.create(new WorkflowRequests.CreateTemplate(key(),"文档流程","",new WorkflowGraph(1,List.of(first),List.of(),List.of(new PublicInput("documents","需求文档",DataKind.DOCUMENT,true))),CanvasLayout.empty()));
        var result=plans.create(new WorkflowRequests.CreateRequirement(key(),project,"文档需求","核对文档",template.id(),1));plans.confirm(result.id(),new WorkflowRequests.VersionCommand(key(),result.version()));return result.id();
    }
    private String start(String id,WorkflowUpload.Reference ref){return admission.start(id,"work",new WorkflowModelAdmission.Start(key(),plans.require(id).version(),Map.of("documents",new WorkflowDelivery.Value(DataKind.DOCUMENT,json.valueToTree(ref))),new OpenCodeClient.OpenCodeModel("fake","test",false))).attemptId();}
    private void running(String run){for(int i=0;i<4&&!models.require(run).state().equals("RUNNING");i++)execution.advance(run);assertThat(models.require(run).state()).isEqualTo("RUNNING");}
    private Object call(String run,String tool,Map<String,Object> args){return tools.call(tool,Map.of("scope",identity.grant(models.require(run)),"attemptId",run,"args",args));}
    private WorkflowUploadStore.Request request(String id){var owner=plans.require(id);return new WorkflowUploadStore.Request(key(),owner.version(),owner.headRevision());}
    private static DocumentTemplateStorage.Incoming file(String name,String text){return new DocumentTemplateStorage.Incoming(name,text.getBytes(StandardCharsets.UTF_8));}
    private int count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
    private static String key(){return UUID.randomUUID().toString();}
    private static Path data(){try{return Files.createTempDirectory("workflow-upload-tests-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
