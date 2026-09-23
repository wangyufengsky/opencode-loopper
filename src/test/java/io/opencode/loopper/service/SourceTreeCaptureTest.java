package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.template.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceTreeCaptureTest {
    @TempDir Path temporary;
    @Test void capturesUncommittedSourceAndContextWithExplicitExclusionsAndStableIdentity() throws Exception {
        Path root = temporary.toRealPath();
        write(root, "src/main/java/example/Service.java", "class Service { int value() { return 1; } }");
        write(root, "src/test/java/example/ServiceTest.java", "class ServiceTest {}");
        write(root, "src/main/java/example/.env", "SECRET=never-read");
        write(root, "target/classes/Service.class", "generated");
        write(root, "pom.xml", "<project/>");
        Files.createSymbolicLink(root.resolve("src/main/java/example/link.java"), root.resolve("pom.xml"));
        var properties = new LoopperProperties(); properties.setDataDir(root.resolve("runtime"));
        var scanner = new SourceTreeCapture(properties);
        var parameters = new SourceTemplateParameters(root.toString(), "src/main/java", null, null, "");
        var captured = scanner.capture(parameters, true);
        assertThat(captured.manifest().targetCount()).isEqualTo(1);
        assertThat(captured.manifest().files()).anySatisfy(file -> {
            assertThat(file.path()).isEqualTo("src/main/java/example/.env"); assertThat(file.sha256()).isNull();
            assertThat(file.exclusion()).contains("受保护");
        }).anySatisfy(file -> { assertThat(file.path()).isEqualTo("src/main/java/example/link.java"); assertThat(file.exclusion()).contains("符号链接"); });
        assertThat(captured.contents().values()).allSatisfy(bytes -> assertThat(new String(bytes)).doesNotContain("SECRET"));
        assertThat(scanner.capture(parameters, true).manifest().sha256()).isEqualTo(captured.manifest().sha256());
        write(root, "src/main/java/example/Service.java", "class Service { int value() { return 2; } }");
        assertThat(scanner.capture(parameters, true).manifest().sha256()).isNotEqualTo(captured.manifest().sha256());
    }
    @Test void singleFileScopeAndTestContextDoNotBecomeAdditionalTargets() throws Exception {
        Path root = temporary.toRealPath();
        write(root, "module/src/main/java/A.java", "class A {}");
        write(root, "module/src/main/java/B.java", "class B {}");
        write(root, "module/src/test/java/ATest.java", "class ATest {}");
        var scanner = new SourceTreeCapture(new LoopperProperties());
        var single = scanner.capture(new SourceTemplateParameters(root.toString(), "module/src/main/java/A.java", null, null, ""), true);
        assertThat(single.manifest().files().stream().filter(SourceManifest.File::processable)).extracting(SourceManifest.File::path)
                .containsExactly("module/src/main/java/A.java");
        var whole = scanner.capture(new SourceTemplateParameters(root.toString(), ".", null, null, ""), true);
        assertThat(whole.manifest().files()).anySatisfy(file -> {
            assertThat(file.path()).endsWith("ATest.java"); assertThat(file.exclusion()).contains("已有测试"); assertThat(file.sha256()).isNotNull();
        });
    }
    @Test void containmentRejectsTraversalSymlinksAndExternalAbsolutePaths() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")).toRealPath();
        Files.createSymbolicLink(root.resolve("outside"), temporary);
        assertThatThrownBy(() -> SourcePathPolicy.resolve(root, "../secret", false)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> SourcePathPolicy.resolve(root, temporary.toString(), true)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> SourcePathPolicy.resolve(root, "outside", true)).isInstanceOf(BadRequestException.class);
    }
    private static void write(Path root, String relative, String content) throws Exception {
        Path target = root.resolve(relative); Files.createDirectories(target.getParent()); Files.writeString(target, content);
    }
}
