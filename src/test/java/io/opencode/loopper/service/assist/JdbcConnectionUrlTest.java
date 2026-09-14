package io.opencode.loopper.service.assist;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class JdbcConnectionUrlTest {
    @TempDir Path temp;
    private DatabaseConfig input(String url) {
        return new DatabaseConfig(DatabaseConfig.Type.OPENGAUSS,null,0,null,"reader","","",List.of("public"),Map.of(),10,200,null,url);
    }
    @Test void multihostUrlIsAcceptedByBundledDriverWithoutLosingFailoverOptions() throws Exception {
        var config=BundledDatabaseDrivers.resolve(input("jdbc:opengauss://db1:8000,db2:8000,db3:8000/app?targetServerType=master&loadBalanceHosts=true"));
        assertThat(config.database()).isEqualTo("app");
        var dialect=DatabaseDialect.forType(config.type());
        assertThat(dialect.url(config)).isEqualTo("jdbc:opengauss://db1:8000,db2:8000,db3:8000/app");
        var properties=dialect.properties(config,"secret");
        assertThat(properties).containsEntry("targetServerType","master").containsEntry("allowReadOnly","true").containsEntry("socketTimeout","30");
        var files=BundledDatabaseDrivers.materialize(temp.toRealPath(),config);
        try(var loader=new URLClassLoader(files.stream().map(p->{try{return p.toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(java.net.URL[]::new),ClassLoader.getPlatformClassLoader())) {
            var driver=(Driver)Class.forName(config.driverClass(),true,loader).getDeclaredConstructor().newInstance();
            assertThat(driver.acceptsURL(dialect.url(config))).isTrue();
            assertThat(driver.getPropertyInfo(dialect.url(config),properties)).isNotEmpty();
        }
        assertThat(config.toString()).doesNotContain("secret");
    }
    @Test void bothInputProtocolsMatchTheFrozenDriverAndKeepIndependentCredentials() throws Exception {
        for(String id:List.of("opengauss-6.0.3","opengauss-7.0.0-RC3-og")) {
            for(String protocol:List.of("opengauss","postgresql")) {
                var config=BundledDatabaseDriversTest.configuration(id,"jdbc:"+protocol+"://[::1]:8000,db2:8000/app?targetServerType=master");
                var dialect=DatabaseDialect.forType(config.type());
                assertThat(dialect.url(config)).isEqualTo("jdbc:"+(id.equals("opengauss-6.0.3")?"postgresql":"opengauss")+"://[::1]:8000,db2:8000/app");
                assertThat(dialect.properties(config," p@ss+&=%密 ")).containsEntry("password"," p@ss+&=%密 ").containsEntry("user","reader").containsEntry("targetServerType","master");
                var files=BundledDatabaseDrivers.materialize(temp.toRealPath(),config);
                try(var loader=new URLClassLoader(files.stream().map(p->{try{return p.toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(java.net.URL[]::new),ClassLoader.getPlatformClassLoader())) {
                    var driver=(Driver)Class.forName(config.driverClass(),true,loader).getDeclaredConstructor().newInstance();
                    assertThat(driver.acceptsURL(dialect.url(config))).isTrue();
                }
            }
        }
    }
    @Test void urlCannotOverrideCredentialsReadOnlyOrTransportGuards() {
        for(String parameter:List.of("password=secret","user=root","socketTimeout=0","allowReadOnly=false","options=evil","sslFactory=evil.Class","targetServerType=master&targetServerType=slave","%70assword=secret"))
            assertThatThrownBy(()->BundledDatabaseDrivers.resolve(input("jdbc:opengauss://db/app?"+parameter))).isInstanceOf(AssistFailure.class);
        for(String url:List.of("jdbc:mysql://db/app","jdbc:opengauss://user:secret@db/app","jdbc:opengauss://db:99999/app","jdbc:opengauss://db/app#secret"))
            assertThatThrownBy(()->BundledDatabaseDrivers.resolve(input(url))).isInstanceOf(AssistFailure.class);
    }
    @Test void removingUrlOptionDoesNotRetainPreviouslyParsedProperties() {
        var prior=BundledDatabaseDrivers.resolve(input("jdbc:opengauss://db/app?targetServerType=master"));
        var edited=new DatabaseConfig(prior.type(),prior.host(),prior.port(),prior.database(),prior.username(),"","",prior.schemas(),prior.parameters(),10,200,null,"jdbc:opengauss://db/app");
        assertThat(BundledDatabaseDrivers.resolve(edited).parameters()).isEmpty();
    }
}
