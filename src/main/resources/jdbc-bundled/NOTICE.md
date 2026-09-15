# 内置 JDBC 驱动
构建时获取下列固定 Maven 坐标；运行时只读 JAR 资源，不下载。厂商原始 JAR 的 LICENSE/NOTICE 保持完整。仅开放普通用户名密码 JDBC；OCI、SM 算法及分片扩展不在本版范围。
- `com.mysql:mysql-connector-j:8.0.33` — SHA-256 `e2a3b2fc726a1ac64e998585db86b30fa8bf3f706195b78bb77c5f99bf877bd9`
- `com.google.protobuf:protobuf-java:3.25.5` — SHA-256 `8540247fad9e06baefa8fb45eb313802d019f485f14300e0f9d6b556ed88e753`
- `org.opengauss:opengauss-jdbc:3.1.0` — GaussDB/openGauss 默认，与 MCP-database 对齐；类 `org.postgresql.Driver`，协议 `jdbc:postgresql:`。SHA-256 `a6c3c1854bacb4020537a50a9dd01265e4db92760ddae78c8df0f07a60722c3a`
- `com.oracle.database.jdbc:ojdbc11:23.7.0.25.01` — Oracle Thin，类 `oracle.jdbc.OracleDriver`。SHA-256 `ec8b7f2020b03b19f572e1bc34f94330610e86d3113ffe1e1f0474b8f5ce88ed`
- `com.ibm.db2:jcc:12.1.0.0` — DB2 Type 4，类 `com.ibm.db2.jcc.DB2Driver`。SHA-256 `29a514dd740a20661d6a22f67a3a118e2c11dc14fa4056e4faeb08afb1738fd8`
- `org.opengauss:opengauss-jdbc:7.0.0-RC3-og` — 历史 openGauss 驱动，类 `org.opengauss.Driver`；包含 SLF4J 1.7.36（MIT）。SHA-256 `a80bc5b50b8af012d8f99d20546885aa218673dab9c44aa64833abed65745a69`
- `org.opengauss:opengauss-jdbc:6.0.3` — SHA-256 `4ee4117006db3d1bf905436e25ee96ae5e32cba31dc15a34963474e894c98f63`
- `org.slf4j:slf4j-api:2.0.17` — SHA-256 `7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832`
- `com.dameng:DmJdbcDriver18:8.1.3.140` — SHA-256 `9af4ff4d6ed15948507f528a18ab9b7196b3600d9169ad7998c19869031a3c6f`

MySQL: GPLv2 + Universal FOSS Exception; protobuf: BSD 3-Clause; openGauss: BSD 2-Clause; SLF4J: MIT; 达梦: 厂商 Maven POM 标注 Apache-2.0。来源见相同坐标的厂商 POM。MySQL 源码：https://github.com/mysql/mysql-connector-j/tree/8.0.33；openGauss：https://gitee.com/opengauss/openGauss-connector-jdbc；达梦：https://gitee.com/dmedu/dm-jdbc-jars。

Oracle、IBM DB2 的许可和版权以各原始 JAR 内的 LICENSE/NOTICE 及同坐标 POM 为准；保留原文件，未重新打包驱动。GaussDB 支持采用 MCP-database 的 openGauss 3.1.0 连接路径，具体服务端认证、方言和账号权限仍需现场验收。
