package io.opencode.loopper.workflow;

import io.opencode.loopper.verification.ProcessCommandPolicy;
import java.util.*;

/** One bounded direct command against one immutable input tree; retry belongs to the node attempt. */
public record WorkflowCommandVerification(int version, String inputName, List<String> argv,
                                          int timeoutSeconds, String purpose, String outputContains) {
    public static final String MODULE = "system.verify.command", ADAPTER = "system.verify.command.v1";
    public WorkflowCommandVerification { argv = argv == null ? List.of() : List.copyOf(argv); }
    public void validate() { validate(false); }
    public void validate(boolean allowUnconfigured) {
        if (version != 1 || inputName == null || !inputName.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                || !allowUnconfigured && argv.isEmpty() || argv.size() > 128 || argv.stream().anyMatch(value -> value.isBlank() || value.length() > 4096 || value.indexOf('\0') >= 0)
                || argv.stream().mapToInt(value -> value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum() > 32768
                || timeoutSeconds < 1 || timeoutSeconds > 3600 || purpose == null || !Set.of("TEST", "CHECK").contains(purpose)
                || outputContains != null && (outputContains.isBlank() || outputContains.length() > 4000))
            throw invalid("请选择代码输入，配置直接命令及 1–3600 秒时限。");
        if (argv.isEmpty()) return; // A planning placeholder still cannot pass runtime admission.
        if (ProcessCommandPolicy.directCommandError(argv) != null) throw invalid("请按参数分别填写直接执行命令，不能填写 Shell 片段或启动 Shell。");
        if (purpose.equals("TEST")) {
            var assessment = ProcessCommandPolicy.assessTestCommand(argv);
            if (!assessment.recognized() || assessment.skipped()) throw invalid("测试验证需要受支持的测试框架命令，不能跳过测试或忽略缺失测试。普通检查脚本请选择 CHECK（程序检查），不要标为 TEST。");
        }
        // A reused Gradle daemon lies outside this command's owned process tree.
        String executable = argv.getFirst().replace('\\', '/').toLowerCase(Locale.ROOT);
        executable = executable.substring(executable.lastIndexOf('/') + 1);
        if (Set.of("gradle", "gradlew", "gradle.bat", "gradlew.bat", "gradle.cmd", "gradlew.cmd", "gradle.exe", "gradlew.exe").contains(executable)
                && (!argv.contains("--no-daemon") || argv.contains("--daemon"))) throw invalid("流程中的 Gradle 检查须添加 --no-daemon，确保本次进程能够独立停止。");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
