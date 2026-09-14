package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class DocumentRollingPlanTransportTest {
    @TempDir Path root;
    private final DocumentPlanTransportMapper mapper=mock(DocumentPlanTransportMapper.class);
    private final LoopperMapper domain=mock(LoopperMapper.class);
    private final RollingPackagePlanService plans=mock(RollingPackagePlanService.class);
    private final RollingPackagePlanCandidateOrchestrator candidates=mock(RollingPackagePlanCandidateOrchestrator.class);
    private final CandidateRuntimeBindingService bindings=mock(CandidateRuntimeBindingService.class);
    private final FakeOpenCodeClient runtime=new FakeOpenCodeClient();
    private final ObjectMapper json=new ObjectMapper();
    private final AtomicReference<DocumentPlanTransportMapper.Transport> saved=new AtomicReference<>();
    private final AtomicReference<TaskPackagePlanRevisionRow> row=new AtomicReference<>();
    private DocumentRollingPlanTransport transport;
    private final OpenCodeClient.OpenCodeModel model=new OpenCodeClient.OpenCodeModel("fake","test",null);
    @BeforeEach void prepare() {
        runtime.setManagedRuntime("generation","internal"); runtime.holdProfileOpen(OpenCodeClient.SessionProfile.ROLLING_PACKAGE_CANDIDATE_READ_ONLY,true);
        row.set(plan(null,"PENDING",0));
        transport=new DocumentRollingPlanTransport(mapper,domain,plans,candidates,runtime,Optional.of(bindings),json);
        when(domain.findTaskPackagePlanRevision("plan")).thenAnswer(call->Optional.of(row.get()));
        when(domain.documentTaskState("task")).thenReturn(Optional.of("DESIGNING"));
        when(domain.documentPlanSourceCurrent("plan")).thenReturn(true);
        when(mapper.find("plan")).thenAnswer(call->Optional.ofNullable(saved.get()));
        when(mapper.insert(any())).thenAnswer(call->{saved.set(call.getArgument(0));return 1;});
        when(mapper.prompt(eq("plan"),anyString(),anyString())).thenAnswer(call->{var old=saved.get();
            saved.set(new DocumentPlanTransportMapper.Transport("plan",old.creationPlanJson(),call.getArgument(1),call.getArgument(2),old.createdAt()));return 1;});
        when(plans.attachSuggestionSession(any(),anyString(),eq("PROMPTING"))).thenAnswer(call->{row.set(plan(call.getArgument(1),"PROMPTING",1));return row.get();});
        when(plans.updateSuggestionState(any(),eq("RUNNING"))).thenAnswer(call->{row.set(plan(row.get().externalSessionId(),"RUNNING",2));return row.get();});
        when(candidates.open(any(),any(),anyString())).thenAnswer(call->new RollingPackagePlanCandidateOrchestrator.Start(call.getArgument(1),
                mock(MachineCandidateSubmission.RunSnapshot.class),"冻结规划请求正文"));
    }
    @Test void disabledRuntimeGuardRefusesBeforeCreatingRemoteSession() {
        var disabled=new DocumentRollingPlanTransport(mapper,domain,plans,candidates,runtime,Optional.empty(),json);
        assertThatThrownBy(() -> disabled.advance(row.get(),root,"facts",model))
                .isInstanceOf(ConflictException.class).hasMessageContaining("身份校验未启用");
        verifyNoInteractions(mapper,plans,candidates,bindings);
    }
    @Test void lostCreationAndMessageResponsesReusePersistedIdentities() {
        transport.advance(row.get(),root,"facts",model);
        assertThat(runtime.createReadOnlySessionCalls()).isZero();
        var creation=json.readValue(saved.get().creationPlanJson(),OpenCodeClient.SessionCreationPlan.class);
        var remote=runtime.createSession(creation); // Simulates successful creation whose response did not reach the owner.
        transport.advance(row.get(),root,"changed facts must not create another session",model);
        assertThat(row.get().externalSessionId()).isEqualTo(remote.remoteId()); assertThat(runtime.createReadOnlySessionCalls()).isEqualTo(1);
        transport.advance(row.get(),root,"facts",model);
        var request=json.readValue(saved.get().promptJson(),DocumentRollingPlanTransport.FrozenPrompt.class).request();
        runtime.promptAsync(remote.session(),request); // Simulates a lost send acknowledgement.
        int calls=runtime.promptCalls();
        transport.advance(row.get(),root,"later facts",model);
        assertThat(runtime.promptCalls()).isEqualTo(calls); assertThat(row.get().externalSessionState()).isEqualTo("RUNNING");
        assertThat(saved.get().promptSha256()).isEqualTo(OpenCodeClient.promptRequestSha256(request));
    }
    @Test void cancellationFindsCreatedButUnattachedSessionsAndRequiresStopProof() {
        transport.advance(row.get(),root,"facts",model);
        var creation=json.readValue(saved.get().creationPlanJson(),OpenCodeClient.SessionCreationPlan.class);
        runtime.createSession(creation);
        runtime.failNextAborts(1);
        assertThatThrownBy(()->transport.stop(row.get())).isInstanceOf(RuntimeException.class);
        verify(plans,never()).failSuggestion(any(),anyString(),anyString(),anyString());
        assertThat(transport.stop(row.get())).isTrue();
        verify(plans).failSuggestion(any(),eq("DOCUMENT_PLAN_CANCELLED"),anyString(),eq("ABORT_ACKNOWLEDGED"));
    }
    @Test void changedOwnerOrSourceCannotCreateOrSendAnotherRequest() {
        when(domain.documentTaskState("task")).thenReturn(Optional.of("STOPPING"));
        assertThatThrownBy(()->transport.advance(row.get(),root,"facts",model)).isInstanceOf(ConflictException.class);
        assertThat(runtime.createReadOnlySessionCalls()).isZero(); verify(mapper,never()).insert(any());
        when(domain.documentTaskState("task")).thenReturn(Optional.of("DESIGNING")); when(domain.documentPlanSourceCurrent("plan")).thenReturn(false);
        assertThatThrownBy(()->transport.advance(row.get(),root,"facts",model)).isInstanceOf(ConflictException.class);
        assertThat(runtime.promptCalls()).isZero();
    }
    private TaskPackagePlanRevisionRow plan(String session,String state,long version) {
        String now=Instant.now().toString();
        return new TaskPackagePlanRevisionRow("plan","task","designer","source",2,"GENERATING","AI","[]","{}",session,state,
                null,null,"checkpoint",3,"package",2,now,now,null,null,version);
    }
}
