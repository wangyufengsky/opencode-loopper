package io.opencode.loopper.service.assist;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseVendorSupportTest {
    private DatabaseConfig input(DatabaseConfig.Type type,String url) {
        return BundledDatabaseDrivers.resolve(new DatabaseConfig(type,null,0,null,"reader","","",List.of("APP","app"),Map.of(),10,200,null,url));
    }
    @Test void mcpDatabaseTypesResolveExactDriverAndVendorAddress() {
        var cases=Map.of(
                DatabaseConfig.Type.GAUSSDB,"jdbc:postgresql://db1:8000,db2:8000/app?targetServerType=master",
                DatabaseConfig.Type.ORACLE,"jdbc:oracle:thin:@//db:1521/service.example",
                DatabaseConfig.Type.DB2,"jdbc:db2://db:50000/app:sslConnection=true;",
                DatabaseConfig.Type.DAMENG,"jdbc:dm://db:5236",
                DatabaseConfig.Type.MYSQL,"jdbc:mysql://db:3306/app");
        for(var entry:cases.entrySet()) {
            var config=input(entry.getKey(),entry.getValue());
            assertThat(config.jdbcUrl()).isEqualTo(entry.getValue());
            assertThat(DatabaseDialect.forType(config.type()).properties(config," 密码+&=% "))
                    .containsEntry("password"," 密码+&=% ").containsEntry("user","reader");
        }
        var gauss=input(DatabaseConfig.Type.GAUSSDB,cases.get(DatabaseConfig.Type.GAUSSDB));
        assertThat(gauss.driverClass()).isEqualTo("org.postgresql.Driver");
        assertThat(gauss.driverFile()).isEqualTo("opengauss-jdbc-3.1.0.jar");
        assertThat(DatabaseDialect.forType(gauss.type()).url(gauss)).isEqualTo("jdbc:postgresql://db1:8000,db2:8000/app");
        var db2=input(DatabaseConfig.Type.DB2,cases.get(DatabaseConfig.Type.DB2));
        assertThat(db2.parameters()).containsExactlyEntriesOf(Map.of("sslConnection","true"));
        assertThat(DatabaseDialect.forType(db2.type()).url(db2)).isEqualTo("jdbc:db2://db:50000/app");
    }
    @Test void oraclePreservesServiceAndSidSyntaxIncludingIpv6() {
        for(String url:List.of("jdbc:oracle:thin:@//[::1]:1521/pdb.example","jdbc:oracle:thin:@db:1521:ORCL","jdbc:oracle:thin:@db:1521/pdb")) {
            var config=input(DatabaseConfig.Type.ORACLE,url);
            assertThat(DatabaseDialect.forType(config.type()).url(config)).isEqualTo(url);
            assertThat(config.port()).isEqualTo(1521);
        }
    }
    @Test void vendorUrlsCannotInjectCredentialsDescriptorsOrUnsafeProperties() {
        for(String url:List.of("jdbc:db2://db:50000/app:user=evil;","jdbc:db2://db/app:password=secret;",
                "jdbc:db2://db/app:sslConnection=true;sslConnection=false;","jdbc:db2://db/app:retrieveMessagesFromServerOnGetMessage=true;"))
            assertThatThrownBy(()->input(DatabaseConfig.Type.DB2,url)).isInstanceOf(AssistFailure.class);
        for(String url:List.of("jdbc:oracle:thin:user/pass@db:1521:ORCL","jdbc:oracle:thin:@(DESCRIPTION=evil)",
                "jdbc:oracle:thin:@//db:1521/app?oracle.net.wallet_location=secret","jdbc:oracle:thin:@//db:1521/app?password=secret"))
            assertThatThrownBy(()->input(DatabaseConfig.Type.ORACLE,url)).isInstanceOf(AssistFailure.class);
    }
    @Test void oracleRequiresReadOnlyTransactionAfterSchemaAndFailsClosed() throws Exception {
        var config=input(DatabaseConfig.Type.ORACLE,"jdbc:oracle:thin:@//db:1521/app");
        var connection=mock(Connection.class);var statement=mock(Statement.class);
        when(connection.isReadOnly()).thenReturn(true);when(connection.createStatement()).thenReturn(statement);
        var dialect=DatabaseDialect.forType(config.type());dialect.prepare(connection,config);
        var order=inOrder(connection,statement);
        order.verify(connection).setReadOnly(true);order.verify(connection).setAutoCommit(false);
        order.verify(connection).isReadOnly();order.verify(connection).setSchema("APP");
        order.verify(connection).createStatement();order.verify(statement).setQueryTimeout(10);
        order.verify(statement).execute("SET TRANSACTION READ ONLY");order.verify(statement).close();
        when(statement.execute(anyString())).thenThrow(new SQLException("unsupported"));
        assertThatThrownBy(()->dialect.prepare(connection,config)).isInstanceOf(SQLException.class);
    }
    @Test void db2SetsSchemaAndEveryVendorProbeRemainsWithinReadOnlySqlPolicy() throws Exception {
        var config=input(DatabaseConfig.Type.DB2,"jdbc:db2://db:50000/app");
        var connection=mock(Connection.class);when(connection.isReadOnly()).thenReturn(true);
        DatabaseDialect.forType(config.type()).prepare(connection,config);
        verify(connection).setSchema("APP");verify(connection).setAutoCommit(false);
        for(var profile:BundledDatabaseDrivers.defaults()) {
            var c=BundledDatabaseDriversTest.configuration(profile.id(),null);
            var dialect=DatabaseDialect.forType(c.type());
            assertThat(ReadOnlySqlPolicy.validate(dialect.probeSql(),c)).isEqualTo(dialect.probeSql());
        }
    }
}
