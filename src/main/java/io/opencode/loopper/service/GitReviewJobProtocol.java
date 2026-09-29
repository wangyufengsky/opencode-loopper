package io.opencode.loopper.service;

import io.opencode.loopper.runtime.DurableCommandProtocol;
import io.opencode.loopper.template.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;

/** Immutable fixed-version capture intent, separate from process identity and source credentials. */
public final class GitReviewJobProtocol {
    private GitReviewJobProtocol() { }
    public record Input(GitSnapshotJobProtocol.Input source, String projectId, SnapshotReview.Mode mode,
                        String startDate, String endDate) {
        public Input {
            if (source == null || projectId == null || projectId.isBlank() || projectId.length() > 200
                    || projectId.chars().anyMatch(Character::isISOControl) || mode == null)
                throw new IllegalArgumentException("Explicit review source required");
            if (mode == SnapshotReview.Mode.FULL) {
                if (startDate != null || endDate != null) throw new IllegalArgumentException("Full review has no date filter");
            } else {
                if (startDate == null || endDate == null || !startDate.matches("\\d{4}-\\d{2}-\\d{2}")
                        || !endDate.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Explicit review dates required");
                TemplateDateRange.parse(startDate, endDate, Clock.systemUTC());
            }
        }
        public String nodeId() { return source.nodeId(); }
        public TemplateDateRange dates() { return mode == SnapshotReview.Mode.FULL ? null : TemplateDateRange.parse(startDate, endDate, Clock.systemUTC()); }
        public String scope(GitSnapshotJobProtocol.Binding binding) {
            return DurableCommandProtocol.hash((projectId + "\n" + binding.project() + "\n" + binding.prefix()).getBytes(StandardCharsets.UTF_8));
        }
    }
    public record Selection(GitSnapshotJobProtocol.Binding binding, String capturedAt) {
        public Selection { if (binding == null) throw new IllegalArgumentException(); Instant.parse(capturedAt); }
    }
    public record Frozen(Selection selection, SnapshotReview.Snapshot snapshot) { }
    public static byte[] input(Input value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1); byte[] source = GitSnapshotJobProtocol.input(value.source());
            out.writeInt(source.length); out.write(source); out.writeUTF(value.projectId()); out.writeUTF(value.mode().name());
            if (value.mode() == SnapshotReview.Mode.DATE_INCREMENTAL) { out.writeUTF(value.startDate()); out.writeUTF(value.endDate()); }
        }
        return bytes.toByteArray();
    }
    public static Input input(byte[] bytes) throws IOException {
        if (bytes.length > 32768) throw new IOException("Review input bound");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1) throw new IOException("Review input version");
            var source = GitSnapshotJobProtocol.input(segment(in)); String project = in.readUTF();
            var mode = SnapshotReview.Mode.valueOf(in.readUTF());
            var value = new Input(source, project, mode, mode == SnapshotReview.Mode.FULL ? null : in.readUTF(), mode == SnapshotReview.Mode.FULL ? null : in.readUTF());
            if (in.available() != 0) throw new IOException("Review input extra fields"); return value;
        }
    }
    public static byte[] selection(Selection value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1); byte[] binding = GitSnapshotJobProtocol.binding(value.binding());
            out.writeInt(binding.length); out.write(binding); out.writeUTF(value.capturedAt());
        }
        return bytes.toByteArray();
    }
    public static Selection selection(byte[] bytes) throws IOException {
        if (bytes.length > 32768) throw new IOException("Review selection bound");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1) throw new IOException("Review selection version");
            var value = new Selection(GitSnapshotJobProtocol.binding(segment(in)), in.readUTF());
            if (in.available() != 0) throw new IOException("Review selection extra fields"); return value;
        }
    }
    private static byte[] segment(DataInputStream in) throws IOException {
        int count = in.readInt(); if (count < 0 || count > 32768) throw new IOException("Review record bound");
        byte[] bytes = in.readNBytes(count); if (bytes.length != count) throw new EOFException(); return bytes;
    }
    public static void requireBinding(Input input, String hash, Frozen value) throws IOException {
        var binding = value.selection().binding(); var snapshot = value.snapshot(); var dates = input.dates();
        if (!binding.inputSha256().equals(hash) || !snapshot.sourceSha().equals(binding.commit())
                || !snapshot.scopeIdentity().equals(input.scope(binding)) || !snapshot.capturedAt().equals(value.selection().capturedAt())
                || !java.util.Objects.equals(snapshot.startInclusive(), dates == null ? null : dates.startInclusive().toString())
                || !java.util.Objects.equals(snapshot.endExclusive(), dates == null ? null : dates.endExclusive().toString())
                || !snapshot.selectionBasis().equals(dates == null ? "FROZEN_BRANCH_TIP" : "FIRST_PARENT_COMMITTER_TIME")
                || dates == null && (snapshot.baselineSha() != null || snapshot.baselineTree() != null || !snapshot.targetSha().equals(binding.commit()))
                || dates != null && (snapshot.baselineSha() == null || snapshot.baselineTree() == null))
            throw new IOException("Review evidence binding differs");
    }
}
