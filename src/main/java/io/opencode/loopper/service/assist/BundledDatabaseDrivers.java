package io.opencode.loopper.service.assist;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Immutable version profiles. Never replace an existing profile: frozen tasks retain this identity. */
public final class BundledDatabaseDrivers {
    private BundledDatabaseDrivers() { }
    public record Binary(String filename,String sha256) { }
    public record Profile(DatabaseConfig.Type type,String label,String id,String driverClass,int defaultPort,List<Binary> binaries) { }
    public static final List<Profile> PROFILES=List.of(new Profile(DatabaseConfig.Type.MYSQL,"MySQL","mysql-8.0.33","com.mysql.cj.jdbc.Driver",3306,List.of(new Binary("mysql-connector-j-8.0.33.jar","e2a3b2fc726a1ac64e998585db86b30fa8bf3f706195b78bb77c5f99bf877bd9"),new Binary("protobuf-java-3.25.5.jar","8540247fad9e06baefa8fb45eb313802d019f485f14300e0f9d6b556ed88e753"))),
        new Profile(DatabaseConfig.Type.GAUSSDB,"GaussDB","gaussdb-opengauss-3.1.0","org.postgresql.Driver",5432,gauss310()),
        new Profile(DatabaseConfig.Type.OPENGAUSS,"openGauss","opengauss-3.1.0","org.postgresql.Driver",5432,gauss310()),
        new Profile(DatabaseConfig.Type.ORACLE,"Oracle","oracle-23.7.0.25.01","oracle.jdbc.OracleDriver",1521,List.of(new Binary("ojdbc11-23.7.0.25.01.jar","ec8b7f2020b03b19f572e1bc34f94330610e86d3113ffe1e1f0474b8f5ce88ed"))),
        new Profile(DatabaseConfig.Type.SQLSERVER,"SQL Server","sqlserver-13.4.0.jre11","com.microsoft.sqlserver.jdbc.SQLServerDriver",1433,List.of(new Binary("mssql-jdbc-13.4.0.jre11.jar","e36f5237c1267983e5b88dc2169f6b9d7e50eceec6dc1ca31018e3877e14af66"))),
        new Profile(DatabaseConfig.Type.DB2,"DB2","db2-12.1.0.0","com.ibm.db2.jcc.DB2Driver",50000,List.of(new Binary("jcc-12.1.0.0.jar","29a514dd740a20661d6a22f67a3a118e2c11dc14fa4056e4faeb08afb1738fd8"))),
        new Profile(DatabaseConfig.Type.OPENGAUSS,"openGauss","opengauss-7.0.0-RC3-og","org.opengauss.Driver",5432,List.of(new Binary("opengauss-jdbc-7.0.0-RC3-og.jar","a80bc5b50b8af012d8f99d20546885aa218673dab9c44aa64833abed65745a69"))),
        new Profile(DatabaseConfig.Type.OPENGAUSS,"openGauss","opengauss-6.0.3","org.postgresql.Driver",5432,List.of(new Binary("opengauss-jdbc-6.0.3.jar","4ee4117006db3d1bf905436e25ee96ae5e32cba31dc15a34963474e894c98f63"),new Binary("slf4j-api-2.0.17.jar","7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832"))),
        new Profile(DatabaseConfig.Type.DAMENG,"达梦","dameng-8.1.3.140","dm.jdbc.driver.DmDriver",5236,List.of(new Binary("DmJdbcDriver18-8.1.3.140.jar","9af4ff4d6ed15948507f528a18ab9b7196b3600d9169ad7998c19869031a3c6f"))));
    private static List<Binary> gauss310() {
        return List.of(new Binary("opengauss-jdbc-3.1.0.jar","a6c3c1854bacb4020537a50a9dd01265e4db92760ddae78c8df0f07a60722c3a"),
                new Binary("slf4j-api-2.0.17.jar","7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832"));
    }
    /** One default per product for new connections; all immutable profiles remain available for recovery. */
    public static List<Profile> defaults() {
        Map<DatabaseConfig.Type,Profile> defaults = new LinkedHashMap<>();
        PROFILES.forEach(p -> defaults.putIfAbsent(p.type(),p));
        return List.copyOf(defaults.values());
    }
    public static Profile profile(String id) {
        return PROFILES.stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(BundledDatabaseDrivers::unavailable);
    }
    public static DatabaseConfig resolve(DatabaseConfig c) {
        c = JdbcConnectionUrl.normalize(c);
        DatabaseConfig input = c;
        Profile p=c.driverProfile()!=null ? profile(c.driverProfile()) : defaults().stream().filter(x->x.type()==input.type()).findFirst().orElseThrow(()->
            new AssistFailure("DATABASE_TYPE_UNAVAILABLE","当前可新增 MySQL、GaussDB、openGauss、Oracle、DB2、SQL Server 和达梦；其他类型请保留历史配置","CONFIGURE"));
        if(p.type()!=c.type() || c.driverProfile()!=null && !p.id().equals(c.driverProfile())
            || c.driverFile()!=null && !c.driverFile().isBlank() && !p.binaries().getFirst().filename().equals(c.driverFile())
            || c.driverClass()!=null && !c.driverClass().isBlank() && !p.driverClass().equals(c.driverClass()))
            throw new AssistFailure("DATABASE_DRIVER_MISMATCH","驱动由数据库类型自动匹配，请刷新配置后重试");
        return new DatabaseConfig(c.type(),c.host(),c.port(),c.database(),c.username(),p.binaries().getFirst().filename(),
            p.driverClass(),c.schemas(),c.parameters(),c.timeoutSeconds(),c.maxRows(),p.id(),c.jdbcUrl()).validated();
    }
    public static List<Path> materialize(Path root,DatabaseConfig c) throws Exception {
        Profile p=profile(c.driverProfile());
        if(p.type()!=c.type() || !p.driverClass().equals(c.driverClass()) || !p.binaries().getFirst().filename().equals(c.driverFile()))throw unavailable();
        Path dir=root.resolve(p.id());
        DatabaseSecretStore.secureDirectory(dir);
        List<Path> result=new ArrayList<>();
        for(Binary binary:p.binaries()) {
            Path dest=dir.resolve(binary.filename());
            if(!Files.exists(dest,LinkOption.NOFOLLOW_LINKS)) {
                try(var input=BundledDatabaseDrivers.class.getResourceAsStream("/jdbc-bundled/"+p.id()+"/"+binary.filename())) {
                    if(input==null)throw unavailable();
                    byte[] bytes=input.readNBytes(64*1024*1024+1);
                    if(bytes.length>64*1024*1024 || !hash(bytes).equals(binary.sha256()))throw unavailable();
                    Path temp=Files.createTempFile(dir,"driver-",".tmp");
                    try {Files.write(temp,bytes); Files.move(temp,dest,StandardCopyOption.ATOMIC_MOVE);}finally {Files.deleteIfExists(temp);}
                }
            }
            DatabaseSecretStore.requireRegular(dest);
            if(Files.size(dest)>64L*1024*1024 || !hash(Files.readAllBytes(dest)).equals(binary.sha256()))throw unavailable();
            result.add(dest);
        }
        return List.copyOf(result);
    }
    static String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static AssistFailure unavailable(){return new AssistFailure("DATABASE_DRIVER_UNAVAILABLE","内置驱动缺失或校验失败，请重新获取完整安装包；不要手动替换驱动文件","CONFIGURE");}
}
