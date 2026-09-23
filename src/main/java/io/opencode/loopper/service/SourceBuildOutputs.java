package io.opencode.loopper.service;

import io.opencode.loopper.runtime.SafeProcessRunner;
import io.opencode.loopper.template.SourceTestProfile;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

/** Build products must already be excluded from Git delivery; the template cannot edit ignore/build configuration. */
@Service
public final class SourceBuildOutputs {
    private final SafeProcessRunner runner;
    public SourceBuildOutputs(SafeProcessRunner runner) { this.runner = runner; }
    public void validate(Path root, SourceTestProfile profile, Collection<String> configurationPaths) {
        var git = runner.run(root, List.of("git", "rev-parse", "--is-inside-work-tree"), Duration.ofSeconds(10));
        if (git.timedOut()) throw unavailable("无法确认项目的 Git 构建输出范围");
        if (git.exitCode() != 0) return;
        var outputs = new TreeSet<String>();
        for (var path : configurationPaths) {
            String parent = SourceTestProfileService.parent(path);
            if (path.endsWith("pom.xml")) outputs.add(SourceTestProfileService.join(parent, "target"));
            if (path.endsWith("build.gradle") || path.endsWith("build.gradle.kts")) {
                outputs.add(SourceTestProfileService.join(parent, "build"));
                outputs.add(SourceTestProfileService.join(parent, ".gradle"));
            }
        }
        for (var module : profile.modules()) if (Set.of("jest", "vitest").contains(module.framework()))
            outputs.add(SourceTestProfileService.join(module.root(), "coverage"));
        if (outputs.size() > 128) throw unavailable("构建输出目录超过本次安全预检容量，请登记更小的项目范围");
        for (var output : outputs) {
            var tracked = runner.run(root, List.of("git", "ls-files", "-z", "--", output), Duration.ofSeconds(10));
            var ignored = runner.run(root, List.of("git", "check-ignore", "--no-index", "--", output + "/.loopper-output-probe"), Duration.ofSeconds(10));
            if (tracked.timedOut() || tracked.exitCode() != 0 || tracked.outputTruncated() || ignored.timedOut())
                throw unavailable("无法核对构建输出目录：" + output);
            if (!tracked.output().isBlank() || ignored.exitCode() != 0)
                throw unavailable("构建输出目录 " + output + " 尚未被 Git 完整忽略，或包含已跟踪文件；请先由项目维护者处理构建输出的交付规则，再重新预检");
        }
    }
    private static BadRequestException unavailable(String message) {
        return new BadRequestException("SOURCE_TEST_CONFIGURATION_REQUIRED", message + "。模板不会修改 .gitignore、依赖或构建配置");
    }
}
