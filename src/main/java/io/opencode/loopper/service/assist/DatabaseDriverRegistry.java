package io.opencode.loopper.service.assist;

import io.opencode.loopper.config.LoopperProperties;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Administrator-installed JDBC binaries are isolated from the application's own SQLite driver. */
@Component
public class DatabaseDriverRegistry {
    private final Path root;
    private final Map<String,Loaded> loaded=new LinkedHashMap<>();
    private record Loaded(URLClassLoader loader,Driver driver) { }
    public DatabaseDriverRegistry(LoopperProperties properties) { root=properties.getDataDir().resolve("jdbc-drivers").toAbsolutePath().normalize(); }
    public record DriverInfo(String filename,String sha256,long sizeBytes) { }
    public List<DriverInfo> inventory() {
        if(!Files.exists(root)) return List.of();
        try(var files=Files.list(root)) {
            var paths=files.filter(p->p.getFileName().toString().endsWith(".jar")).limit(65).toList();
            if(paths.size()>64) throw unavailable();
            return paths.stream().map(p->{ try { return inspect(p); } catch(Exception e) { throw unavailable(); } }).toList();
        } catch(Exception e) { throw unavailable(); }
    }
    public Opened open(DatabaseConfig c,String password) throws Exception {
        return connect(c,password,true);
    }
    Opened diagnose(DatabaseConfig c,String password) throws Exception { return connect(c,password,false); }
    private Opened connect(DatabaseConfig c,String password,boolean enforceReadOnly) throws Exception {
        List<Path> paths;
        DriverInfo info;
        if(c.driverProfile()!=null) {
            paths=BundledDatabaseDrivers.materialize(root.resolveSibling("jdbc-bundled"),c);
            Path first=paths.getFirst();info=new DriverInfo(first.getFileName().toString(),BundledDatabaseDrivers.profile(c.driverProfile()).binaries().getFirst().sha256(),Files.size(first));
        } else {
            Path path=root.resolve(c.driverFile());
            try {info=inspect(path);} catch(Exception invalidDriver) {throw unavailable();}
            paths=List.of(path);
        }
        Loaded holder=load(paths,c.driverClass(),c.driverProfile()==null?info.sha256():c.driverProfile()+info.sha256());
        Connection connection=null;
        try {
            Driver driver=holder.driver();
            DatabaseDialect dialect=DatabaseDialect.forType(c.type());
            connection=driver.connect(dialect.url(c),dialect.properties(c,password));
            if(connection==null) throw unavailable();
            if(enforceReadOnly)dialect.prepare(connection,c);
            return new Opened(connection,holder.loader(),info,driver.getMajorVersion()+"."+driver.getMinorVersion(),false);
        } catch(Exception e) { if(connection!=null)try { connection.close(); } catch(Exception ignored) { }
            throw e; }
    }
    private synchronized Loaded load(List<Path> paths,String driverClass,String sha) throws Exception {
        String key=sha+":"+driverClass;Loaded existing=loaded.get(key);if(existing!=null)return existing;
        if(loaded.size()>=64)throw new AssistFailure("DATABASE_DRIVER_GENERATION_LIMIT","当前进程已加载 64 个驱动版本，请在维护窗口重启后使用新版本","CONFIGURE");
        var urls=new java.net.URL[paths.size()];for(int i=0;i<paths.size();i++)urls[i]=paths.get(i).toUri().toURL();
        var loader=new URLClassLoader(urls,ClassLoader.getPlatformClassLoader());
        try {var result=new Loaded(loader,(Driver)Class.forName(driverClass,true,loader).getDeclaredConstructor().newInstance());loaded.put(key,result);return result;}
        catch(Exception failure){loader.close();throw failure;}
    }
    @jakarta.annotation.PreDestroy public synchronized void close() {
        for(Loaded holder:loaded.values())try{holder.loader().close();}catch(java.io.IOException ignored){}
        loaded.clear();
    }
    private DriverInfo inspect(Path path) throws Exception {
        if(!path.normalize().getParent().equals(root)) throw unavailable();
        DatabaseSecretStore.requireRegular(path); long size=Files.size(path); if(size>64L*1024*1024) throw unavailable();
        return new DriverInfo(path.getFileName().toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))),size);
    }
    public record Opened(Connection connection,URLClassLoader loader,DriverInfo info,String driverVersion,boolean closeLoader) implements AutoCloseable {
        public Opened(Connection connection,URLClassLoader loader,DriverInfo info,String driverVersion){this(connection,loader,info,driverVersion,true);}
        public void close() throws Exception { try { try { if(!connection.getAutoCommit())connection.rollback(); } finally { connection.close(); } } finally { if(closeLoader)loader.close(); } }
    }
    private static AssistFailure unavailable() { return new AssistFailure("DATABASE_DRIVER_UNAVAILABLE","请在受管数据目录的 jdbc-drivers 中安装匹配的厂商 JDBC 驱动并检查文件权限","CONFIGURE"); }
}
