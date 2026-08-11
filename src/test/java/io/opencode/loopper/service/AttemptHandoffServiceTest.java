package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttemptHandoffServiceTest {
    @TempDir Path temp;

    @Test
    void boundedDigestCountsBytesActuallyReadInsteadOfTrustingADeclaredSize() throws Exception {
        byte[] content = new byte[17];

        assertThatThrownBy(() -> AttemptHandoffService.digestBounded(
                new ByteArrayInputStream(content), MessageDigest.getInstance("SHA-256"), new byte[8], 16))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void changedFileMetadataMakesACompletedReadUnreliable() throws Exception {
        Path file = temp.resolve("changing.txt");
        Files.writeString(file, "before");
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Files.writeString(file, "after-content");
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

        assertThat(AttemptHandoffService.unchangedDuringRead(before, after, before.size())).isFalse();
    }
}
