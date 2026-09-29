package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.template.SourceTestProfile;
import io.opencode.loopper.workflow.WorkflowNativeTest;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.ObjectMapper;

class WorkflowNativeEnvironmentTest {
    @TempDir Path temp;
    Path root,bin;
    WorkflowNativeEnvironment compiler;
    @BeforeEach void setup()throws Exception {
        temp=temp.toRealPath();root=Files.createDirectory(temp.resolve("code"));bin=Files.createDirectory(temp.resolve("bin"));
        Path python=Files.writeString(bin.resolve("python3"),"fixture");python.toFile().setExecutable(true);
        compiler=new WorkflowNativeEnvironment("Linux",Map.of("PATH",bin.toString()),new ObjectMapper());
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void npmUsesDeclaredLockAndDoesNotCreateDependenciesDuringCompilation(boolean locked)throws Exception {
        write("package.json","{}");if(locked)write("package-lock.json","{}");
        var module=module(".","vitest",List.of("npm","test","--","--run"));
        var result=compiler.compile(root,module,WorkflowNativeTest.argv(module));
        assertThat(result.preparations()).hasSize(1);var preparation=result.preparations().getFirst();
        assertThat(preparation.argv()).containsSubsequence("npm",locked?"ci":"install","--prefix",root.toString()).contains("--include=dev");
        assertThat(preparation.argv().contains("--package-lock=false")).isEqualTo(!locked);
        assertThat(result.argv()).isEqualTo(WorkflowNativeTest.argv(module));assertThat(root.resolve("node_modules")).doesNotExist();
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void onlyAnAncestorLockThatActuallyContainsThisWorkspaceIsSelected(boolean member)throws Exception {
        write("package.json","{}");write("packages/web/package.json","{}");write("package-lock.json",member?"{\"packages\":{\"packages/web\":{}}}":"{\"packages\":{\"packages/other\":{}}}");
        var module=module("packages/web","jest",List.of("npm","test","--prefix","packages/web","--","--runInBand"));
        var result=compiler.compile(root,module,WorkflowNativeTest.argv(module));
        assertThat(result.preparations().getFirst().directory()).isEqualTo(member?root.toString():root.resolve("packages/web").toString());
        assertThat(result.preparations().getFirst().argv().get(1)).isEqualTo(member?"ci":"install");
    }
    @Test void pythonCreatesAPrivateEnvironmentAndInstallsOnlyProjectDeclaredDependencies()throws Exception {
        write("pytest.ini","[pytest]\n");write("requirements.txt","example==1\n");write("requirements-test.txt","pytest==9.0.2\n");
        var module=module(".","pytest",List.of("python","-m","pytest","tests"));var result=compiler.compile(root,module,WorkflowNativeTest.argv(module));
        assertThat(result.preparations()).hasSize(2);assertThat(result.preparations().getFirst().argv()).containsExactly(bin.resolve("python3").toString(),"-m","venv",root.resolve(".loopper-test-env").toString());
        assertThat(result.preparations().get(1).argv()).containsSubsequence("-m","pip","install").containsSubsequence("-r","requirements.txt","-r","requirements-test.txt");
        assertThat(result.argv().getFirst()).isEqualTo(root.resolve(".loopper-test-env/bin/python").toString());
        assertThat(result.argv()).contains("--junitxml=.loopper-test-results/report.xml");assertThat(root.resolve(".loopper-test-env")).doesNotExist();
    }
    @Test void pyprojectTestExtraIsUsedWithoutInstallingUndeclaredTooling()throws Exception {
        write("pyproject.toml","[project]\nname = 'example'\nversion = '1'\n[project.optional-dependencies]\ntest = ['pytest==9.0.2']\n[tool.pytest.ini_options]\n");
        var module=module(".","pytest",List.of("python","-m","pytest","tests"));var result=compiler.compile(root,module,WorkflowNativeTest.argv(module));
        assertThat(result.preparations().get(1).argv()).contains(".[test]").doesNotContain("pytest","--system-site-packages");
    }
    @Test void pythonModuleMayUseItsNearestAncestorDependencyDeclarationInsideTheSnapshot()throws Exception {
        write("api/pytest.ini","[pytest]\n");write("requirements-test.txt","pytest==9.0.2\n");
        var module=module("api","pytest",List.of("python","-m","pytest","api/tests"));var result=compiler.compile(root,module,WorkflowNativeTest.argv(module));
        assertThat(result.preparations().get(1).directory()).isEqualTo(root.toString());assertThat(result.argv()).contains("api/tests","--junitxml=api/.loopper-test-results/report.xml");
    }
    @Test void missingPythonDependencyDeclarationIsActionableAndDoesNotInstallAGuessedVersion()throws Exception {
        write("pyproject.toml","[tool.pytest.ini_options]\ntestpaths = ['tests']\n");
        var module=module(".","pytest",List.of("python","-m","pytest","tests"));
        assertThatThrownBy(()->compiler.compile(root,module,WorkflowNativeTest.argv(module))).isInstanceOf(BadRequestException.class).hasMessageContaining("声明测试依赖");
    }
    @Test void windowsFreezesTheFuturePrivateInterpreterWithoutRequiringItToExist()throws Exception {
        Files.writeString(bin.resolve("python3.exe"),"fixture");write("requirements-test.txt","pytest==9.0.2\n");
        var windows=new WorkflowNativeEnvironment("Windows 11",Map.of("PATH",bin.toString()),new ObjectMapper());
        var module=module(".","pytest",List.of("python","-m","pytest","tests"));var result=windows.compile(root,module,WorkflowNativeTest.argv(module));
        assertThat(result.argv().getFirst()).isEqualTo(root.resolve(".loopper-test-env/Scripts/python.exe").toString());
        assertThat(result.preparations().getFirst().argv().getFirst()).isEqualTo(bin.resolve("python3.exe").toString());
    }
    @ParameterizedTest @CsvSource({"mvn,mvnw","gradle,gradlew"})
    void nativeProjectWrapperTakesPrecedenceOverAnUnrelatedGlobalBuildTool(String program,String wrapper)throws Exception {
        Path script=write(wrapper,"fixture");script.toFile().setExecutable(true);Files.createDirectories(root.resolve("module"));
        var module=module("module","junit",List.of(program,"test","--no-daemon"));
        var result=compiler.compile(root,module,module.command());assertThat(result.argv().getFirst()).isEqualTo(script.toString());assertThat(result.preparations()).isEmpty();
    }
    @Test void reactorBuildSelectsTheWrapperOfItsActualBuildEntry()throws Exception {
        write("mvnw","root fixture").toFile().setExecutable(true);write("module/mvnw","child fixture").toFile().setExecutable(true);
        var module=module("module","junit",List.of("mvn","-f","pom.xml","-pl","module","-am","test"));
        assertThat(compiler.compile(root,module,module.command()).argv().getFirst()).isEqualTo(root.resolve("mvnw").toString());
    }
    @Test void existingDependencyDirectoriesCannotBeOverwritten()throws Exception {
        write("package.json","{}");Files.createDirectory(root.resolve("node_modules"));
        var module=module(".","vitest",List.of("npm","test","--","--run"));
        assertThatThrownBy(()->compiler.compile(root,module,WorkflowNativeTest.argv(module))).isInstanceOf(BadRequestException.class).hasMessageContaining("已有内容");
    }
    @Test void linkedProjectConfigurationCannotEscapeTheCopiedCode()throws Exception {
        Path outside=Files.writeString(temp.resolve("outside.json"),"{}");Files.createSymbolicLink(root.resolve("package.json"),outside);
        var module=module(".","jest",List.of("npm","test","--","--runInBand"));
        assertThatThrownBy(()->compiler.compile(root,module,WorkflowNativeTest.argv(module))).isInstanceOf(BadRequestException.class);
    }
    private Path write(String name,String body)throws Exception {Path path=root.resolve(name);Files.createDirectories(path.getParent());return Files.writeString(path,body);}
    private static SourceTestProfile.Module module(String root,String framework,List<String> command){return new SourceTestProfile.Module(root,framework,List.of("source"),List.of("tests"),List.of(),command);}
}
