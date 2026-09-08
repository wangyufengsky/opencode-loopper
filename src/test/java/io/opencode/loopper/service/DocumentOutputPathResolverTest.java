package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class DocumentOutputPathResolverTest {
    @Test void referencesNeverOverrideTheDeclaredOutput() {
        assertThat(DocumentOutputPathResolver.resolve("参考资料：`README.md`。最终交付文件：`docs/design.md`。", false))
                .isEqualTo("docs/design.md");
        assertThat(DocumentOutputPathResolver.resolve("参考 `docs/source.md`\n## 输出位置与格式\n- 单文件 `docs/详细设计文档.md`\n## 资料\n参考 `docs/guide.md`", false))
                .isEqualTo("docs/详细设计文档.md");
        assertThat(DocumentOutputPathResolver.resolve("Source: `docs/example.docx`\nOutput: `docs/result.docx`", true))
                .isEqualTo("docs/result.docx");
        assertThat(DocumentOutputPathResolver.resolve("输出：`docs/design.md`\n## 假设\n假定 `docs/design.md` 文件名可接受（如用户偏好 `DESIGN.md` 可改名）。", false))
                .isEqualTo("docs/design.md");
    }

    @Test void aMissingTargetUsesAnExplicitDefaultInsteadOfOverwritingAReference() {
        assertThat(DocumentOutputPathResolver.resolve("参考 `README.md`，撰写设计文档。", false)).isEqualTo("output/document.md");
        assertThat(DocumentOutputPathResolver.resolve("撰写设计文档。", true)).isEqualTo("output/document.docx");
        assertThat(DocumentOutputPathResolver.resolve("编写文档 `docs/design.md`", false)).isEqualTo("docs/design.md");
    }

    @Test void ambiguousUnsafeOrMismatchedTargetsRequireCorrection() {
        for (String requirement : java.util.List.of("输出 `docs/a.md`\n输出 `docs/b.md`", "输出 `../a.md`",
                "输出 `/tmp/a.md`", "输出 `docs/design.docx`")) {
            assertThatThrownBy(() -> DocumentOutputPathResolver.resolve(requirement, false)).isInstanceOf(BadRequestException.class);
        }
    }
}
