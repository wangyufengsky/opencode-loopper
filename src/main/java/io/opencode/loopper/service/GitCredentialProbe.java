package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.runtime.GitHttpAuthentication;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Read-only, bounded Git authentication test; draft credentials are never saved. */
@Service
public class GitCredentialProbe {
    private final GitCredentialService credentials;
    private final GitEvidenceProcess git;
    private final Path directory;
    public GitCredentialProbe(GitCredentialService credentials, GitEvidenceProcess git, LoopperProperties properties) {
        this.credentials = credentials; this.git = git; this.directory = properties.getDataDir();
    }
    public record Result(boolean success, String message) { }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result test(String projectId, GitCredentialService.Request request) {
        Path working = projectId == null ? directory : credentials.projectRoot(projectId);
        String url = request == null ? null : request.repositoryUrl();
        if ((url == null || url.isBlank()) && projectId != null) url = git.read(working, "remote", "get-url", "--", "origin").strip();
        if (url == null || url.isBlank() || url.length() > 2048) throw new BadRequestException("GIT_CREDENTIAL_TEST_URL_REQUIRED", "请填写要验证的完整 HTTP(S) 仓库地址");
        GitHttpAuthentication.origin(url);
        var environment = credentials.testEnvironment(projectId, request, url);
        var result = git.run(working, Duration.ofSeconds(30), List.of("ls-remote", "--symref", "--", url, "HEAD"), environment);
        return result.exitCode() == 0 ? new Result(true, "连接成功，当前账号可读取该仓库；未执行推送")
                : new Result(false, result.diagnostic().message());
    }
}
