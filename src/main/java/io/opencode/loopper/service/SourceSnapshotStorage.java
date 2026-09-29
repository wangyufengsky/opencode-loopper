package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.ImmutableContentStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Source adapter keeps the historical directory, byte limit and failure code. */
@Component
public final class SourceSnapshotStorage {
    private final ImmutableContentStore content;
    public SourceSnapshotStorage(LoopperProperties properties) {
        content = new ImmutableContentStore(properties.getDataDir().resolve("source-templates"));
    }
    public Path directory(String id) {
        try { return content.directory(id); }
        catch (ImmutableContentStore.StorageFailure failure) { throw invalid(); }
    }
    public void write(String id, Map<String, byte[]> contents) {
        try { contents.forEach((hash, bytes) -> content.write(id, hash, bytes, SourceTreeCapture.MAX_FILE_BYTES)); }
        catch (ImmutableContentStore.StorageFailure failure) { throw invalid(); }
    }
    public String read(String id, String hash) {
        try { return new String(content.read(id, hash, -1, SourceTreeCapture.MAX_FILE_BYTES), StandardCharsets.UTF_8); }
        catch (ImmutableContentStore.StorageFailure failure) { throw invalid(); }
    }
    private static ConflictException invalid() {
        return new ConflictException("SOURCE_SNAPSHOT_STORAGE_INVALID", "冻结源码存储不可用或校验失败，保留原记录，请检查运行目录");
    }
}
