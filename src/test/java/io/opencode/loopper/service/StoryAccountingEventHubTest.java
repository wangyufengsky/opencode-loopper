package io.opencode.loopper.service;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;

class StoryAccountingEventHubTest {
    @Test void publishesOnlyAfterCommitAndDropsRolledBackInvalidations() throws Exception {
        var hub = new StoryAccountingEventHub();
        var received = new ArrayList<String>();
        try (var subscription = hub.subscribe(received::add)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                hub.changed("committed");
                assertThat(received).isEmpty();
                var hooks = TransactionSynchronizationManager.getSynchronizations();
                hooks.forEach(TransactionSynchronization::afterCommit);
                assertThat(received).containsExactly("committed");
            } finally { TransactionSynchronizationManager.clear(); }
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                hub.changed("rolled-back");
                TransactionSynchronizationManager.getSynchronizations().forEach(hook ->
                        hook.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
                assertThat(received).containsExactly("committed");
            } finally { TransactionSynchronizationManager.clear(); }
        }
    }

    @Test void isolatesDisconnectedBrowsersAndReleasesClosedSubscriptions() throws Exception {
        var hub = new StoryAccountingEventHub();
        var failures = new AtomicInteger();
        var received = new ArrayList<String>();
        hub.subscribe(id -> { failures.incrementAndGet(); throw new IllegalStateException("closed AsyncContext"); });
        try (var subscription = hub.subscribe(received::add)) {
            hub.changed("one"); hub.heartbeat(); hub.changed("two");
            assertThat(received).containsExactly("one", "", "two");
            assertThat(failures).hasValue(1);
        }
        hub.changed("after-close");
        assertThat(received).containsExactly("one", "", "two");
    }
}
