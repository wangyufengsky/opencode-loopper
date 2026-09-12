package io.opencode.loopper.service.assist;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class AssistSafetyTest {
    @TempDir Path temp;
    static DatabaseConfig config(DatabaseConfig.Type type) {return new DatabaseConfig(type,"localhost",3306,"app","reader","vendor.jar","vendor.Driver",List.of("app"),Map.of(),10,200);}
    @ParameterizedTest @ValueSource(strings={"SELECT id, name FROM app.users WHERE id = 2", "WITH recent AS (SELECT id FROM app.users) SELECT COUNT(*) FROM recent", "SELECT '中文', NULL, CURRENT_DATE", "SELECT COALESCE(name, '无') FROM app.users"})
    void permitsReadonlySubset(String sql){for(var type:DatabaseConfig.Type.values())assertThat(ReadOnlySqlPolicy.validate(sql,config(type))).isNotBlank();}
    @ParameterizedTest @ValueSource(strings={"SELECT 1; DELETE FROM app.users", "WITH gone AS (DELETE FROM app.users RETURNING *) SELECT * FROM gone", "SELECT * FROM app.users FOR UPDATE", "SELECT * INTO OUTFILE '/tmp/x' FROM app.users", "SELECT pg_sleep(10)", "SELECT evil(name) FROM app.users", "SELECT * FROM forbidden.users", "SELECT load_file('/etc/passwd')", "CALL proc()", "SELECT 1 /*! INTO OUTFILE '/tmp/a' */", "SELECT public.count(*) FROM app.users", "SELECT nextval('seq')", "SET read_only = false", "SELECT @a:=1", "not sql"})
    void rejectsWritesFunctionsAndEscapes(String sql){for(var type:DatabaseConfig.Type.values())assertThatThrownBy(()->ReadOnlySqlPolicy.validate(sql,config(type))).isInstanceOf(AssistFailure.class);}
    @Test void parametersCannotOverrideSafety(){for(var type:DatabaseConfig.Type.values())for(String key:List.of("allowReadOnly","compatibleMode","allowMultiQueries","allowLoadLocalInfile","options","socketFactory","initSql"))assertThatThrownBy(()->DatabaseDialect.forType(type).validateParameters(Map.of(key,"false"))).isInstanceOf(AssistFailure.class);}
    @Test void credentialsSurviveRestartButRejectWrongOrMissingKeys() throws Exception {
        Path dir=temp.toRealPath().resolve("secrets"),key=temp.toRealPath().resolve("keys/master");var store=new DatabaseSecretStore(dir,key,null);
        String ref=store.save("only-in-memory-密码");assertThat(new DatabaseSecretStore(dir,key,null).read(ref)).isEqualTo("only-in-memory-密码");
        assertThat(new String(Files.readAllBytes(dir.resolve(ref)),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("only-in-memory");
        assertThatThrownBy(()->new DatabaseSecretStore(dir,key,Base64.getEncoder().encodeToString(new byte[32])).read(ref)).isInstanceOf(AssistFailure.class);
        Files.delete(key);assertThatThrownBy(()->store.read(ref)).isInstanceOf(AssistFailure.class);assertThatThrownBy(()->store.save("replacement")).isInstanceOf(AssistFailure.class);assertThat(key).doesNotExist();
    }
    @Test void pathsRejectTraversalSensitiveFilesAndSymlinks() throws Exception {
        Files.writeString(temp.resolve("report.md"),"ok");assertThat(AssistFiles.resolve(temp,"report.md")).isEqualTo(temp.toRealPath().resolve("report.md"));
        for(String path:List.of("../escape.md","a/../report.md",".env",".git/config","secret.key","/etc/passwd"))assertThatThrownBy(()->AssistFiles.resolve(temp,path)).isInstanceOf(AssistFailure.class);
        Files.createSymbolicLink(temp.resolve("link.md"),temp.resolve("report.md"));assertThatThrownBy(()->AssistFiles.resolve(temp,"link.md")).isInstanceOf(AssistFailure.class);
    }
    @Test void minimalRolesAndCredentialRedaction(){assertThat(AssistToolCatalog.allowed("ROUTER_NO_TOOLS")).isEmpty();assertThat(AssistToolCatalog.allowed("JUDGE_CANDIDATE_READ_ONLY")).doesNotContain("query_database_readonly","generate_word");assertThat(AssistToolCatalog.allowed("IMPLEMENTATION")).contains("generate_word");assertThat(AssistRedaction.text("scope=lpa_abc."+"x".repeat(43))).doesNotContain("lpa_");}
}
