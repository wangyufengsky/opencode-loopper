package io.opencode.loopper.service.assist;

import io.opencode.loopper.TestJvm;
import io.opencode.loopper.config.LoopperProperties;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Loopback authentication exchange with the exact MCP-database driver; no live database or account. */
class GaussAuthenticationProtocolTest {
    @TempDir Path temp;
    @Test void mcpDriverSendsUnchangedCredentialDigestUsingPostgresqlUrl() throws Exception { TestJvm.run(GaussAuthenticationProtocolTest.class,temp); }
    public static void main(String[] args) throws Exception {
        System.setProperty("socksNonProxyHosts","localhost|127.*|[::1]");
        var properties=new LoopperProperties();properties.setDataDir(Path.of(args[0]).toRealPath());
        var registry=new DatabaseDriverRegistry(properties);
        try {
            for(String id:List.of("gaussdb-opengauss-3.1.0","opengauss-3.1.0")) exchange(registry,id);
        } finally { registry.close(); }
        System.exit(0);
    }
    private static void exchange(DatabaseDriverRegistry registry,String profile) throws Exception {
        String password=" p@ss+&=%密 "; byte[] salt={1,3,5,7};
        try(var server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var worker=Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(10000);
            Future<String> received=worker.submit(()->respond(server,salt));
            var config=BundledDatabaseDriversTest.configuration(profile,"jdbc:postgresql://127.0.0.1:"+server.getLocalPort()+"/app?sslmode=disable");
            assertThat(config.driverFile()).isEqualTo("opengauss-jdbc-3.1.0.jar");
            assertThatThrownBy(()->registry.diagnose(config,password)).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("28P01"));
            String first=md5((password+config.username()).getBytes(StandardCharsets.UTF_8));
            var combined=new ByteArrayOutputStream();combined.write(first.getBytes(StandardCharsets.US_ASCII));combined.write(salt);
            assertThat(received.get(10,TimeUnit.SECONDS).equals("md5"+md5(combined.toByteArray()))).as("credential digest preserved").isTrue();
        }
    }
    private static String respond(ServerSocket server,byte[] salt) throws Exception {
        try(var socket=server.accept()) {
            socket.setSoTimeout(10000);var in=new DataInputStream(socket.getInputStream());var out=new DataOutputStream(socket.getOutputStream());
            int size=in.readInt();if(size<8 || size>16384)throw new IOException("Invalid startup size");
            byte[] startup=in.readNBytes(size-4);assertThat(startup.length).isEqualTo(size-4);
            out.writeByte('R');out.writeInt(12);out.writeInt(5);out.write(salt);out.flush();
            assertThat(in.readByte()).isEqualTo((byte)'p');int length=in.readInt();
            if(length<5 || length>1024)throw new IOException("Invalid authentication size");
            byte[] response=in.readNBytes(length-4);
            byte[] error="SFATAL\0C28P01\0Mfixture authentication rejected\0\0".getBytes(StandardCharsets.UTF_8);
            out.writeByte('E');out.writeInt(error.length+4);out.write(error);out.flush();
            return new String(response,0,response.length-1,StandardCharsets.US_ASCII);
        }
    }
    private static String md5(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)); }
}
