package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.opencode.loopper.domain.TaskFailure;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;

class GitEvidenceDiagnosticTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"2.30.2,true", "2.30.2.windows.1,true", "2.45.2,true", "3.0.0,true", "2.30.1,false", "2.29.9,false", "1.99.9,false"})
    void checksTheActualGitVersionBeforeCollection(String version, boolean supported) {
        var git = spy(new GitEvidenceProcess(new SafeProcessRunner()));
        doReturn("git version " + version).when(git).read(directory, "--version");
        if (supported) assertThatCode(() -> git.requireSupported(directory)).doesNotThrowAnyException();
        else assertThatThrownBy(() -> git.requireSupported(directory)).isInstanceOf(TaskFailure.class).hasMessageContaining("Git 2.30.2", "当前为");
    }

    @Test void stderrIsClassifiedWithoutPublishingUrlsHeadersOrArbitrarySecretText() {
        String secret = "credential-fixture-do-not-publish";
        var unsupported = GitEvidenceDiagnostic.classify("error: unknown option source https://user:" + secret + "@git.test/repo");
        assertThat(unsupported.code()).isEqualTo("TEMPLATE_GIT_COMMAND_UNSUPPORTED");
        assertThat(unsupported.failure(List.of("check-attr", "--source=HEAD"), 129).getMessage())
                .contains("不支持", "git check-attr", "129").doesNotContain(secret, "git.test", "访问权限");
        var auth = GitEvidenceDiagnostic.classify("Authentication failed for https://user:" + secret + "@git.test/repo");
        assertThat(auth.code()).isEqualTo("TEMPLATE_GIT_AUTH_FAILED");
        var arbitrary = GitEvidenceDiagnostic.classify("Authorization: Bearer " + secret);
        assertThat(arbitrary.failure(List.of("fetch", secret), 128).getMessage()).contains("git fetch", "128").doesNotContain(secret);
    }

    @Test void realGitFailureIncludesTheOperationAndExitCodeWithoutContaminatingStdout() {
        var git = new GitEvidenceProcess(new SafeProcessRunner());
        git.read(directory, "init", "--bare");
        assertThatThrownBy(() -> git.read(directory, "check-attr", "--unsupported-loopper-parameter", "--", "file"))
                .isInstanceOf(TaskFailure.class).hasMessageContaining("不支持", "git check-attr", "退出码");
        assertThat(git.read(directory, "rev-parse", "--is-bare-repository").strip()).isEqualTo("true");
    }
}
