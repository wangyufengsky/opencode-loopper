package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageDesignEvidencePreparationTest {
    @TempDir Path root;

    @Test void hashesFullSourceAndLeavesMissingFactsUnconfirmed() throws Exception {
        Files.writeString(root.resolve("test.java"), "中文😀");
        var snapshot = PackageDesignEvidencePreparation.prepare(root, "R1", "原始需求😀", List.of("test.java", "missing.java"));
        assertThat(snapshot.requirementSourceRef()).isEqualTo("requirement:R1");
        assertThat(snapshot.requirementSha256()).hasSize(64);
        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.files().getFirst().excerpt()).isEqualTo("中文😀");
        assertThat(snapshot.files().getFirst().sha256()).hasSize(64);
        assertThat(snapshot.files().get(1).status()).isEqualTo("UNCONFIRMED");
        assertThat(snapshot.files().get(1).sha256()).isNull();
    }

    @Test void boundsBytesAndScopeWithoutSilentlyTruncatingEvidence() throws Exception {
        Files.writeString(root.resolve("large.java"), "中".repeat(2000));
        var snapshot = PackageDesignEvidencePreparation.prepare(root, "R1", "text", List.of("large.java", "../outside", "*.java"));
        assertThat(snapshot.files()).extracting(PackageDesignEvidencePreparation.FileEvidence::status)
                .containsExactly("OVER_BYTE_LIMIT", "OUTSIDE_BOUNDED_SCOPE", "OUTSIDE_BOUNDED_SCOPE");
        assertThat(snapshot.files()).allSatisfy(file -> assertThat(file.excerpt()).isNull());
        var many = PackageDesignEvidencePreparation.prepare(root, "R1", "text", java.util.Collections.nCopies(20, "missing"));
        assertThat(many.files()).hasSize(16);
        assertThat(many.complete()).isFalse();
    }

    @Test void rejectsSymlinkAndInvalidUtf8RatherThanReadingOutsideOrReplacingBytes() throws Exception {
        Files.write(root.resolve("invalid"), new byte[]{(byte) 0xff});
        Files.createSymbolicLink(root.resolve("link"), root.resolve("invalid"));
        var snapshot = PackageDesignEvidencePreparation.prepare(root, "R1", "text", List.of("link", "invalid"));
        assertThat(snapshot.files()).extracting(PackageDesignEvidencePreparation.FileEvidence::status)
                .containsExactly("SYMLINK_NOT_READ", "UNCONFIRMED");
    }

    @Test void preparationDoesNotBypassDesignerReadPermissions() throws Exception {
        Files.writeString(root.resolve(".env"), "synthetic secret");
        Files.writeString(root.resolve(".env.local"), "synthetic secret");
        Files.writeString(root.resolve(".env.example"), "EXAMPLE=placeholder");
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "synthetic credential");
        var snapshot = PackageDesignEvidencePreparation.prepare(root, "R1", "text", List.of(".env", ".env.local", ".git/config", ".env.example", ".ENV"));
        assertThat(snapshot.files()).extracting(PackageDesignEvidencePreparation.FileEvidence::status)
                .containsExactly("READ_PERMISSION_DENIED", "READ_PERMISSION_DENIED", "READ_PERMISSION_DENIED", "READ", "READ_PERMISSION_DENIED");
        assertThat(snapshot.files().subList(0, 3)).allSatisfy(file -> assertThat(file.excerpt()).isNull());
    }
}
