package io.opencode.loopper.service.assist;

import io.opencode.loopper.config.LoopperProperties;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DatabaseDriverRegistryTest {
    @TempDir Path temp;
    @Test void missingDriverReportsDriverConfigurationInsteadOfCredentialFailure() throws Exception {
        var properties=new LoopperProperties();properties.setDataDir(temp.toRealPath());var drivers=new DatabaseDriverRegistry(properties);
        assertThatThrownBy(()->drivers.open(AssistSafetyTest.config(DatabaseConfig.Type.MYSQL),"fixture"))
                .isInstanceOfSatisfying(AssistFailure.class,failure->assertThat(failure.code()).isEqualTo("DATABASE_DRIVER_UNAVAILABLE"));
    }
    @Test void loadsOfflineJarOncePerHashAndKeepsConnectionsIndependent() throws Exception {
        Path root=temp.toRealPath(),source=root.resolve("FixtureDriver.java");
        Files.writeString(source,"""
                import java.sql.*; import java.util.*; import java.util.logging.*;
                public class FixtureDriver implements Driver {
                  static { try { DriverManager.registerDriver(new FixtureDriver()); } catch(SQLException e) {throw new RuntimeException(e);} }
                  public Connection connect(String url,Properties p) {
                    boolean[] readonly={false};
                    return (Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                      if(method.getName().equals("setReadOnly")){readonly[0]=(boolean)args[0];return null;}
                      if(method.getName().equals("isReadOnly"))return readonly[0];
                      if(method.getName().equals("getAutoCommit"))return false;
                      return null;
                    });
                  }
                  public boolean acceptsURL(String url){return url.startsWith("jdbc:");}
                  public DriverPropertyInfo[] getPropertyInfo(String url,Properties p){return new DriverPropertyInfo[0];}
                  public int getMajorVersion(){return 1;} public int getMinorVersion(){return 0;}
                  public boolean jdbcCompliant(){return false;} public Logger getParentLogger(){return Logger.getGlobal();}
                }
                """);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",root.toString(),source.toString())).isZero();
        Path jar=Files.createDirectories(root.resolve("jdbc-drivers")).resolve("fixture.jar");
        try(var out=new JarOutputStream(Files.newOutputStream(jar))){out.putNextEntry(new JarEntry("FixtureDriver.class"));out.write(Files.readAllBytes(root.resolve("FixtureDriver.class")));out.closeEntry();}
        var properties=new LoopperProperties();properties.setDataDir(root);var drivers=new DatabaseDriverRegistry(properties);
        try {
            var config=new DatabaseConfig(DatabaseConfig.Type.MYSQL,"localhost",3306,"app","reader","fixture.jar","FixtureDriver",List.of("app"),Map.of(),10,200);
            try(var first=drivers.open(config,"test");var second=drivers.open(config,"test")) {
                assertThat(first.loader()).isSameAs(second.loader());assertThat(first.connection()).isNotSameAs(second.connection());assertThat(first.connection().isReadOnly()).isTrue();
                assertThat(first.info().sha256()).isEqualTo(AssistFiles.sha(Files.readAllBytes(jar)));assertThat(drivers.inventory()).hasSize(1);
            }
            try(var third=drivers.open(config,"test")){assertThat(third.connection().isReadOnly()).isTrue();}
        }finally{drivers.close();}
    }
}
