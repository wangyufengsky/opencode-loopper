package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.template.SourceTestProfile;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorkflowNativeCasesTest {
    private final WorkflowEncoding encoding=new WorkflowEncoding(new ObjectMapper());
    @Test void nativeXmlPreservesEachExecutionIncludingDuplicateNamesFailureAndSkip() {
        String xml="<testsuite tests='3' failures='1' skipped='1'><testcase classname='a.SampleTest$Inner' name='value[1]'/><testcase classname='a.SampleTest$Inner' name='value[1]'><failure/></testcase><testcase classname='a.SampleTest' name='later'><skipped/></testcase></testsuite>";
        var module=module(".","junit",List.of("src/test/java"));var report=report("target/surefire-reports/TEST-a.xml",xml,new WorkflowNativeTestReports.Counts(3,1,1,1));
        var cases=WorkflowNativeCases.read(report,module,"/fixed",encoding);
        assertThat(cases).extracting(WorkflowNativeCases.Case::id).doesNotHaveDuplicates();assertThat(cases).extracting(WorkflowNativeCases.Case::status).containsExactlyInAnyOrder("PASSED","FAILED","SKIPPED");
        assertThat(cases).allMatch(c->WorkflowNativeCases.matches(c,"src/test/java/a/SampleTest.java",module));
        assertThat(cases).noneMatch(c->WorkflowNativeCases.matches(c,"src/main/java/a/SampleTest.java",module)||WorkflowNativeCases.matches(c,"src/test/java/a/Unrelated.java",module));
        assertThat(WorkflowNativeCases.read(report,module,"/fixed",encoding)).isEqualTo(cases);
    }
    @Test void pytestClassesAndParameterizedTestsMapOnlyToTheMatchingModuleFile() {
        var module=module("api","pytest",List.of("api/tests"));String xml="<testsuites><testsuite tests='1'><testcase classname='tests.test_sum.TestCalculator' name='test_sum[positive]'/></testsuite></testsuites>";
        var item=WorkflowNativeCases.read(report("api/report.xml",xml,new WorkflowNativeTestReports.Counts(1,1,0,0)),module,"/fixed",encoding).getFirst();
        assertThat(WorkflowNativeCases.matches(item,"api/tests/test_sum.py",module)).isTrue();assertThat(WorkflowNativeCases.matches(item,"api/tests/test_other.py",module)).isFalse();
    }
    @Test void junitExplicitFileAttributesAreNotTreatedAsClassNames() {
        var module=module(".","junit",List.of("src/test/java"));
        for(String file:List.of("src/test/java/a/SampleTest.java","a/SampleTest.java","/fixed/src/test/java/a/SampleTest.java")) {
            String xml="<testsuite tests='1'><testcase classname='a.SampleTest' name='sum' file='"+file+"'/></testsuite>";
            var item=WorkflowNativeCases.read(report("report.xml",xml,new WorkflowNativeTestReports.Counts(1,1,0,0)),module,"/fixed",encoding).getFirst();
            assertThat(WorkflowNativeCases.matches(item,"src/test/java/a/SampleTest.java",module)).isTrue();assertThat(WorkflowNativeCases.matches(item,"src/test/java/b/SampleTest.java",module)).isFalse();
        }
    }
    @Test void jsonCasesNormalizeTheFrozenPrivateRootWithoutReadingTheCurrentFilesystem() {
        String text="{\"testResults\":[{\"name\":\"/fixed/packages/web/tests/sum.test.js\",\"assertionResults\":[{\"fullName\":\"calculator sum\",\"status\":\"passed\"},{\"title\":\"next\",\"status\":\"pending\"}]}]}";
        var module=module("packages/web","vitest",List.of("packages/web/tests"));
        var cases=WorkflowNativeCases.read(report("packages/web/report.json",text,new WorkflowNativeTestReports.Counts(2,1,0,1)),module,"/fixed",encoding);
        assertThat(cases).allMatch(c->WorkflowNativeCases.matches(c,"packages/web/tests/sum.test.js",module));assertThat(cases).extracting(WorkflowNativeCases.Case::testPath).containsOnly("packages/web/tests/sum.test.js");
    }
    @Test void externalNativePathCannotProveAnyFixedTestFile() {
        String text="{\"testResults\":[{\"name\":\"/other/tests/test.js\",\"assertionResults\":[{\"title\":\"sum\",\"status\":\"passed\"}]}]}";
        var module=module(".","jest",List.of("tests"));var item=WorkflowNativeCases.read(report("report.json",text,new WorkflowNativeTestReports.Counts(1,1,0,0)),module,"/fixed",encoding).getFirst();
        assertThat(item.testPath()).isNull();assertThat(WorkflowNativeCases.matches(item,"tests/test.js",module)).isFalse();
    }
    @Test void changedReportBytesOrMismatchedCountsAreRejected() {
        var module=module(".","junit",List.of("tests"));String xml="<testsuite tests='1'><testcase classname='SampleTest' name='sum'/></testsuite>";
        var report=report("report.xml",xml,new WorkflowNativeTestReports.Counts(1,0,1,0));assertThatThrownBy(()->WorkflowNativeCases.read(report,module,"/fixed",encoding)).hasMessageContaining("不一致");
        var changed=new WorkflowNativeTestReports.Report(true,"saved",new WorkflowNativeTestReports.Counts(1,1,0,0),List.of(new WorkflowNativeTestReports.File("report.xml","0".repeat(64),xml)),true);
        assertThatThrownBy(()->WorkflowNativeCases.read(changed,module,"/fixed",encoding)).hasMessageContaining("不一致");
    }
    @Test void xmlExternalEntitiesStayDisabledDuringCaseProjection() {
        String xml="<!DOCTYPE test [<!ENTITY outside SYSTEM 'file:///etc/passwd'>]><testsuite tests='1'><testcase classname='SampleTest' name='sum'>&outside;</testcase></testsuite>";
        assertThatThrownBy(()->WorkflowNativeCases.read(report("report.xml",xml,new WorkflowNativeTestReports.Counts(1,1,0,0)),module(".","junit",List.of("tests")),"/fixed",encoding)).hasMessageContaining("不一致");
    }
    private static SourceTestProfile.Module module(String root,String framework,List<String> testRoots){return new SourceTestProfile.Module(root,framework,List.of("src/Calculator"),testRoots,List.of(),List.of("test"));}
    private static WorkflowNativeTestReports.Report report(String path,String text,WorkflowNativeTestReports.Counts counts){return new WorkflowNativeTestReports.Report(true,"saved",counts,List.of(new WorkflowNativeTestReports.File(path,WorkflowEncoding.hash(text),text)),true);}
}
