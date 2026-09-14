package io.opencode.loopper.service.assist;

import java.math.BigDecimal;
import java.sql.*;
import java.net.URLClassLoader;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseQueryServiceTest {
    @Test void boundedResultsPreserveNullNumbersAndTruncationAndReleaseConnection() throws Exception {
        var drivers=mock(DatabaseDriverRegistry.class);var secrets=mock(DatabaseSecretStore.class);when(secrets.read("ref")).thenReturn("private");
        var connection=mock(Connection.class);var statement=mock(Statement.class);when(connection.createStatement()).thenReturn(statement);
        var rs=mock(ResultSet.class);var md=mock(ResultSetMetaData.class);when(statement.executeQuery(anyString())).thenReturn(rs);when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(2);when(md.getColumnLabel(1)).thenReturn("n");when(md.getColumnLabel(2)).thenReturn("value");when(md.getColumnTypeName(anyInt())).thenReturn("numeric");when(md.getColumnType(anyInt())).thenReturn(Types.DECIMAL);
        when(rs.next()).thenReturn(true,true,false);when(rs.getBigDecimal(1)).thenReturn(BigDecimal.valueOf(42));when(rs.getBigDecimal(2)).thenReturn(null);
        var config=new DatabaseConfig(DatabaseConfig.Type.MYSQL,"host",3306,"app","reader","vendor.jar","vendor.Driver",List.of("app"),Map.of(),1,1);
        when(drivers.open(config,"private")).thenReturn(new DatabaseDriverRegistry.Opened(connection,new URLClassLoader(new java.net.URL[0]),new DatabaseDriverRegistry.DriverInfo("vendor.jar","a".repeat(64),1),"1"));
        try(var queries=new DatabaseQueryService(drivers,secrets,new ObjectMapper())) {
            var result=queries.query(new DatabaseConnectionService.Bound("id","name",config,"ref",7),"SELECT n,value FROM app.t");
            assertThat(result.get("rows")).isEqualTo(List.of(Arrays.asList(BigDecimal.valueOf(42),null)));assertThat(result.get("truncated")).isEqualTo(true);assertThat(result.get("connectionVersion")).isEqualTo(7L);
            verify(connection).rollback();verify(connection).close();verify(statement).setMaxRows(2);
        }
    }
    @Test void authenticationFailureIsActionableWithoutLeakingDriverDetailsOrChangingDraftPassword() throws Exception {
        for(String state:Arrays.asList("28P01","28000","08001",null)) {
            var drivers=mock(DatabaseDriverRegistry.class);var secrets=mock(DatabaseSecretStore.class);
            var config=BundledDatabaseDrivers.resolve(BundledDatabaseDriversTest.input(DatabaseConfig.Type.OPENGAUSS));
            String password=" p@ss+&=%密 ";
            when(drivers.diagnose(config,password)).thenThrow(new SQLException("sensitive-host and "+password,state));
            try(var service=new DatabaseQueryService(drivers,secrets,new ObjectMapper())) {
                assertThatThrownBy(()->service.test(new DatabaseConnectionService.Bound("draft","draft",config,null,0),password))
                        .isInstanceOfSatisfying(AssistFailure.class,f->{
                            assertThat(f.code()).isEqualTo(state!=null && state.startsWith("28")?"DATABASE_AUTHENTICATION_FAILED":"DATABASE_QUERY_FAILED");
                            assertThat(f.getMessage()).doesNotContain(password,"sensitive-host");
                        });
                verifyNoInteractions(secrets);verify(drivers).diagnose(config,password);
            }
        }
    }
    @Test void rejectedSqlNeverOpensAConnection() {
        var drivers=mock(DatabaseDriverRegistry.class);try(var query=new DatabaseQueryService(drivers,mock(DatabaseSecretStore.class),new ObjectMapper())) {
            assertThatThrownBy(()->query.query(new DatabaseConnectionService.Bound("id","name",AssistSafetyTest.config(DatabaseConfig.Type.MYSQL),"ref",0),"DELETE FROM app.t")).isInstanceOf(AssistFailure.class);verifyNoInteractions(drivers);
        }
    }
    @Test void timedOutConnectionsRetainTheirSlotsUntilTheDriverActuallyEnds() throws Exception {
        var drivers=mock(DatabaseDriverRegistry.class);var secrets=mock(DatabaseSecretStore.class);when(secrets.read("ref")).thenReturn("private");
        var entered=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        var config=new DatabaseConfig(DatabaseConfig.Type.MYSQL,"host",3306,"app","reader","vendor.jar","vendor.Driver",List.of("app"),Map.of(),1,1);
        when(drivers.open(config,"private")).thenAnswer(call->{entered.countDown();release.await(15,java.util.concurrent.TimeUnit.SECONDS);throw new SQLException("sensitive driver detail");});
        var bound=new DatabaseConnectionService.Bound("same","name",config,"ref",0);
        try(var service=new DatabaseQueryService(drivers,secrets,new ObjectMapper());var callers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=callers.submit(()->catchThrowable(()->service.query(bound,"SELECT 1")));var b=callers.submit(()->catchThrowable(()->service.query(bound,"SELECT 1")));
            try {
                assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->service.query(bound,"SELECT 1")).isInstanceOf(AssistFailure.class).hasMessageContaining("容量");
                assertThat(a.get(8,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(AssistFailure.class).hasMessageContaining("结果未知");
                assertThat(b.get(8,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(AssistFailure.class);
                assertThatThrownBy(()->service.query(bound,"SELECT 1")).hasMessageContaining("容量");verify(drivers,times(2)).open(config,"private");
            }finally{release.countDown();}
        }
    }
}
