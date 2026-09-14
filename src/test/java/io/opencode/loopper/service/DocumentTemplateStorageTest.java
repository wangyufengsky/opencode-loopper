package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.service.assist.AssistDocumentParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class DocumentTemplateStorageTest {
    @TempDir Path temporary;
    private DocumentTemplateStorage storage() throws Exception {
        var properties = new LoopperProperties(); properties.setDataDir(temporary.toRealPath());
        return new DocumentTemplateStorage(properties, new AssistDocumentParser(), new ObjectMapper());
    }
    @Test void longRequirementsRemainCompleteAndSegmentedBeyondLegacyContextLimits() throws Exception {
        String text = "# 订单需求\n" + "订单提交必须校验金额大于零。\n".repeat(12000);
        try (var holder = new StorageHolder(storage())) {
            var prepared = holder.value.prepare(List.of(file("requirements.md", text))).getFirst();
            assertThat(prepared.bytes().length).isGreaterThan(128 * 1024);
            assertThat(prepared.document().sections()).hasSizeGreaterThan(1);
            assertThat(prepared.document().sections().stream().map(AssistDocumentParser.Section::markdown)
                    .reduce("", String::concat)).isEqualTo(text);
            assertThat(prepared.document().sections()).allMatch(section -> section.markdown().length() <= 12000);
        }
    }
    @Test void immutableOriginalIsVerifiedAndCannotBeOverwrittenOrReadThroughSymlink() throws Exception {
        try (var holder = new StorageHolder(storage())) {
            var store = holder.value;
            byte[] content = "# 需求\n审批必须鉴权".getBytes(StandardCharsets.UTF_8);
            String sha = DocumentTemplateStorage.hash(content);
            String path = store.relativePath(UUID.randomUUID().toString(), UUID.randomUUID().toString());
            store.save(path, content, sha); store.save(path, content, sha);
            assertThat(store.read(path, sha)).isEqualTo(content);
            assertThatThrownBy(() -> store.save(path, new byte[]{1}, sha)).isInstanceOf(BadRequestException.class);
            Path target = temporary.toRealPath().resolve("document-templates").resolve(path);
            Files.writeString(target, "changed");
            assertThatThrownBy(() -> store.read(path, sha)).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> store.read("../outside", sha)).isInstanceOf(BadRequestException.class);
        }
    }
    @Test void rejectsEntireBatchWithInvalidFileOrUnsafeName() throws Exception {
        try (var holder = new StorageHolder(storage())) {
            assertThatThrownBy(() -> holder.value.prepare(List.of(file("ok.md", "ok"), file("old.doc", "legacy"))))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("旧 DOC");
            assertThatThrownBy(() -> holder.value.prepare(List.of(file("../input.md", "text"))))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> holder.value.prepare(List.of(file("empty.md", ""))))
                    .isInstanceOf(BadRequestException.class);
            assertThat(Files.exists(temporary.resolve("document-templates"))).isFalse();
        }
    }
    private static DocumentTemplateStorage.Incoming file(String name, String text) {
        return new DocumentTemplateStorage.Incoming(name, text.getBytes(StandardCharsets.UTF_8));
    }
    private record StorageHolder(DocumentTemplateStorage value) implements AutoCloseable {
        @Override public void close() { value.close(); }
    }
}
