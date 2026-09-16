package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.time.Instant;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** One bounded remote step per coordinator pass, with durable intent before I/O and proof before completion. */
@Service
public class TemplateBatchFinalization {
    private final TemplateBatchRecoveryStore store;
    private final TemplateBatchRecoveryMapper ledger;
    private final TemplateCandidateSubmissionMapper receipts;
    private final TemplateBatchObservationService observations;
    private final OpenCodeClient client;

    public TemplateBatchFinalization(TemplateBatchRecoveryStore store, TemplateBatchRecoveryMapper ledger,
            TemplateCandidateSubmissionMapper receipts, TemplateBatchObservationService observations, OpenCodeClient client) {
        this.store = store; this.ledger = ledger; this.receipts = receipts; this.observations = observations; this.client = client;
    }

    public Poll poll(TemplateTaskBatchRow row, OpenCodeClient.OpenCodeSession remote, Function<String, String> validate) {
        var accepted = receipts.accepted(row.id());
        var intent = ledger.find(row.id()).orElse(null);
        if (intent == null && accepted.isPresent()) intent = store.accepted(row);
        if (intent != null) {
            store.current(row); store.requireIntent(row);
            if (CandidateSessionTerminationProof.persisted(intent.proof())) return finish(row, intent, validate);
        }
        OpenCodeClient.SessionStatus status;
        try { status = client.sessionStatus(remote); }
        catch (RuntimeException unavailable) {
            observations.sample(row, remote, null);
            intent = ledger.find(row.id()).orElse(null);
            if (intent == null && receipts.accepted(row.id()).isPresent()) intent = store.accepted(row);
            if (intent == null) throw unavailable;
            return recover(row, remote, intent, null, validate);
        }
        observations.sample(row, remote, status);
        // A manual stop or MCP receipt may commit while the remote observation is in flight.
        intent = ledger.find(row.id()).orElse(null);
        if (intent == null && receipts.accepted(row.id()).isPresent()) intent = store.accepted(row);
        return intent == null ? new Poll(status, null) : recover(row, remote, intent, status, validate);
    }

    private Poll recover(TemplateTaskBatchRow row, OpenCodeClient.OpenCodeSession remote,
            TemplateBatchRecoveryMapper.Recovery intent, OpenCodeClient.SessionStatus status, Function<String, String> validate) {
        if (status != null && (status.completed() || status.failed())) {
            store.proof(row, CandidateSessionTerminationProof.REMOTE_COMPLETED);
            return finish(row, intent, validate);
        }
        Instant now = Instant.now();
        if (Instant.parse(intent.notBefore()).isAfter(now)
                || intent.lastAttemptAt() != null && Instant.parse(intent.lastAttemptAt()).plusSeconds(15).isAfter(now))
            return new Poll(status, row);
        store.attempted(row, "TEMPLATE_STOP_UNCONFIRMED");
        CandidateSessionTerminationProof proof;
        try { proof = CandidateSessionTerminationProof.from(client.abortWithConfirmation(remote)); }
        catch (RuntimeException unknown) { return new Poll(status, row); }
        store.proof(row, proof);
        return finish(row, intent, validate);
    }

    private Poll finish(TemplateTaskBatchRow row, TemplateBatchRecoveryMapper.Recovery intent, Function<String, String> validate) {
        String output = intent.action().equals("FINALIZE") ? validate.apply(receipts.accepted(row.id()).orElseThrow()) : null;
        return new Poll(null, store.finish(row, output));
    }

    public record Poll(OpenCodeClient.SessionStatus status, TemplateTaskBatchRow handled) { }
}
