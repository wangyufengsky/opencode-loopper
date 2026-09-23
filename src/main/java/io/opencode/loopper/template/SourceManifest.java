package io.opencode.loopper.template;

import java.util.List;

/** A complete bounded inventory; excluded entries remain visible and never become successful work. */
public record SourceManifest(String projectRoot, String sourcePath, String sha256, List<File> files) {
    public SourceManifest { files = List.copyOf(files); }
    public long targetCount() { return files.stream().filter(File::processable).count(); }
    public record File(String path, boolean target, long sizeBytes, String sha256, String exclusion) {
        public boolean processable() { return target && exclusion == null; }
    }
}
