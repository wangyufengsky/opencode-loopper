package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitSnapshotInventoryTest {
    private static final String SHA = "a".repeat(40);
    @Test void preservesLimitationsAndGitNamesWithoutTreatingThemAsShellOrWhitespaceSyntax() {
        var files = GitSnapshotInventory.parse(entry("100644", "blob", "7", "source with spaces.txt")
                + entry("100755", "blob", "0", "$(do-not-run).sh")
                + entry("120000", "blob", "10", "linked-source")
                + entry("160000", "commit", "-", "submodule")
                + entry("100644", "blob", "2000001", "large.txt")
                + entry("100644", "blob", "4", ".env")
                + entry("100644", "blob", "4", ".env.example")
                + entry("100644", "blob", "4", "line\nbreak.txt"));
        assertThat(files).hasSize(8);
        assertThat(files.getFirst().path()).isEqualTo("source with spaces.txt");
        assertThat(files.getFirst().limitation()).isNull();
        assertThat(files.get(1).limitation()).isNull();
        assertThat(files.get(2).limitation()).contains("符号链接");
        assertThat(files.get(3).limitation()).contains("子模块");
        assertThat(files.get(4).limitation()).contains("上限");
        assertThat(files.get(5).limitation()).contains("受保护");
        assertThat(files.get(6).limitation()).isNull();
        assertThat(files.get(7).limitation()).contains("受保护");
    }
    @Test void rejectsTruncationWarningsDuplicateNamesAndMissingPaths() {
        String valid = entry("100644", "blob", "7", "source.txt");
        for (String invalid : new String[] {null, valid.stripTrailing().substring(0, valid.length()-1),
                "warning: diagnostic\n" + valid, valid + valid, entry("100644", "blob", "7", "")}) {
            assertInvalid(invalid);
        }
        assertThat(GitSnapshotInventory.parse("")).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"-1", "-", "not-a-size", "9223372036854775808"})
    void refusesMalformedBlobSizes(String size) { assertInvalid(entry("100644", "blob", size, "source.txt")); }
    @Test void rejectsModeTypeMismatchAndInvalidHashes() {
        assertInvalid(entry("100644", "commit", "7", "source.txt"));
        assertInvalid(entry("160000", "blob", "-", "submodule"));
        assertInvalid(entry("160000", "commit", "7", "submodule"));
        assertInvalid(entry("040000", "tree", "-", "directory"));
        assertThatThrownBy(() -> GitSnapshotInventory.parse("100644 blob invalid 7\tsource.txt\0"))
                .isInstanceOfSatisfying(BadRequestException.class, error -> assertThat(error.code()).isEqualTo("DOCUMENT_CODE_SHA_INVALID"));
    }
    @Test void inventoryLimitFailsInsteadOfSilentlyDroppingSources() {
        String output = IntStream.range(0, 50001).mapToObj(i -> entry("100644", "blob", "0", "file-" + i))
                .collect(java.util.stream.Collectors.joining());
        assertThatThrownBy(() -> GitSnapshotInventory.parse(output)).isInstanceOfSatisfying(BadRequestException.class,
                error -> assertThat(error.code()).isEqualTo("DOCUMENT_CODE_MANIFEST_LIMIT"));
    }
    @ParameterizedTest @ValueSource(strings = {".ssh/config", "src/credentials", ".aws/config", "tls/host.key", ".ENV.production", "../escape", "a\\b", "/absolute"})
    void protectedFilesRemainListedWithoutReadQualification(String path) {
        assertThat(GitSnapshotInventory.parse(entry("100644", "blob", "3", path)).getFirst().limitation()).contains("受保护");
    }
    private static String entry(String mode, String type, String size, String path) {
        return mode + " " + type + " " + SHA + " " + size + "\t" + path + "\0";
    }
    private static void assertInvalid(String output) {
        assertThatThrownBy(() -> GitSnapshotInventory.parse(output)).isInstanceOfSatisfying(BadRequestException.class,
                error -> assertThat(error.code()).isEqualTo("DOCUMENT_CODE_MANIFEST_INVALID"));
    }
}
