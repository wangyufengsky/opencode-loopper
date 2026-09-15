package io.opencode.loopper.service.assist;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.Driver;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class BundledDatabaseDriversTest {
    @TempDir Path temp;
    static DatabaseConfig input(DatabaseConfig.Type type) {
        return new DatabaseConfig(type,"localhost",3306,"app","reader","","",List.of("app"),Map.of(),10,200);
    }
    @Test void coldExtractionLoadsAllSupportedDriversInIsolationAndSurvivesRestart() throws Exception {
        io.opencode.loopper.TestJvm.run(BundledDatabaseDriversTest.class, temp);
    }
    public static void main(String[] args) throws Exception {
        var fixture = new BundledDatabaseDriversTest();
        fixture.temp = Path.of(args[0]);
        fixture.exerciseDrivers();
        System.exit(0);
    }
    private void exerciseDrivers() throws Exception {
        for(var profile:BundledDatabaseDrivers.PROFILES) {
            var config=configuration(profile.id(),null);
            var files=BundledDatabaseDrivers.materialize(temp.toRealPath(),config);
            assertThat(files).hasSize(profile.binaries().size());
            var urls=new java.net.URL[files.size()];for(int i=0;i<files.size();i++)urls[i]=files.get(i).toUri().toURL();
            try(var loader=new URLClassLoader(urls,ClassLoader.getPlatformClassLoader())) {
                var driver=(Driver)Class.forName(profile.driverClass(),true,loader).getDeclaredConstructor().newInstance();
                assertThat(driver.acceptsURL(DatabaseDialect.forType(profile.type()).url(config))).isTrue();
                assertThat(driver.getPropertyInfo(DatabaseDialect.forType(profile.type()).url(config),new Properties())).isNotNull();
                assertThatThrownBy(()->loader.loadClass("org.sqlite.JDBC")).isInstanceOf(ClassNotFoundException.class);
            }
            assertThat(BundledDatabaseDrivers.materialize(temp.toRealPath(),config)).isEqualTo(files);
            Files.writeString(files.getFirst(),"tampered");
            assertThatThrownBy(()->BundledDatabaseDrivers.materialize(temp.toRealPath(),config)).isInstanceOf(AssistFailure.class);
            assertThat(Files.readString(files.getFirst())).isEqualTo("tampered");
        }
    }
    static DatabaseConfig configuration(String id,String url) {
        var p=BundledDatabaseDrivers.profile(id);
        return BundledDatabaseDrivers.resolve(new DatabaseConfig(p.type(),"localhost",p.defaultPort(),"app","reader",
                p.binaries().getFirst().filename(),p.driverClass(),List.of("app"),Map.of(),10,200,p.id(),url));
    }
    @Test void defaultsAreUniqueAndHistoricalProfileCannotBeSubstitutedAcrossProducts() {
        assertThat(BundledDatabaseDrivers.defaults()).extracting(BundledDatabaseDrivers.Profile::type).doesNotHaveDuplicates().hasSize(6);
        assertThat(BundledDatabaseDrivers.resolve(input(DatabaseConfig.Type.OPENGAUSS)).driverProfile()).isEqualTo("opengauss-3.1.0");
        var old=configuration("opengauss-6.0.3",null);
        assertThat(BundledDatabaseDrivers.resolve(old)).isEqualTo(old);
        assertThatThrownBy(()->BundledDatabaseDrivers.resolve(new DatabaseConfig(DatabaseConfig.Type.MYSQL,old.host(),old.port(),old.database(),old.username(),
                old.driverFile(),old.driverClass(),old.schemas(),old.parameters(),10,200,old.driverProfile()))).isInstanceOf(AssistFailure.class);
    }
    @Test void typeSelectionCannotSupplyArbitraryDriversOrSubstituteVendorProducts() {
        for(var type:List.of(DatabaseConfig.Type.GOLDENDB))
            assertThatThrownBy(()->BundledDatabaseDrivers.resolve(input(type))).isInstanceOf(AssistFailure.class);
        assertThatThrownBy(()->BundledDatabaseDrivers.resolve(AssistSafetyTest.config(DatabaseConfig.Type.MYSQL))).isInstanceOf(AssistFailure.class);
    }
    @Test void extractionRejectsSymbolicLinkDirectories() throws Exception {
        var root=temp.toRealPath();var config=BundledDatabaseDrivers.resolve(input(DatabaseConfig.Type.MYSQL));
        Files.createSymbolicLink(root.resolve(config.driverProfile()),Files.createDirectory(root.resolve("outside")));
        assertThatThrownBy(()->BundledDatabaseDrivers.materialize(root,config)).isInstanceOf(AssistFailure.class);
        try(var files=Files.list(root.resolve("outside"))) { assertThat(files).isEmpty(); }
    }
}
