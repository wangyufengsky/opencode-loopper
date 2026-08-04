package io.opencode.loopper.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("loopper")
public class LoopperProperties {
    private Path dataDir = Path.of("./data");
    private int maxStageAttempts = 3;
    private int maxTaskAttempts = 12;
    private int sessionErrorLimit = 3;
    private Duration maxDuration = Duration.ofHours(2);
    private Duration attemptTimeout = Duration.ofMinutes(30);
    private Duration verifierTimeout = Duration.ofMinutes(10);
    private Duration monitorDelay = Duration.ofSeconds(2);
    /** Follow-up abort attempts after a mutating Session could not be confirmed stopped. */
    private int abortCleanupAttempts = 3;
    private Mcp mcp = new Mcp();
    private OpenCode openCode = new OpenCode();
    public Path getDataDir() { return dataDir; }
    public void setDataDir(Path dataDir) { this.dataDir = dataDir; }
    public int getMaxStageAttempts() { return maxStageAttempts; }
    public void setMaxStageAttempts(int value) { this.maxStageAttempts = value; }
    public int getMaxTaskAttempts() { return maxTaskAttempts; }
    public void setMaxTaskAttempts(int value) { this.maxTaskAttempts = value; }
    public int getSessionErrorLimit() { return sessionErrorLimit; }
    public void setSessionErrorLimit(int value) { this.sessionErrorLimit = value; }
    public Duration getMaxDuration() { return maxDuration; }
    public void setMaxDuration(Duration value) { this.maxDuration = value; }
    public Duration getAttemptTimeout() { return attemptTimeout; }
    public void setAttemptTimeout(Duration value) { this.attemptTimeout = value; }
    public Duration getVerifierTimeout() { return verifierTimeout; }
    public void setVerifierTimeout(Duration value) { this.verifierTimeout = value; }
    public Duration getMonitorDelay() { return monitorDelay; }
    public void setMonitorDelay(Duration value) { this.monitorDelay = value; }
    public int getAbortCleanupAttempts() { return abortCleanupAttempts; }
    public void setAbortCleanupAttempts(int value) { this.abortCleanupAttempts = value; }
    public Mcp getMcp() { return mcp; }
    public void setMcp(Mcp value) { this.mcp = value; }
    public static class Mcp {
        private String bearerToken = "";
        public String getBearerToken() { return bearerToken; }
        public void setBearerToken(String value) { this.bearerToken = value; }
    }
    public OpenCode getOpenCode() { return openCode; }
    public void setOpenCode(OpenCode value) { this.openCode = value; }
    public static class OpenCode {
        /**
         * fake is deterministic for tests, http connects to an operator-owned server,
         * and auto may start a short-lived local server owned by this application.
         */
        private String mode = "auto";
        private URI baseUrl = URI.create("http://127.0.0.1:4096");
        private String username = "";
        private String password = "";
        /** Explicit binary path (or command name) wins over OPENCODE_EXECUTABLE and PATH. */
        private String executable = "";
        /** Optional provider/model display and task default, for example opencode/deepseek-v4-flash-free. */
        private String model = "";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Duration startupTimeout = Duration.ofSeconds(15);
        public String getMode() { return mode; }
        public void setMode(String value) { this.mode = value; }
        public URI getBaseUrl() { return baseUrl; }
        public void setBaseUrl(URI value) { this.baseUrl = value; }
        public String getUsername() { return username; }
        public void setUsername(String value) { this.username = value; }
        public String getPassword() { return password; }
        public void setPassword(String value) { this.password = value; }
        public String getExecutable() { return executable; }
        public void setExecutable(String value) { this.executable = value; }
        public String getModel() { return model; }
        public void setModel(String value) { this.model = value; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration value) { this.connectTimeout = value; }
        public Duration getRequestTimeout() { return requestTimeout; }
        public void setRequestTimeout(Duration value) { this.requestTimeout = value; }
        public Duration getStartupTimeout() { return startupTimeout; }
        public void setStartupTimeout(Duration value) { this.startupTimeout = value; }
    }
}
