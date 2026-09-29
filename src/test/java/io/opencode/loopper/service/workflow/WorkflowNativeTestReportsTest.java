package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.template.SourceTestProfile;
import io.opencode.loopper.workflow.WorkflowNativeTest;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class WorkflowNativeTestReportsTest {
    @TempDir Path root;
    @BeforeEach void canonical()throws Exception {root=root.toRealPath();}
    final WorkflowNativeTestReports reports=new WorkflowNativeTestReports(mock(WorkflowNativeTestEvidence.class),new WorkflowEncoding(new ObjectMapper()),mock(WorkflowCommandWorkspace.class));
    @Test void junitCountsActualCasesAndNeverDoubleCountsNestedTotals()throws Exception {
        var counts=WorkflowNativeTestReports.xml("<testsuites tests='9'><testsuite tests='9'><testsuite tests='3' failures='1' skipped='1'><testcase/><testcase><failure/></testcase><testcase><skipped/></testcase></testsuite></testsuite></testsuites>");
        assertThat(counts).isEqualTo(new WorkflowNativeTestReports.Counts(3,1,1,1));
    }
    @ParameterizedTest @ValueSource(strings={"<testsuite tests='2'><testcase/></testsuite>","<testsuite tests='1' failures='1'><testcase/></testsuite>","<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///not-readable'>]><testsuite tests='1'><testcase>&x;</testcase></testsuite>","<other/>","<testsuite tests='-1'/>","<testsuite tests='1'><testcase><failure/><skipped/></testcase></testsuite>"})
    void inconsistentOrExternalEntityXmlCannotBecomePassingEvidence(String xml){assertThatThrownBy(()->WorkflowNativeTestReports.xml(xml)).isInstanceOf(Exception.class);}
    @Test void jestAndVitestCountsMatchIndividualResults()throws Exception {
        String json="{\"success\":false,\"numTotalTests\":3,\"numPassedTests\":1,\"numFailedTests\":1,\"numPendingTests\":0,\"numTodoTests\":1,\"testResults\":[{\"assertionResults\":[{\"status\":\"passed\"},{\"status\":\"failed\"},{\"status\":\"todo\"}]}]}";
        assertThat(reports.json(json)).isEqualTo(new WorkflowNativeTestReports.Counts(3,1,1,1));
        assertThatThrownBy(()->reports.json(json.replace("\"numFailedTests\":1","\"numFailedTests\":0"))).isInstanceOf(Exception.class);
        assertThatThrownBy(()->reports.json(json.replace("\"success\":false","\"success\":true"))).isInstanceOf(Exception.class);
    }
    @ParameterizedTest @ValueSource(strings={"<testsuite tests='0'/>","<testsuite tests='1' skipped='1'><testcase><skipped/></testcase></testsuite>"})
    void emptyOrAllSkippedReportsAreNotExecutionProof(String xml)throws Exception {
        var module=module("pytest",".");write(WorkflowNativeTest.reportPath(module),xml);var report=reports.collect(root,module);
        assertThat(report.valid()).isFalse();assertThat(report.message()).contains("零测试");assertThat(report.files()).hasSize(1);
    }
    @Test void sourceReportsAreFreshBoundedAndNeverFollowLinks()throws Exception {
        var module=module("pytest",".");reports.preflight(root,module);Path path=write(WorkflowNativeTest.reportPath(module),"<testsuite tests='1'><testcase/></testsuite>");
        assertThatThrownBy(()->reports.preflight(root,module)).hasMessageContaining("旧报告");assertThat(reports.collect(root,module).valid()).isTrue();
        Files.writeString(path,"x".repeat(WorkflowNativeTestReports.FILE_LIMIT+1));assertThat(reports.collect(root,module).valid()).isFalse();
        Files.delete(path);Path external=Files.writeString(root.resolve("outside.xml"),"<testsuite tests='1'><testcase/></testsuite>");Files.createSymbolicLink(path,external);
        assertThat(reports.collect(root,module).valid()).isFalse();assertThatThrownBy(()->reports.preflight(root,module)).isInstanceOf(RuntimeException.class);
    }
    @Test void moduleReportSelectionDoesNotBorrowAnotherModulesTests()throws Exception {
        var first=module("junit","first");var second=module("junit","second");write(WorkflowNativeTest.reportPath(first)+"/TEST-one.xml","<testsuite tests='1'><testcase/></testsuite>");
        assertThat(reports.collect(root,first).valid()).isTrue();assertThat(reports.collect(root,second).valid()).isFalse();
    }
    @Test void deterministicNativeArgumentsDoNotChangeConfiguredModuleSelection() {
        var pytest=module("pytest","api");assertThat(WorkflowNativeTest.argv(pytest)).containsExactly("python","-m","pytest","api/tests","--junitxml=api/.loopper-test-results/report.xml");
        var gradle=new SourceTestProfile.Module("api","junit",List.of(),List.of(),List.of(),List.of("gradle","-p","api","test"));
        assertThat(WorkflowNativeTest.argv(gradle)).containsExactly("gradle","-p","api","test","--no-daemon","--rerun-tasks");
    }
    private Path write(String path,String body)throws Exception {Path file=root.resolve(path);Files.createDirectories(file.getParent());return Files.writeString(file,body);}
    private static SourceTestProfile.Module module(String framework,String root){return new SourceTestProfile.Module(root,framework,List.of(),List.of(),List.of(),framework.equals("pytest")?List.of("python","-m","pytest",root.equals(".")?"tests":root+"/tests"):List.of("mvn","test"));}
}
