package io.opencode.loopper.service.assist;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlServerSupportTest {
    private DatabaseConfig config(String url) {
        return BundledDatabaseDrivers.resolve(new DatabaseConfig(DatabaseConfig.Type.SQLSERVER,null,0,null,"reader","","",
                List.of("dbo"),Map.of(),10,200,null,url));
    }
    @Test void compilesFixedDriverAndKeepsSecretsOutsideVendorUrl() {
        var c=config("jdbc:sqlserver://db:1433;databaseName=app;encrypt=true;trustServerCertificate=false;");
        var dialect=DatabaseDialect.forType(c.type());
        assertThat(c.driverClass()).isEqualTo("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        assertThat(c.driverProfile()).isEqualTo("sqlserver-13.4.0.jre11");
        assertThat(c.port()).isEqualTo(1433);
        assertThat(dialect.url(c)).isEqualTo("jdbc:sqlserver://db:1433;databaseName=app");
        assertThat(dialect.properties(c," secret+&= ")).containsEntry("password"," secret+&= ")
                .containsEntry("applicationIntent","ReadOnly").containsEntry("loginTimeout","5").containsEntry("socketTimeout","30000");
        assertThat(config("jdbc:sqlserver://[::1];databaseName=app").host()).isEqualTo("::1");
    }
    @Test void rejectsCredentialsAliasesDuplicateParametersAndUncontrolledDriverOptions() {
        for(String suffix:List.of("user=evil","password=secret","integratedSecurity=true","applicationIntent=ReadWrite",
                "socketTimeout=0","trustStore=/tmp/secret","encrypt=true;encrypt=false","Encrypt=false","databaseName=other","encrypt=invalid"))
            assertThatThrownBy(()->config("jdbc:sqlserver://db;databaseName=app;"+suffix)).isInstanceOf(AssistFailure.class);
        for(String url:List.of("jdbc:sqlserver://db\\instance;databaseName=app","jdbc:sqlserver://db/app","jdbc:sqlserver://db:0;databaseName=app"))
            assertThatThrownBy(()->config(url)).isInstanceOf(AssistFailure.class);
    }
    @Test void checksEffectivePermissionsWithoutPretendingJdbcReadOnlyFlagIsSupported() throws Exception {
        var c=config("jdbc:sqlserver://db;databaseName=app");
        var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);var results=mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenReturn(results);
        when(results.next()).thenReturn(true);when(results.getInt(1)).thenReturn(1);
        DatabaseDialect.forType(c.type()).prepare(connection,c);
        verify(connection).setAutoCommit(false);verify(connection,never()).setReadOnly(anyBoolean());
        verify(statement).setQueryTimeout(10);verify(statement).setMaxRows(1);
        verify(statement).setString(1,"dbo");verify(statement).setString(2,"dbo");verify(results).close();
        when(results.getInt(1)).thenReturn(0);
        assertThatThrownBy(()->DatabaseDialect.forType(c.type()).prepare(connection,c)).isInstanceOf(AssistFailure.class).hasMessageContaining("SELECT");
        when(statement.executeQuery()).thenThrow(new SQLException("permission lookup unavailable"));
        assertThatThrownBy(()->DatabaseDialect.forType(c.type()).prepare(connection,c)).isInstanceOf(SQLException.class);
    }
    @Test void requiresExplicitAuthorizedSchemaAndRejectsWritesAndCrossDatabaseAccess() {
        var c=config("jdbc:sqlserver://db;databaseName=app");
        assertThat(ReadOnlySqlPolicy.validate("SELECT id FROM dbo.orders",c)).isEqualTo("SELECT id FROM dbo.orders");
        for(String sql:List.of("SELECT * FROM orders","SELECT * FROM other.orders","SELECT * FROM other.dbo.orders",
                "UPDATE dbo.orders SET id=1","SELECT * INTO dbo.copy FROM dbo.orders","EXEC dbo.proc"))
            assertThatThrownBy(()->ReadOnlySqlPolicy.validate(sql,c)).isInstanceOf(AssistFailure.class);
    }
    @Test void metadataUsesDatabaseCatalogAndAuthorizedSchemaTogether() throws Exception {
        var c=config("jdbc:sqlserver://db;databaseName=app");
        var drivers=mock(DatabaseDriverRegistry.class);var secrets=mock(DatabaseSecretStore.class);
        var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);
        var result=mock(ResultSet.class);var columns=mock(ResultSetMetaData.class);
        when(secrets.read("ref")).thenReturn("secret");when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSearchStringEscape()).thenReturn("\\");
        when(metadata.getTables(eq("app"),eq("dbo"),isNull(),any())).thenReturn(result);
        when(result.getMetaData()).thenReturn(columns);
        when(drivers.open(c,"secret")).thenReturn(new DatabaseDriverRegistry.Opened(connection,null,
                new DatabaseDriverRegistry.DriverInfo("driver.jar","sha",1),"13.4",false));
        try(var service=new DatabaseQueryService(drivers,secrets,new tools.jackson.databind.ObjectMapper())) {
            service.inspect(new DatabaseConnectionService.Bound("id","name",c,"ref",1),"dbo",null,"tables",0);
            verify(metadata).getTables(eq("app"),eq("dbo"),isNull(),any());verify(connection).rollback();verify(connection).close();
        }
    }

}
