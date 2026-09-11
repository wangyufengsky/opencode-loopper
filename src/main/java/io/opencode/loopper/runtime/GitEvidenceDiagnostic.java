package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Only known diagnostic categories cross the transport boundary; raw Git stderr may contain secrets. */
public record GitEvidenceDiagnostic(String code, String message) {
    public static GitEvidenceDiagnostic classify(String stderr) {
        String error = stderr.toLowerCase(Locale.ROOT);
        if (error.contains("unknown option") || error.contains("unrecognized argument") || error.contains("unknown switch"))
            return new GitEvidenceDiagnostic("TEMPLATE_GIT_COMMAND_UNSUPPORTED", "当前 Git 不支持采集命令的参数，请检查实际调用的 Git 版本");
        if (error.contains("authentication failed") || error.contains("permission denied (publickey")
                || error.contains("could not read username") || error.contains("could not read password"))
            return new GitEvidenceDiagnostic("TEMPLATE_GIT_AUTH_FAILED", "Git 身份认证失败，请检查启动程序所用账号的凭据或 SSH 配置");
        if (error.contains("dubious ownership") || error.contains("safe.directory") || error.contains("safe.barerepository"))
            return new GitEvidenceDiagnostic("TEMPLATE_GIT_REPOSITORY_UNSAFE", "Git 拒绝访问当前仓库，请核对仓库所有者与信任配置");
        if (error.contains("could not resolve host") || error.contains("connection refused") || error.contains("failed to connect")
                || error.contains("ssl certificate problem") || error.contains("unable to access"))
            return new GitEvidenceDiagnostic("TEMPLATE_GIT_REMOTE_UNAVAILABLE", "Git 远程连接失败，请检查网络、代理或证书配置");
        if (error.contains("couldn't find remote ref") || error.contains("unknown revision") || error.contains("needed a single revision"))
            return new GitEvidenceDiagnostic("TEMPLATE_GIT_REF_MISSING", "Git 分支或提交不存在，请刷新分支列表后重新发起");
        if (error.contains("not a git repository") || error.contains("bad object") || error.contains("invalid object"))
            return new GitEvidenceDiagnostic("TEMPLATE_GIT_REPOSITORY_INVALID", "Git 仓库或对象无法读取，请检查项目路径和仓库完整性");
        if (error.contains("permission denied") || error.contains("read-only file system") || error.contains("no space left on device"))
            return new GitEvidenceDiagnostic("TEMPLATE_GIT_IO_FAILED", "Git 文件读写失败，请检查任务目录权限、只读状态与磁盘空间");
        return new GitEvidenceDiagnostic("TEMPLATE_GIT_FAILED", "Git 命令执行失败，未获得可安全展示的具体原因；请按下列操作和退出码排查");
    }

    public TaskFailure failure(List<String> arguments, int exitCode) {
        Set<String> commands = Set.of("init", "fetch", "log", "show", "remote", "rev-parse", "ls-tree", "for-each-ref",
                "check-attr", "check-mailmap", "read-tree", "merge-base", "merge-recursive", "ls-files", "add", "write-tree", "diff", "--version");
        String operation = arguments.stream().filter(commands::contains).findFirst().orElse("仓库操作");
        return new TaskFailure(code, message + "（操作：git " + operation + "；退出码：" + exitCode + "）");
    }
}
