package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CandidatePromptDispatchBarrierTest {
    @Test
    void runTerminationWinsAtomicallyAgainstNewDispatchAndWaitsForExistingIo() {
        CandidatePromptDispatchBarrier barrier = new CandidatePromptDispatchBarrier();
        CandidatePromptDispatchBarrier.Ticket active = barrier.begin("run-1");

        assertThat(active.acquired()).isTrue();
        assertThat(barrier.prepareTermination("run-1")).isFalse();
        assertThat(barrier.begin("run-1").acquired()).isFalse();

        active.close();
        assertThat(barrier.prepareTermination("run-1")).isTrue();
        barrier.complete("run-1");
        assertThat(barrier.begin("run-1").acquired()).isTrue();
    }
}
