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

    @Test
    void rejectsMavenSkipAndMissingTestToleranceFlagsThroughSharedPolicy() {
        for (String flag : List.of("-DskipTests", "-DskipTests=true", "-Dmaven.test.skip",
                "-Dmaven.test.skip=true", "--skipTests", "--skip-tests", "-DskipITs",
                "-DskipITs=true", "-Dsurefire.failIfNoSpecifiedTests=false",
                "-Dfailsafe.failIfNoSpecifiedTests=false")) {
            List<String> command = List.of("./mvnw", "-Dtest=EventTest", flag, "verify");
            assertThat(ProcessCommandPolicy.assessTestCommand(command).recognized()).as(flag).isTrue();
            assertThat(ProcessCommandPolicy.assessTestCommand(command).skipped()).as(flag).isTrue();
            assertThat(ProcessCommandPolicy.explicitFocusedJavaTestTargets(command)).as(flag).isEmpty();
        }
        assertThat(ProcessCommandPolicy.explicitFocusedJavaTestTargets(List.of(
                "./mvnw", "-Dtest=EventTest", "-DskipTests=false", "verify"))).containsExactly("EventTest");
    }

    @Test
    void rejectsAllGradleTestExclusionFormsThroughSharedPolicy() {
        for (List<String> exclusion : List.of(List.of("-x", "test"), List.of("--exclude-task", ":app:test"),
                List.of("--exclude-task=:app:check"), List.of("-xtest"), List.of("-x=test"))) {
            var command = new java.util.ArrayList<>(List.of("./gradlew", "test", "--tests", "EventTest"));
            command.addAll(exclusion);
            assertThat(ProcessCommandPolicy.assessTestCommand(command).recognized()).as(exclusion.toString()).isTrue();
            assertThat(ProcessCommandPolicy.assessTestCommand(command).skipped()).as(exclusion.toString()).isTrue();
            assertThat(ProcessCommandPolicy.explicitFocusedJavaTestTargets(command)).as(exclusion.toString()).isEmpty();
        }
        assertThat(ProcessCommandPolicy.explicitFocusedJavaTestTargets(List.of(
                "./gradlew", "test", "--tests", "EventTest", "-x", "javadoc"))).containsExactly("EventTest");
    }

    @Test
    void rejectsOptionalNpmTestExecutionThroughSharedPolicy() {
        for (String flag : List.of("--if-present", "--ignore-scripts")) {
            var assessment = ProcessCommandPolicy.assessTestCommand(List.of("npm", "run", "test", flag));
            assertThat(assessment.recognized()).as(flag).isTrue();
            assertThat(assessment.skipped()).as(flag).isTrue();
        }
        assertThat(ProcessCommandPolicy.assessTestCommand(List.of("npm", "test")).skipped()).isFalse();
    }
}
