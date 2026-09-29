package io.opencode.loopper.workflow;

import java.util.List;

/** User supplied source documents have their own immutable identity, independent of node attempts. */
public final class WorkflowUpload {
    public static final String TYPE = "UPLOADED_DOCUMENTS";
    private WorkflowUpload() { }
    public record Reference(int version, String type, String uploadId, String sha256) { }
    public record Original(String filename, String path, long sizeBytes, String sha256,
                           String representationSha256, String format, int sections, List<String> limitations) { }
    public record File(String path, long sizeBytes, String sha256) { }
    public record Manifest(int version, String type, String parserVersion, List<Original> originals, List<File> files) { }
    public record Summary(String id, String createdAt, boolean ready, Reference reference,
                          String parserVersion, List<Original> originals, Resume resume) { }
    public record Resume(String requestKey,long expectedVersion,int expectedRevision) { }
}
