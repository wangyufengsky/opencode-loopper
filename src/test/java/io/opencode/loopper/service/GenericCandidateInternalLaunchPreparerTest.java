package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class GenericCandidateInternalLaunchPreparerTest {
    @TempDir Path projectRoot;

    @Test
    void exposesStableLaunchAndRunIdsBeforeAnyRemoteBoundary() throws Exception {
        OpenCodeClient openCode = mock(OpenCodeClient.class);
        GenericCandidateInternalLaunchStore store = mock(GenericCandidateInternalLaunchStore.class);
        GenericCandidateInternalLaunchPlanCodec plans =
                new GenericCandidateInternalLaunchPlanCodec(new ObjectMapper());
        GenericCandidateInternalLaunchPreparer preparer = new GenericCandidateInternalLaunchPreparer(
                openCode, store, plans, new AcceptanceCandidateCreationCredentialSource());
        var command = new GenericCandidateInternalLaunchPreparer.PrepareCommand(
                MachineCandidateKind.REVIEWER_REPORT_V1,
                MachineCandidateSubmission.CandidateScope.designerSession("designer"),
                MachineCandidateSubmission.CandidateOwnerRef.analysisReport("report"),
                7, 0, projectRoot, null,
                OpenCodeClient.SessionProfile.REVIEWER_CANDIDATE_READ_ONLY);
        when(store.findActive(command.owner(), "REVIEWER_REPORT_V1")).thenReturn(Optional.empty());
        when(openCode.prepareCandidateSessionCreationLocally(
                any(Path.class), anyString(), any(), any(), anyString()))
                .thenAnswer(invocation -> plan(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(3), invocation.getArgument(4)));
        when(store.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        GenericCandidateInternalLaunchPreparer.Prepared prepared = preparer.prepare(command);

        assertThat(prepared.row().id())
                .isEqualTo(GenericCandidateInternalLaunchPreparer.launchId(command));
        assertThat(prepared.row().candidateRunId())
                .isEqualTo(GenericCandidateInternalLaunchPreparer.candidateRunId(command));
        assertThat(prepared.row().id()).isNotEqualTo(prepared.row().candidateRunId());
        assertThat(prepared.plan().exactTitle())
                .contains("candidate_launch_id:" + prepared.row().id());
        verify(openCode, never()).requireCandidateSessionReady(any());
        verify(openCode, never()).findSessionsByExactTitle(any(OpenCodeClient.SessionCreationPlan.class));
        verify(openCode, never()).createSession(any(OpenCodeClient.SessionCreationPlan.class));
    }

    @Test
    void acceptsJudgeCandidateReadOnlyProfileForTheGenericLaunchProtocol() throws Exception {
        OpenCodeClient openCode = mock(OpenCodeClient.class);
        GenericCandidateInternalLaunchStore store = mock(GenericCandidateInternalLaunchStore.class);
        GenericCandidateInternalLaunchPlanCodec plans =
                new GenericCandidateInternalLaunchPlanCodec(new ObjectMapper());
        GenericCandidateInternalLaunchPreparer preparer = new GenericCandidateInternalLaunchPreparer(
                openCode, store, plans, new AcceptanceCandidateCreationCredentialSource());
        var command = new GenericCandidateInternalLaunchPreparer.PrepareCommand(
                MachineCandidateKind.JUDGE_DECISION_V1,
                MachineCandidateSubmission.CandidateScope.task("task"),
                MachineCandidateSubmission.CandidateOwnerRef.judgeRun("judge-run"),
                11, 3, projectRoot, null,
                OpenCodeClient.SessionProfile.JUDGE_CANDIDATE_READ_ONLY);
        when(store.findActive(command.owner(), "JUDGE_DECISION_V1")).thenReturn(Optional.empty());
        when(openCode.prepareCandidateSessionCreationLocally(
                any(Path.class), anyString(), any(), any(), anyString()))
                .thenAnswer(invocation -> plan(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(3), invocation.getArgument(4)));
        when(store.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        GenericCandidateInternalLaunchPreparer.Prepared prepared = preparer.prepare(command);

        assertThat(prepared.row().candidateKind()).isEqualTo("JUDGE_DECISION_V1");
        assertThat(prepared.row().taskId()).isEqualTo("task");
        assertThat(prepared.row().judgeRunId()).isEqualTo("judge-run");
        assertThat(prepared.row().profile()).isEqualTo("JUDGE_CANDIDATE_READ_ONLY");
        assertThat(prepared.plan().profile())
                .isEqualTo(OpenCodeClient.SessionProfile.JUDGE_CANDIDATE_READ_ONLY);
    }

    private OpenCodeClient.SessionCreationPlan plan(Path root, String baseTitle,
            OpenCodeClient.SessionProfile profile, String credential)
            throws Exception {
        String server = "loopper_internal_generic";
        List<OpenCodeClient.SessionPermissionRule> permissions = List.of(
                new OpenCodeClient.SessionPermissionRule("*", "*", "deny"),
                new OpenCodeClient.SessionPermissionRule("read", "*", "allow"),
                new OpenCodeClient.SessionPermissionRule("glob", "*", "allow"),
                new OpenCodeClient.SessionPermissionRule("grep", "*", "allow"),
                new OpenCodeClient.SessionPermissionRule("read", ".env", "deny"),
                new OpenCodeClient.SessionPermissionRule("read", ".env.*", "deny"),
                new OpenCodeClient.SessionPermissionRule("read", ".env.example", "allow"),
                new OpenCodeClient.SessionPermissionRule("external_directory", "*", "deny"),
                new OpenCodeClient.SessionPermissionRule(server + "_submit_candidate", "*", "allow"));
        String title = OpenCodeClient.recoveryTitle(baseTitle, credential);
        String digest = OpenCodeClient.permissionPolicyDigest(permissions);
        String request = OpenCodeClient.sessionCreationRequestSha256(
                root.toRealPath(), title, "generation-1", true, server, "a".repeat(64), null,
                profile, digest, credential);
        return OpenCodeClient.SessionCreationPlan.fromPersisted(
                root.toRealPath(), title, "generation-1", true, server, "a".repeat(64), null,
                profile,
                permissions, digest, credential, request);
    }
}
