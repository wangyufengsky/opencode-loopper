package io.opencode.loopper.verification;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessCommandPolicyTest {
    @Test
    void extractsOnlyExplicitMavenFocusedTestTargets() {
        assertThat(ProcessCommandPolicy.explicitFocusedJavaTestTargets(List.of(
                "mvn", "-q", "-Dtest=SimpleEventTest,EventRegistryTest#rejectsDuplicate", "test")))
                .containsExactly("SimpleEventTest", "EventRegistryTest#rejectsDuplicate");
        assertThat(ProcessCommandPolicy.explicitFocusedJavaTestTargets(List.of(
                "./mvnw -q -Dit.test=EventPublisherIT verify")))
                .containsExactly("EventPublisherIT");
    }

    @Test
    void extractsExplicitGradleTargetsInBothArgvForms() {
        assertThat(ProcessCommandPolicy.explicitFocusedJavaTestTargets(List.of(
                "./gradlew", "test", "--tests", "com.example.EventDispatcherTest", "--tests=*RegistryTest")))
                .containsExactly("com.example.EventDispatcherTest", "*RegistryTest");
    }

    @Test
    void neverGuessesTargetsFromBroadOrDisabledCommands() {
        assertThat(ProcessCommandPolicy.explicitFocusedJavaTestTargets(List.of("mvn", "test"))).isEmpty();
        assertThat(ProcessCommandPolicy.explicitFocusedJavaTestTargets(List.of(
                "mvn", "-Dtest=!SlowTest", "test"))).isEmpty();
        assertThat(ProcessCommandPolicy.explicitFocusedJavaTestTargets(List.of(
                "mvn", "-Dtest=EventTest", "-DskipTests", "test"))).isEmpty();
        assertThat(ProcessCommandPolicy.explicitFocusedJavaTestTargets(List.of(
                "npm", "test", "EventTest"))).isEmpty();
    }
}
