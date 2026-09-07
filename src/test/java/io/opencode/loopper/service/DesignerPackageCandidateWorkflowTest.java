package io.opencode.loopper.service;

import static org.mockito.Mockito.*;
import java.util.Optional;
import io.opencode.loopper.persistence.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DesignerPackageCandidateWorkflowTest {
    @Test void frozenPreparationFailureCannotBecomeAnotherTransportRound() {
        var mapper = mock(LoopperMapper.class);
        var candidates = mock(DesignerPackageCandidateOrchestrator.class);
        var host = mock(DesignerSessionService.class);
        var owner = mock(DesignWorkPackageRow.class);
        var session = mock(DesignerSessionRow.class);
        when(owner.id()).thenReturn("owner"); when(owner.designerExternalSessionId()).thenReturn("remote");
        when(mapper.behaviorForRemote("owner", "remote")).thenReturn(Optional.of(mock(PackageBehaviorPreparationRow.class)));
        var workflow = new DesignerPackageCandidateWorkflow(mapper, null, new ObjectMapper(), null, null, candidates);
        workflow.failHandoff(host, owner, session, "PACKAGE_SOURCE_UNCONFIRMED", "saved evidence", true);
        verify(host).failPackageDesigner(owner, session, "PACKAGE_SOURCE_UNCONFIRMED", "saved evidence", false);
        workflow.failHandoff(host, owner, session, "REMOTE_STOP_UNCONFIRMED", "abort=false", true);
        verify(host).failPackageDesigner(owner, session, "REMOTE_STOP_UNCONFIRMED", "abort=false", false);
        when(mapper.behaviorForRemote("owner", "remote")).thenReturn(Optional.empty());
        workflow.failHandoff(host, owner, session, "LEGACY_TRANSPORT", "network", true);
        verify(host).failPackageDesigner(owner, session, "LEGACY_TRANSPORT", "network", true);
    }
}
