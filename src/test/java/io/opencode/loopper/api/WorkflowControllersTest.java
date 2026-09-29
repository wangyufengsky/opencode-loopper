package io.opencode.loopper.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.opencode.loopper.service.workflow.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkflowControllersTest {
    @Test void directoryConfirmationStatusAndRecoveryKeepExplicitRequestAndVersion()throws Exception {
        mvc.perform(post("/api/workflows/requirements/owner/publication/writeback/confirm").header("X-Loopper-Local-UI","1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestKey\":\"key\",\"expectedVersion\":7,\"selection\":{\"revision\":2,\"node\":\"work\",\"attempt\":\"attempt\",\"output\":\"code\",\"sourceSha256\":\"source\"},\"previewSha256\":\"preview\"}")).andExpect(status().isOk());
        verify(writebackExecution).confirm("owner",new io.opencode.loopper.workflow.WorkflowWriteback.Request("key",7,new io.opencode.loopper.workflow.WorkflowWriteback.Selection(2,"work","attempt","code","source"),"preview"));
        mvc.perform(post("/api/workflows/requirements/owner/publication/writeback/retry").header("X-Loopper-Local-UI","1").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":3}")).andExpect(status().isOk());
        mvc.perform(get("/api/workflows/requirements/owner/publication/writeback")).andExpect(status().isOk());verify(writebacks).retry("owner",3);verify(writebacks).get("owner");
        verifyNoMoreInteractions(writebacks,writebackExecution);verifyNoInteractions(writebackPreviews,nodes,controls);
    }
    @Test void directoryPreviewPreservesExactSourceAndRequiresExplicitLocalInspection()throws Exception {
        var request=new io.opencode.loopper.workflow.WorkflowWriteback.Selection(2,"work","attempt","code","hash");
        when(writebackPreviews.inspect("owner",request)).thenReturn(new WorkflowWritebackPreviews.Context(null,null,null));
        mvc.perform(post("/api/workflows/requirements/owner/publication/writeback/preview").header("X-Loopper-Local-UI","1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"revision\":2,\"node\":\"work\",\"attempt\":\"attempt\",\"output\":\"code\",\"sourceSha256\":\"hash\"}")).andExpect(status().isOk());
        verify(writebackPreviews).inspect("owner",request);verifyNoMoreInteractions(writebackPreviews);verifyNoInteractions(pushExecution,publications,nodes,controls);
    }
    @Test void remotePushUsesExplicitLocalAuthorityAndExactPreviewAndVersion()throws Exception {
        var preview=new io.opencode.loopper.workflow.WorkflowPush.Preview("owner",3,"origin","https://example.org/repo.git","loopper/results/one","commit",null,"hash");
        when(pushPreviews.inspect("owner","origin")).thenReturn(new WorkflowPushPreviews.Context(null,null,preview));
        mvc.perform(post("/api/workflows/requirements/owner/publication/push/preview").header("X-Loopper-Local-UI","1").contentType(MediaType.APPLICATION_JSON).content("{\"remote\":\"origin\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.publicationVersion").value(3));
        mvc.perform(post("/api/workflows/requirements/owner/publication/push").header("X-Loopper-Local-UI","1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestKey\":\"push-key\",\"expectedVersion\":3,\"remote\":\"origin\",\"previewSha256\":\"hash\"}" )).andExpect(status().isOk());
        mvc.perform(post("/api/workflows/requirements/owner/publication/push/retry").header("X-Loopper-Local-UI","1").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":7}" )).andExpect(status().isOk());
        verify(pushPreviews).inspect("owner","origin");verify(pushExecution).confirm("owner",new io.opencode.loopper.workflow.WorkflowPush.Request("push-key",3,"origin","hash"));verify(pushExecution).retry("owner",7);
        mvc.perform(get("/api/workflows/requirements/owner/publication/push")).andExpect(status().isOk());
        mvc.perform(get("/api/workflows/requirements/owner/publication/push/remotes")).andExpect(status().isOk());verify(pushes).get("owner");verify(pushPreviews).remotes("owner");
        verifyNoMoreInteractions(pushes,pushPreviews,pushExecution);verifyNoInteractions(publications,nodes,modelActions,controls);
    }
    @Test void confirmedPublicationUsesLocalAuthorityAndExplicitVersionedSelection()throws Exception {
        var request=new io.opencode.loopper.workflow.WorkflowPublication.Request("request-key",7,2,"work","attempt","code","sha","保存成果");
        mvc.perform(post("/api/workflows/requirements/owner/publication").header("X-Loopper-Local-UI","1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestKey\":\"request-key\",\"expectedVersion\":7,\"revision\":2,\"node\":\"work\",\"attempt\":\"attempt\",\"output\":\"code\",\"previewSha256\":\"sha\",\"message\":\"保存成果\"}"))
            .andExpect(status().isOk());verify(publications).confirm("owner",request);
        mvc.perform(post("/api/workflows/requirements/owner/publication/retry").header("X-Loopper-Local-UI","1").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":3}"))
            .andExpect(status().isOk());verify(publications).retry("owner",3);
        mvc.perform(get("/api/workflows/requirements/owner/publication")).andExpect(status().isOk());verify(publications).get("owner");verifyNoInteractions(nodes,modelActions,controls);
    }
    @Test void partialSnapshotReportUsesExactSourceAttemptWithoutExecutingWork()throws Exception {
        when(partialReports.read("owner","source","attempt")).thenReturn(new io.opencode.loopper.workflow.WorkflowSnapshotPartialReport("阶段报告","hash","now",4,1,2,3));
        mvc.perform(get("/api/workflows/requirements/owner/nodes/source/attempts/attempt/snapshot-partial-report"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.planRevision").value(4)).andExpect(jsonPath("$.pendingUnits").value(2));
        verify(partialReports).read("owner","source","attempt");verifyNoMoreInteractions(partialReports);verifyNoInteractions(nodes,modelActions,controls);
    }
    @Test void inputBodyReadCarriesExactOwnerAttemptNameAndPagination() throws Exception {
        when(nodes.inputContent("owner","node","attempt","draft",3,7)).thenReturn(new WorkflowInputPages.Page("draft",io.opencode.loopper.workflow.WorkflowGraph.DataKind.TEXT,"fixed-hash","正文",3,null,5));
        mvc.perform(get("/api/workflows/requirements/owner/nodes/node/attempts/attempt/inputs/draft/content").param("offset","3").param("limit","7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("正文")).andExpect(jsonPath("$.totalLength").value(5));
        verify(nodes).inputContent("owner","node","attempt","draft",3,7);verifyNoMoreInteractions(nodes);
    }
    private final WorkflowPlanTemplates planTemplates = mock(WorkflowPlanTemplates.class);
    private final WorkflowTemplates templates = mock(WorkflowTemplates.class);
    private final WorkflowPlans plans = mock(WorkflowPlans.class);
    private final WorkflowNodeActions nodes = mock(WorkflowNodeActions.class);
    private final WorkflowModelAdmission modelAdmission = mock(WorkflowModelAdmission.class);
    private final WorkflowModelActions modelActions = mock(WorkflowModelActions.class);
    private final WorkflowCodeFiles files = mock(WorkflowCodeFiles.class);
    private final WorkflowPublicationReads publication = mock(WorkflowPublicationReads.class);
    private final WorkflowPublications publications = mock(WorkflowPublications.class);
    private final WorkflowPushes pushes = mock(WorkflowPushes.class);
    private final WorkflowPushPreviews pushPreviews = mock(WorkflowPushPreviews.class);
    private final WorkflowPushExecution pushExecution = mock(WorkflowPushExecution.class);
    private final WorkflowWritebacks writebacks = mock(WorkflowWritebacks.class);
    private final WorkflowWritebackExecution writebackExecution = mock(WorkflowWritebackExecution.class);
    private final WorkflowWritebackPreviews writebackPreviews = mock(WorkflowWritebackPreviews.class);
    private final WorkflowSnapshotPartialReports partialReports = mock(WorkflowSnapshotPartialReports.class);
    private final WorkflowRunReads reads = mock(WorkflowRunReads.class);
    private final WorkflowRunActivity activity = mock(WorkflowRunActivity.class);
    private final WorkflowCommandActions commandActions = mock(WorkflowCommandActions.class);
    private final WorkflowControls controls = mock(WorkflowControls.class);
    private final WorkflowPlanCandidates candidates = mock(WorkflowPlanCandidates.class);
    private final WorkflowPlanRevisions revisions = mock(WorkflowPlanRevisions.class);
    private final WorkflowNodePresets presets = mock(WorkflowNodePresets.class);
    private final WorkflowNodeKnowledgeEvidence knowledge = mock(WorkflowNodeKnowledgeEvidence.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new WorkflowPushController(pushes,pushPreviews,pushExecution),
            new WorkflowWritebackController(writebackPreviews,writebacks,writebackExecution),
            new WorkflowPublicationController(publication,publications), new WorkflowSnapshotReportController(partialReports), new WorkflowKnowledgeEvidenceController(knowledge), new WorkflowPlanTemplateController(planTemplates), new WorkflowCommandController(commandActions), new WorkflowPresetController(presets), new WorkflowPlanCandidateController(candidates), new WorkflowPlanRevisionController(revisions), new WorkflowTemplateController(templates), new WorkflowRequirementController(plans), new WorkflowNodeController(nodes),
            new WorkflowModelController(modelAdmission, modelActions), new WorkflowFileController(files), new WorkflowControlController(controls), new WorkflowRunReadController(reads,activity))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    @Test void publicationReadsBindTheCurrentPlanAndSelectedSourceWithoutSideEffects()throws Exception {
        when(publication.sources("owner",3,"cursor",7)).thenReturn(new CursorPage<>(java.util.List.of(new io.opencode.loopper.workflow.WorkflowPublicationPreview.Source("work","开发阶段","attempt",2,"FAILED","code","代码","now",2,8)),"next"));
        mvc.perform(get("/api/workflows/requirements/owner/publication/sources").param("revision","3").param("cursor","cursor").param("limit","7"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].attemptState").value("FAILED")).andExpect(jsonPath("$.items[0].content").doesNotExist());
        mvc.perform(get("/api/workflows/requirements/owner/publication/preview").param("revision","3").param("node","work").param("attempt","attempt").param("output","code")).andExpect(status().isOk());
        verify(publication).sources("owner",3,"cursor",7);verify(publication).preview("owner",3,"work","attempt","code");verifyNoMoreInteractions(publication);
        verifyNoInteractions(nodes,modelActions,controls,plans);
    }
    @Test void knowledgeListsOnlyMetadataAndReadsBodyInsideTheExactAttempt()throws Exception {
        when(knowledge.list("owner","node","attempt","cursor",7)).thenReturn(new CursorPage<>(java.util.List.of(
                new io.opencode.loopper.workflow.WorkflowKnowledgeEvidence.Entry("entry","read_knowledge_source","now")),"next"));
        mvc.perform(get("/api/workflows/requirements/owner/nodes/node/attempts/attempt/knowledge").param("cursor","cursor").param("limit","7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value("entry")).andExpect(jsonPath("$.items[0].content").doesNotExist());
        verify(knowledge).list("owner","node","attempt","cursor",7);verifyNoMoreInteractions(knowledge);
        mvc.perform(get("/api/workflows/requirements/owner/nodes/node/attempts/attempt/knowledge/entry")).andExpect(status().isOk());
        verify(knowledge).read("owner","node","attempt","entry");verifyNoInteractions(modelActions,modelAdmission);
    }
    @Test void presetReadsPreserveTheCatalogCursorAndExplicitVersionWithoutCreatingRuns() throws Exception {
        when(presets.list("资料","cursor",7)).thenReturn(new CursorPage<>(java.util.List.of(new WorkflowNodePresets.Summary("analysis.read",1,"资料分析","阅读资料")),"next"));
        mvc.perform(get("/api/workflows/node-presets").param("query","资料").param("cursor","cursor").param("limit","7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value("analysis.read"))
                .andExpect(jsonPath("$.items[0].node").doesNotExist()).andExpect(jsonPath("$.nextCursor").value("next"));
        mvc.perform(get("/api/workflows/node-presets/analysis.read/versions/1")).andExpect(status().isOk());
        verify(presets).list("资料","cursor",7);verify(presets).get("analysis.read",1);verifyNoMoreInteractions(presets);
        verifyNoInteractions(templates,plans,nodes,modelAdmission,controls);
    }
    @Test void everyMutationRequiresLocalUiAuthorityBeforeCallingTheApplication() throws Exception {
        for (var request : java.util.List.of(
                java.util.Map.entry(post("/api/workflows/requirements/id/publication/writeback/confirm"), "{\"expectedVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/publication/writeback/retry"), "{\"expectedVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/publication/writeback/preview"), "{\"revision\":1}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/publication/push/preview"), "{\"remote\":\"origin\"}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/publication/push"), "{\"expectedVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/publication/push/retry"), "{\"expectedVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/publication"), "{\"expectedVersion\":0,\"revision\":1}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/publication/retry"), "{\"expectedVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/nodes/first/attempts/a/command/stop"), "{\"expectedVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/nodes/first/attempts/a/command/resume"), "{\"expectedVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/templates/preview"), "{\"expectedRevision\":1}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/templates"), "{}"),
                java.util.Map.entry(post("/api/workflows/templates"), "{}"),
                java.util.Map.entry(put("/api/workflows/templates/id"), "{\"expectedVersion\":0,\"expectedRevision\":1}"),
                java.util.Map.entry(post("/api/workflows/templates/id/copy"), "{\"sourceRevision\":1}"),
                java.util.Map.entry(delete("/api/workflows/templates/id"), "{\"expectedVersion\":0}"),
                java.util.Map.entry(put("/api/workflows/templates/id/layout"), "{\"expectedRevision\":1,\"expectedLayoutVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements"), "{\"templateRevision\":1}"),
                java.util.Map.entry(put("/api/workflows/requirements/id/plan"), "{\"expectedVersion\":0,\"expectedRevision\":1}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/plan/apply"), "{\"expectedVersion\":0,\"expectedRevision\":1}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/candidates/candidate/apply"), "{\"expectedVersion\":0,\"expectedRevision\":1,\"expectedCandidateVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/candidates/candidate/reject"), "{\"expectedCandidateVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/confirm"), "{\"expectedVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/cancel"), "{\"expectedVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/control/start"), "{\"expectedVersion\":0,\"expectedControlVersion\":-1,\"mode\":\"CONTINUOUS\"}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/control/pause"), "{\"expectedControlVersion\":0}"),
                java.util.Map.entry(put("/api/workflows/requirements/id/layout"), "{\"expectedRevision\":1,\"expectedLayoutVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/nodes/first/human/start"), "{\"expectedVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/nodes/first/model/start"), "{\"expectedVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/nodes/first/attempts/a/model/stop"), "{\"expectedVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/nodes/first/attempts/a/model/resume"), "{\"expectedVersion\":0}"),
                java.util.Map.entry(post("/api/workflows/requirements/id/nodes/first/human/complete"), "{\"expectedVersion\":0,\"expectedAttemptVersion\":1}")))
            mvc.perform(request.getKey().contentType(MediaType.APPLICATION_JSON).content(request.getValue()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));
        verifyNoInteractions(writebacks, writebackExecution, writebackPreviews, pushes, pushPreviews, pushExecution, publications, planTemplates, templates, plans, nodes, modelAdmission, modelActions, controls, candidates, revisions, commandActions);
    }
    @Test void nativeCommandRecoveryPreservesExactScopeVersionAndIdempotencyKey() throws Exception {
        var request=new WorkflowCommandActions.Command("native-command-key",7);
        var view=new WorkflowCommandActions.View("attempt","STOPPING",false,null,8);
        when(commandActions.stop("owner","node","attempt",request)).thenReturn(view);
        mvc.perform(post("/api/workflows/requirements/owner/nodes/node/attempts/attempt/command/stop")
                .header("X-Loopper-Local-UI","1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestKey\":\"native-command-key\",\"expectedVersion\":7}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("STOPPING")).andExpect(jsonPath("$.version").value(8));
        mvc.perform(get("/api/workflows/requirements/owner/nodes/node/attempts/attempt/command/evidence")).andExpect(status().isOk());
        verify(commandActions).stop("owner","node","attempt",request);verify(commandActions).evidence("owner","node","attempt");
        verifyNoInteractions(modelActions,modelAdmission);
    }
    @Test void confirmingAPlanRoutesOnlyToConfirmationAndReturnsItsDurableAcknowledgement() throws Exception {
        when(plans.confirm(eq("id"), any())).thenReturn(new WorkflowCommands.Receipt("id", 2, 4, 1, "PENDING_START"));
        mvc.perform(post("/api/workflows/requirements/id/confirm").header("X-Loopper-Local-UI", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestKey\":\"confirmation-key-1234\",\"expectedVersion\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING_START"));
        verify(plans).confirm("id", new WorkflowRequests.VersionCommand("confirmation-key-1234", 3));
        verifyNoMoreInteractions(plans); verifyNoInteractions(templates);
    }
    @Test void documentPreviewRetainsExactProducerIdentityAndBoundedPageArguments()throws Exception {
        var binding=new WorkflowCodeFiles.Binding("p","r","a",null,null,new io.opencode.loopper.workflow.WorkflowDocument.Reference(1,"HISTORY_DOCUMENT","a","hash"));
        when(files.output("r","n","a","document")).thenReturn(binding);
        when(files.text(binding,"报告/明细.md",12,120)).thenReturn(new WorkflowCodeFiles.Text("报告/明细.md","hash","固定正文",12,null));
        mvc.perform(get("/api/workflows/requirements/r/nodes/n/attempts/a/outputs/document/text").param("path","报告/明细.md").param("offset","12").param("limit","120"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("固定正文")).andExpect(jsonPath("$.path").value("报告/明细.md"));
        verify(files).output("r","n","a","document");verify(files).text(binding,"报告/明细.md",12,120);verifyNoMoreInteractions(files);
    }
    @Test void fileReadsResolveTheNamedInputOrOutputInsideTheRequestedAttempt() throws Exception {
        var reference = new io.opencode.loopper.workflow.WorkflowCodeSnapshot.Reference(1,"snapshot","0".repeat(64));
        var input = new WorkflowCodeFiles.Binding("project", "requirement", "parent-attempt", reference);
        var output = new WorkflowCodeFiles.Binding("project", "requirement", "attempt", reference);
        when(files.input("requirement", "node", "attempt", "source")).thenReturn(input);
        when(files.output("requirement", "node", "attempt", "code")).thenReturn(output);
        when(files.list(input, "cursor", 7)).thenReturn(new CursorPage<>(java.util.List.of(
                new WorkflowCodeFiles.File("src/例子.java", 4, "hash", "100644")), "next"));
        mvc.perform(get("/api/workflows/requirements/requirement/nodes/node/attempts/attempt/inputs/source/files")
                        .param("cursor", "cursor").param("limit", "7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].path").value("src/例子.java"))
                .andExpect(jsonPath("$.nextCursor").value("next"));
        byte[] binary = {0, 1, (byte) 255, 9}; when(files.bytes(output, "src/例子.java")).thenReturn(binary);
        mvc.perform(get("/api/workflows/requirements/requirement/nodes/node/attempts/attempt/outputs/code/file")
                        .param("path", "src/例子.java"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().bytes(binary)).andExpect(header().string("Content-Length", "4"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("filename*=UTF-8''")));
        verify(files).input("requirement", "node", "attempt", "source"); verify(files).list(input, "cursor", 7);
        verify(files).output("requirement", "node", "attempt", "code"); verify(files).bytes(output, "src/例子.java");
        verifyNoMoreInteractions(files);
    }
    @Test void codeChangeReadsResolveExactBindingsAndRejectOtherDirections() throws Exception {
        var reference=new io.opencode.loopper.workflow.WorkflowCodeSnapshot.Reference(1,"snapshot","0".repeat(64));
        var input=new WorkflowCodeFiles.Binding("project","requirement","producer",reference);
        when(files.input("requirement","node","attempt","source")).thenReturn(input);
        when(files.changes(input,"cursor",7)).thenReturn(new CursorPage<>(java.util.List.of(
                new io.opencode.loopper.workflow.WorkflowCodeSnapshot.Change("deleted.txt","DELETE","blob",null)),"next"));
        mvc.perform(get("/api/workflows/requirements/requirement/nodes/node/attempts/attempt/inputs/source/changes").param("cursor","cursor").param("limit","7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].kind").value("DELETE"))
                .andExpect(jsonPath("$.items[0].path").value("deleted.txt")).andExpect(jsonPath("$.nextCursor").value("next"));
        mvc.perform(get("/api/workflows/requirements/requirement/nodes/node/attempts/attempt/other/source/changes"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("WORKFLOW_FILE_BINDING_INVALID"));
        verify(files).input("requirement","node","attempt","source");verify(files).changes(input,"cursor",7);verifyNoMoreInteractions(files);
    }
    @Test void documentArchiveUsesTheSameScopedBindingAndReturnsDownloadHeaders() throws Exception {
        var reference=new io.opencode.loopper.workflow.WorkflowDocument.Reference(1,"DESIGN_DOCUMENT","a","0".repeat(64));
        var binding=new WorkflowCodeFiles.Binding("p","r","a",null,null,reference);byte[] bytes={80,75,3,4};
        when(files.output("r","n","a","document")).thenReturn(binding);when(files.archive(binding)).thenReturn(bytes);
        mvc.perform(get("/api/workflows/requirements/r/nodes/n/attempts/a/outputs/document/archive"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip")).andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Length","4")).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("filename*=UTF-8''")));
        verify(files).output("r","n","a","document");verify(files).archive(binding);verifyNoMoreInteractions(files);
    }
    @Test void invalidFileDirectionCannotBypassBindingResolution() throws Exception {
        mvc.perform(get("/api/workflows/requirements/r/nodes/n/attempts/a/arbitrary/source/files"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("WORKFLOW_FILE_BINDING_INVALID"));
        verifyNoInteractions(files);
    }
    @Test void executionReadReturnsOneSnapshotWithoutLoadingAnyAttemptBodies() throws Exception {
        var view=new WorkflowControls.View("r",2,5,false,-1,null,null,io.opencode.loopper.workflow.WorkflowControlState.PAUSED,"WORKFLOW_NOT_STARTED",null,java.util.List.of());
        when(reads.snapshot("r")).thenReturn(new WorkflowRunReads.Snapshot(view,new WorkflowNodeActions.Overview("r",2,5,"PENDING_START",java.util.List.of())));
        mvc.perform(get("/api/workflows/requirements/r/execution")).andExpect(status().isOk())
                .andExpect(jsonPath("$.control.version").value(5)).andExpect(jsonPath("$.execution.version").value(5))
                .andExpect(jsonPath("$.execution.state").value("PENDING_START"));
        verify(reads).snapshot("r");verifyNoMoreInteractions(reads);verifyNoInteractions(nodes,activity,controls,modelActions);
    }
    @Test void paginatedHistoryAndExpandedReadsPreserveEveryPathScope() throws Exception {
        when(reads.page("r","n","cursor",8)).thenReturn(new CursorPage<>(java.util.List.of(),null));
        mvc.perform(get("/api/workflows/requirements/r/nodes/n/attempts").param("cursor","cursor").param("limit","8")).andExpect(status().isOk());
        mvc.perform(get("/api/workflows/requirements/r/nodes/n/attempts/a")).andExpect(status().isOk());
        mvc.perform(get("/api/workflows/requirements/r/nodes/n/attempts/a/definition")).andExpect(status().isOk());
        when(activity.get("r","n","a")).thenReturn(new WorkflowRunActivity.Activity(false,"now","暂时无法读取",java.util.List.of(),false));
        mvc.perform(get("/api/workflows/requirements/r/nodes/n/attempts/a/activity")).andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(false));
        verify(reads).page("r","n","cursor",8);verify(reads).get("r","n","a");verify(reads).definition("r","n","a");verify(activity).get("r","n","a");
        verifyNoMoreInteractions(reads,activity);verifyNoInteractions(nodes,controls,modelActions);
    }

    @Test void explicitCandidateApprovalReturnsItsReceiptWithoutStartingExecution() throws Exception {
        var request=new WorkflowPlanCandidates.Apply("apply-key-12345",8,2,0,null);
        when(candidates.apply("r","c",request)).thenReturn(new WorkflowCommands.Receipt("r",3,10,4,"PAUSED"));
        mvc.perform(post("/api/workflows/requirements/r/candidates/c/apply").header("X-Loopper-Local-UI","1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestKey\":\"apply-key-12345\",\"expectedVersion\":8,\"expectedRevision\":2,\"expectedCandidateVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3)).andExpect(jsonPath("$.state").value("PAUSED"));
        verify(candidates).apply("r","c",request);verifyNoMoreInteractions(candidates);verifyNoInteractions(controls,nodes,modelAdmission,modelActions);
    }

}
