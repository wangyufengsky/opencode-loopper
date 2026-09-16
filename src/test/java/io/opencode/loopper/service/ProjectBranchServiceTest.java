package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.runtime.GitEvidenceDiagnostic;
import io.opencode.loopper.runtime.GitEvidenceProcess;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectBranchServiceTest {
    @Test void explicitLocalBranchDoesNotContactAnyRemote() {
        ProjectService projects = mock(ProjectService.class);
        ProjectRow project = mock(ProjectRow.class);
        GitEvidenceProcess git = mock(GitEvidenceProcess.class);
        Path root = Path.of(".").toAbsolutePath();
        when(projects.get("p")).thenReturn(project); when(project.rootPath()).thenReturn(root.toString());
        when(git.read(root, "for-each-ref", "--format=%(refname)", "refs/heads/")).thenReturn("refs/heads/main\n");
        assertThat(new ProjectBranchService(projects, git).require("p", "local:refs/heads/main").remote()).isNull();
        verify(git).requireSupported(root);
        verify(git).read(root, "for-each-ref", "--format=%(refname)", "refs/heads/");
        verifyNoMoreInteractions(git);
    }

    @Test void remoteTimeoutKeepsLocalBranchesAndStopUncertaintyStillBlocks() {
        ProjectService projects = mock(ProjectService.class);
        GitEvidenceProcess git = mock(GitEvidenceProcess.class);
        Path root = Path.of(".").toAbsolutePath();
        when(git.read(root, "for-each-ref", "--format=%(refname)", "refs/heads/")).thenReturn("refs/heads/main\n");
        when(git.read(root, "remote")).thenReturn("origin\n");
        var args = List.of("ls-remote", "--symref", "--", "origin", "HEAD", "refs/heads/*");
        when(git.remote(root, root, Duration.ofSeconds(30), args, "origin")).thenThrow(new TaskFailure("TEMPLATE_GIT_TIMEOUT", "timeout"));
        var service = new ProjectBranchService(projects, git);
        var result = service.discover(root);
        assertThat(result.remoteAvailable()).isFalse();
        assertThat(result.branches()).extracting(ProjectBranchService.Branch::id).containsExactly("local:refs/heads/main");
        assertThat(result.remoteProblems()).anyMatch(value -> value.contains("超时"));
        doReturn(new GitEvidenceProcess.Result(128, "",
                GitEvidenceDiagnostic.classify("could not read Username for 'http://secret@host': terminal prompts disabled")))
                .when(git).remote(root, root, Duration.ofSeconds(30), args, "origin");
        assertThat(service.discover(root).remoteProblems()).containsExactly("Git 身份认证失败，请检查全局或项目 Git 账号；使用 SSH 时请检查系统 SSH 配置");
        doThrow(new TaskFailure("GIT_CREDENTIAL_UNAVAILABLE", "凭据无法解密")).when(git).remote(root, root, Duration.ofSeconds(30), args, "origin");
        assertThat(service.discover(root).remoteProblems()).containsExactly("凭据无法解密");
        doThrow(new TaskFailure("TEMPLATE_GIT_STOP_UNCONFIRMED", "stop")).when(git).remote(root, root, Duration.ofSeconds(30), args, "origin");
        assertThatThrownBy(() -> service.discover(root)).isInstanceOfSatisfying(TaskFailure.class,
                failure -> assertThat(failure.code()).isEqualTo("TEMPLATE_GIT_STOP_UNCONFIRMED"));
    }
}
