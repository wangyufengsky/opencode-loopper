package io.opencode.loopper;

import io.opencode.loopper.config.LoopperProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@MapperScan("io.opencode.loopper.persistence")
@EnableConfigurationProperties(LoopperProperties.class)
public class LoopperApplication {
    public static void main(String[] args) {
        // The SQLite driver creates the file but not its parent; make the default/env data root available before datasource auto-configuration.
        try { Files.createDirectories(Path.of(System.getenv().getOrDefault("LOOPPER_DATA_DIR", "./data")).toAbsolutePath().normalize()); }
        catch (Exception e) { throw new IllegalStateException("Unable to create Loopper data directory", e); }
        SpringApplication.run(LoopperApplication.class, args);
    }
}
