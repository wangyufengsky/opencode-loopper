package io.opencode.loopper.service.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.service.BadRequestException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class RoleArchiveTest {
    private final RoleArchive archive = new RoleArchive();

    @Test
    void importsAnExactPromptFragmentWithoutAllowingWorkflowSlotDefinitions() throws IOException {
        byte[] bytes = zip("manifest.yaml", """
                schemaVersion: 1
                roles:
                  - roleId: custom.reviewer
                    displayName: 自定义评审员
                    groupKey: reviewer
                    groupLabel: 评审员
                    allowedSlots: [REVIEWER_READ_ONLY]
                    permissionMode: INTERSECT
                    nativeTools: [read]
                    mcpTools: ['@loopper-assist/read_project']
                    requiredMcpTools: ['@loopper-assist/read_project']
                    modelPolicy: INHERIT_WORKFLOW
                    prompts:
                      machine-role.judge: prompts/machine-role.judge.md
                """, "prompts/machine-role.judge.md", "审查本次证据。\n");

        RoleArchive.Parsed parsed = archive.parse(bytes);

        assertThat(parsed.manifest().roles()).hasSize(1);
        assertThat(parsed.manifest().roles().getFirst().requiredMcpTools())
                .containsExactly("@loopper-assist/read_project");
        assertThat(parsed.fragmentsByRole().get("custom.reviewer"))
                .containsEntry("machine-role.judge", "审查本次证据。\n");
        assertThat(parsed.sourceSha256()).hasSize(64);
    }

    @Test
    void rejectsUnreferencedAndTraversalEntries() throws IOException {
        byte[] bytes = zip("manifest.yaml", "schemaVersion: 1\nroles: []\n", "../escape.md", "payload");
        assertThatThrownBy(() -> archive.parse(bytes)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsUnknownModelPolicyAndRequiredToolOutsideTheGrantList() {
        String yaml = """
                schemaVersion: 1
                roles:
                  - roleId: custom.reviewer
                    displayName: 自定义评审员
                    groupKey: reviewer
                    groupLabel: 评审员
                    allowedSlots: [REVIEWER_READ_ONLY]
                    permissionMode: INTERSECT
                    mcpTools: []
                    requiredMcpTools: ['@loopper-assist/read_project']
                    modelPolicy: PIN_MODEL
                """;
        assertThatThrownBy(() -> archive.parseYaml(yaml)).isInstanceOf(BadRequestException.class);
    }

    private static byte[] zip(String firstName, String first, String secondName, String second) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(firstName));
            zip.write(first.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(secondName));
            zip.write(second.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
