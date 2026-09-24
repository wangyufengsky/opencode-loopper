package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StoryAccountingOwnerRow;
import io.opencode.loopper.persistence.StoryAccountingSessionRow;
import io.opencode.loopper.persistence.StoryAccountingCallRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.service.roles.RoleConfigurationService;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class StoryAccountingRoleTest {
    @Test
    void configuredAccountantMarksThePersistedMessageBeforeDispatch() {
        var mapper = mock(LoopperMapper.class);
        var roles = mock(RoleConfigurationService.class);
        var transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        when(mapper.findStoryAccountingOwner("remote")).thenReturn(Optional.of(
                new StoryAccountingOwnerRow("binding", "SYS-001", "000123", "designer", null, "REQUIREMENT_DESIGNER", false)));
        when(mapper.incrementStorySessionOrdinal("binding")).thenReturn(1);
        AtomicReference<StoryAccountingSessionRow> saved = new AtomicReference<>();
        when(mapper.insertStoryAccountingSession(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return 1;
        });
        when(mapper.findStoryAccountingSessionById(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(roles.sessionSnapshot("remote")).thenReturn(Optional.empty());
        when(roles.resolveFrozen(new RoleConfigurationService.OwnerRef("DESIGNER_SESSION", "designer"), "ACCOUNTING_COMMAND"))
                .thenReturn(Optional.of(new RoleConfigurationService.ResolvedRole("accounting", "revision-1",
                        "a".repeat(64), "ACCOUNTING_COMMAND", "ACCOUNTING_COMMAND", Map.of("accounting.instructions", "static"),
                        "BASELINE", List.of(), List.of(), List.of(), "INHERIT_WORKFLOW", "ACCOUNTING_COMMAND")));
        var coordinator = new StoryAccountingCoordinator(mapper, mock(TaskEventService.class), transactions, roles);
        try {
            coordinator.beforeBusinessPrompt(new OpenCodeClient.OpenCodeSession("remote", Path.of("/tmp")), request -> {
                assertThat(request.messageId()).matches("msg_loopper_aicoding_[0-9a-f]{32}_role");
                return new OpenCodeClient.CommandResult("run", "ok");
            });
            var calls = org.mockito.ArgumentCaptor.forClass(StoryAccountingCallRow.class);
            verify(mapper).insertStoryAccountingCall(calls.capture());
            assertThat(calls.getValue().messageId()).matches("msg_loopper_aicoding_[0-9a-f]{32}_role");
        } finally { coordinator.close(); }
    }

    @ParameterizedTest
    @CsvSource({"REQUIREMENT_DESIGNER,true", "PACKAGE_DESIGNER,true", "PACKAGE_DESIGN_V1,true",
            "IMPLEMENTATION,true", "ROUTER,false", "DECOMPOSER,false", "COMPILER,false",
            "REVIEWER,false", "JUDGE,false", "DECOMPOSITION_PLAN_V2,false", "ACCEPTANCE_CLOSED_CHOICE_V7,false",
            "ROLLING_PACKAGE_PLAN_V1,false", "REVIEWER_REPORT_V1,false", "PROJECT_CONVENTION_V1,false",
            "JUDGE_DECISION_V1,false", "FINALIZER,false", "UNTRACKED,false"})
    void onlyDesignersAndImplementersStartAccounting(String role, boolean eligible) {
        var mapper = mock(LoopperMapper.class);
        var transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        when(mapper.findStoryAccountingOwner("remote")).thenReturn(Optional.of(
                new StoryAccountingOwnerRow("binding", "SYS-001", "000123", "designer", null, role, false)));
        when(mapper.incrementStorySessionOrdinal("binding")).thenReturn(1);
        // Even a later Session must issue start, never continue.
        when(mapper.currentStorySessionOrdinal("binding")).thenReturn(7);
        var coordinator = new StoryAccountingCoordinator(mapper, mock(TaskEventService.class), transactions);
        AtomicInteger calls = new AtomicInteger();
        try {
            coordinator.beforeBusinessPrompt(new OpenCodeClient.OpenCodeSession("remote", Path.of("/tmp")), request -> {
                calls.incrementAndGet();
                assertThat(request.arguments()).isEqualTo("start SYS-001 000123");
                return new OpenCodeClient.CommandResult("run", "ok");
            });
            assertThat(calls.get()).isEqualTo(eligible ? 1 : 0);
            verify(mapper, times(eligible ? 1 : 0)).incrementStorySessionOrdinal("binding");
            verify(mapper, times(eligible ? 1 : 0)).insertStoryAccountingSession(any());
        } finally { coordinator.close(); }
    }
}
