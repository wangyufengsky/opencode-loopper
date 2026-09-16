package io.opencode.loopper.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ChildProcessEnvironmentTest {
    @Test void removesMasterKeysFromActualChildWhileKeepingProviderAuthentication() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command()).redirectErrorStream(true);
        builder.environment().putAll(environment());
        Process child = ChildProcessEnvironment.start(builder);
        assertThat(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("false:false:true");
        assertThat(child.exitValue()).isZero();
        var result = new SafeProcessRunner().run(Path.of(".").toRealPath(), command(), Duration.ofSeconds(10), environment());
        assertThat(result.exitCode()).isZero(); assertThat(result.output()).isEqualTo("false:false:true");
    }
    private List<String> command() {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Child.class.getName());
    }
    private Map<String, String> environment() {
        return Map.of("LOOPPER_GIT_MASTER_KEY", "git-fixture", "LOOPPER_DATABASE_MASTER_KEY", "database-fixture", "LOOPPER_FIXTURE_PROVIDER_KEY", "provider-fixture");
    }
    public static class Child {
        public static void main(String[] arguments) {
            System.out.print(System.getenv().containsKey("LOOPPER_GIT_MASTER_KEY") + ":"
                    + System.getenv().containsKey("LOOPPER_DATABASE_MASTER_KEY") + ":"
                    + System.getenv().containsKey("LOOPPER_FIXTURE_PROVIDER_KEY"));
        }
    }
}
