package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.template.SourceManifest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class SourceTestProfilesTest {
    final SourceTestProfiles resolver=new SourceTestProfiles(JsonMapper.builder().build());
    @ParameterizedTest @ValueSource(strings={"jest","vitest"})
    void nativeJavascriptCommandsKeepExistingDiscoveryRootsAndDoNotInventDependencies(String framework) {
        var bodies=Map.of("config","{\"scripts\":{\"test\":\""+framework+"\"}}","test","test('works', () => {})");
        var files=List.of(file("app/package.json",false,"config"),file("app/src/Main.ts",true,"source"),file("app/tests/main.test.ts",false,"test"));
        var module=resolver.resolve("fixed","app/src",null,files,bodies::get).modules().getFirst();
        assertThat(module.testRoots()).containsExactly("app/tests");assertThat(module.command()).containsExactly("npm","test","--prefix","app","--",framework.equals("jest")?"--runInBand":"--run");
        assertThat(module.sourcePaths()).containsExactly("app/src/Main.ts");
    }
    @Test void gradleTestNgIsRecognizedButCustomSourceSetsRequireExplicitConfiguration() {
        var files=List.of(file("build.gradle",false,"config"),file("src/main/java/Main.java",true,"source"));
        assertThat(resolver.resolve("fixed","src/main",null,files,s->"testImplementation 'org.testng:testng'").modules().getFirst().framework()).isEqualTo("testng");
        assertThatThrownBy(()->resolver.resolve("fixed","src/main",null,files,s->"testng sourceSets {}"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("sourceSets");
    }
    @Test void nonexistentAndOutsideNativeTestRootsCannotBecomeWritePermission() {
        var files=List.of(file("package.json",false,"config"),file("src/Main.js",true,"source"));
        assertThatThrownBy(()->resolver.resolve("fixed","src",null,files,s->"{\"scripts\":{\"test\":\"jest\"}}"))
                .hasMessageContaining("尚无已有测试");
        var python=List.of(file("pytest.ini",false,"config"),file("src/main.py",true,"source"));
        assertThatThrownBy(()->resolver.resolve("fixed","src","src",python,s->"[pytest]"))
                .hasMessageContaining("不在已识别的测试源码范围");
    }
    private SourceManifest.File file(String path,boolean target,String sha){return new SourceManifest.File(path,target,10,sha,null);}
}
