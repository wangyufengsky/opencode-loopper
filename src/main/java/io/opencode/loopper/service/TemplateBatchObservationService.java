package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Background samples track observed content separately from candidate progress; refreshes are not progress. */
@Service
public class TemplateBatchObservationService {
    private final TemplateBatchRecoveryMapper ledger;
    private final TemplateCandidateSubmissionMapper receipts;
    private final TemplateTaskMapper batches;
    private final OpenCodeClient client;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public TemplateBatchObservationService(TemplateBatchRecoveryMapper ledger, TemplateCandidateSubmissionMapper receipts,
            TemplateTaskMapper batches, OpenCodeClient client, ObjectMapper json, PlatformTransactionManager manager) {
        this.ledger = ledger; this.receipts = receipts; this.batches = batches; this.client = client; this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    public void sample(TemplateTaskBatchRow row, OpenCodeClient.OpenCodeSession remote, OpenCodeClient.SessionStatus status) {
        Instant now = Instant.now();
        var old = ledger.observation(row.id()).orElse(null);
        if (old != null && Objects.equals(old.promptSha256(), row.promptSha256())
                && Instant.parse(old.observedAt()).plusSeconds(15).isAfter(now)) return;
        String fingerprint = null;
        boolean connected = false;
        try {
            if (status != null) {
                var transcript = client.sessionTranscript(remote);
                fingerprint = transcript.activityFingerprint() == null
                        ? TemplateGitEvidenceCollector.hash(json.writeValueAsString(transcript)) : transcript.activityFingerprint();
                connected = true;
            }
        } catch (RuntimeException unavailable) { /* An unreadable sample is not Session failure or progress. */ }
        save(row, old, now, fingerprint, connected, status);
    }

    private void save(TemplateTaskBatchRow expected, TemplateBatchRecoveryMapper.Observation old, Instant now,
            String fingerprint, boolean connected, OpenCodeClient.SessionStatus status) {
        transactions.executeWithoutResult(ignored -> {
            var row = batches.findBatch(expected.id()).orElseThrow();
            if (row.version() != expected.version() || !row.state().equals("RUNNING")) return;
            var receipt = receipts.latest(row.id()).orElse(null);
            String progress = receipt == null ? row.promptSha256() : receipt.candidateSha256() + ":" + receipt.accepted();
            boolean reset = old == null || !Objects.equals(old.promptSha256(), row.promptSha256());
            String activityAt = connected && (reset || !Objects.equals(old.fingerprint(), fingerprint)) ? now.toString()
                    : reset ? null : old.lastActivityAt();
            String progressAt = reset || !Objects.equals(old.progressFingerprint(), progress)
                    ? receipt == null ? now.toString() : receipt.createdAt() : old.lastProgressAt();
            ledger.observe(new TemplateBatchRecoveryMapper.Observation(row.id(), row.sessionId(), row.promptSha256(),
                    now.toString(), activityAt, progressAt, fingerprint == null && !reset ? old.fingerprint() : fingerprint,
                    progress, status == null ? null : status.state(), connected ? 1 : 0));
        });
    }
}
