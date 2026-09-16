package io.opencode.loopper.service.assist;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.EncryptedSecretStore;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Keeps the existing database key, encrypted-file format and public error contract. */
@Component
public class DatabaseSecretStore {
    private final EncryptedSecretStore store;
    @org.springframework.beans.factory.annotation.Autowired
    public DatabaseSecretStore(LoopperProperties properties) {
        this(properties.getDataDir().resolve("database-secrets"),
                Path.of(System.getProperty("user.home"), ".opencode-loopper", "keys", "database-master.key"),
                System.getenv("LOOPPER_DATABASE_MASTER_KEY"));
    }
    DatabaseSecretStore(Path directory, Path keyFile, String environmentKey) {
        store = new EncryptedSecretStore(directory, keyFile, environmentKey);
    }
    public String save(String password) {
        try { return store.save(password); } catch (RuntimeException failure) { throw unavailable(); }
    }
    public String read(String reference) {
        try { return store.read(reference); } catch (RuntimeException failure) { throw unavailable(); }
    }
    static void secureDirectory(Path path) throws Exception {
        try { EncryptedSecretStore.secureDirectory(path); } catch (RuntimeException failure) { throw unavailable(); }
    }
    static void requireRegular(Path path) throws Exception {
        try { EncryptedSecretStore.requireRegular(path); } catch (RuntimeException failure) { throw unavailable(); }
    }
    private static AssistFailure unavailable() {
        return new AssistFailure("DATABASE_CREDENTIAL_UNAVAILABLE", "数据库凭据无法安全保存或解密，请检查主密钥和目录权限；缺失密钥时重新配置凭据", "CONFIGURE");
    }
}
