package io.opencode.loopper.workflow;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkflowCommandVerificationTest {
    @Test void acceptsDirectTestsAndRequiresGradleToStayInsideTheOwnedProcessScope() {
        spec(List.of("mvn", "test", "-Dtest=ExampleTest"), "TEST").validate();
        spec(List.of("python3", "-m", "unittest", "test_example"), "TEST").validate();
        spec(List.of("gradle", "test", "--tests", "ExampleTest", "--no-daemon"), "TEST").validate();
        assertThatThrownBy(() -> spec(List.of("gradle", "test"), "TEST").validate()).hasMessageContaining("--no-daemon");
    }
    @Test void missingSkippedTestsAndShellCommandsCannotProduceTestEvidence() {
        for (var argv : List.of(List.of("mvn", "test", "-DskipTests"), List.of("npm", "run", "test", "--if-present"),
                List.of("grep", "expected", "file"), List.of("bash", "-c", "echo passed"), List.of("python3", "--version")))
            assertThatThrownBy(() -> spec(argv, "TEST").validate()).isInstanceOf(IllegalArgumentException.class);
        spec(List.of("python3", "--version"), "CHECK").validate();
    }
    @Test void preservesOutputMatchingAndRejectsUnboundedContracts() {
        var valid = spec(List.of("python3", "check.py"), "CHECK"); valid.validate(); assertThat(valid.outputContains()).isEqualTo("  done\n");
        assertThatThrownBy(() -> new WorkflowCommandVerification(1,"code", valid.argv(), 3601,"CHECK",null).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowCommandVerification(1,"../code", valid.argv(), 10,"CHECK",null).validate()).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void emptyPlanningPlaceholderDoesNotWeakenExecutionOrConfiguredCommandValidation() {
        var empty=spec(List.of(),"TEST");empty.validate(true);
        assertThatThrownBy(empty::validate).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->spec(List.of("python3","verify.py"),"TEST").validate(true)).hasMessageContaining("CHECK");
        spec(List.of("python3","verify.py"),"CHECK").validate(true);
        assertThatThrownBy(()->new WorkflowCommandVerification(1,"code",List.of(),3601,"TEST",null).validate(true)).isInstanceOf(IllegalArgumentException.class);
    }
    private static WorkflowCommandVerification spec(List<String> argv,String purpose) { return new WorkflowCommandVerification(1,"code",argv,30,purpose,"  done\n"); }
}
