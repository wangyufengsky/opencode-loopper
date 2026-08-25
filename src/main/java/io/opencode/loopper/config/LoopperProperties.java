package io.opencode.loopper.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
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
    private Duration designerMonitorDelay = Duration.ofMillis(750);
    private Duration designerTimeout = Duration.ofMinutes(15);
    private Duration taskProfileRouterTimeout = Duration.ofSeconds(240);
    private Duration projectConventionStallTimeout = Duration.ofSeconds(240);
    /** Optional canonical parent directory for newly registered projects. */
    private String allowedRoot = "";
    /** Follow-up abort attempts after a mutating Session could not be confirmed stopped. */
    private int abortCleanupAttempts = 3;
    private RetryWait retryWait = new RetryWait();
    private Mcp mcp = new Mcp();
    private OpenCode openCode = new OpenCode();
    private Publication publication = new Publication();
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
    public Duration getDesignerMonitorDelay() { return designerMonitorDelay; }
    public void setDesignerMonitorDelay(Duration value) { this.designerMonitorDelay = value; }
    public Duration getDesignerTimeout() { return designerTimeout; }
    public void setDesignerTimeout(Duration value) { this.designerTimeout = value; }
    public Duration getTaskProfileRouterTimeout() { return taskProfileRouterTimeout; }
    public void setTaskProfileRouterTimeout(Duration value) { this.taskProfileRouterTimeout = value; }
    public Duration getProjectConventionStallTimeout() { return projectConventionStallTimeout; }
    public void setProjectConventionStallTimeout(Duration value) { this.projectConventionStallTimeout = value; }
    public String getAllowedRoot() { return allowedRoot; }
    public void setAllowedRoot(String value) { this.allowedRoot = value; }
    public int getAbortCleanupAttempts() { return abortCleanupAttempts; }
    public void setAbortCleanupAttempts(int value) { this.abortCleanupAttempts = value; }
    public RetryWait getRetryWait() { return retryWait; }
    public void setRetryWait(RetryWait value) { this.retryWait = value == null ? new RetryWait() : value; }
    public static class RetryWait {
        private Duration rateLimitBase = Duration.ofSeconds(60);
        private Duration rateLimitMax = Duration.ofSeconds(300);
        private Duration sessionBase = Duration.ofSeconds(10);
        private Duration sessionMax = Duration.ofSeconds(60);
        private Duration verificationBase = Duration.ofSeconds(5);
        private Duration verificationMax = Duration.ofSeconds(30);
        public Duration getRateLimitBase() { return rateLimitBase; }
        public void setRateLimitBase(Duration value) { this.rateLimitBase = value; }
        public Duration getRateLimitMax() { return rateLimitMax; }
        public void setRateLimitMax(Duration value) { this.rateLimitMax = value; }
        public Duration getSessionBase() { return sessionBase; }
        public void setSessionBase(Duration value) { this.sessionBase = value; }
        public Duration getSessionMax() { return sessionMax; }
        public void setSessionMax(Duration value) { this.sessionMax = value; }
        public Duration getVerificationBase() { return verificationBase; }
        public void setVerificationBase(Duration value) { this.verificationBase = value; }
        public Duration getVerificationMax() { return verificationMax; }
        public void setVerificationMax(Duration value) { this.verificationMax = value; }
    }
    public Mcp getMcp() { return mcp; }
    public void setMcp(Mcp value) { this.mcp = value; }
    public static class Mcp {
        private String bearerToken = "";
        public String getBearerToken() { return bearerToken; }
        public void setBearerToken(String value) { this.bearerToken = value; }
    }
    public OpenCode getOpenCode() { return openCode; }
    public void setOpenCode(OpenCode value) { this.openCode = value; }
    public Publication getPublication() { return publication; }
    public void setPublication(Publication value) { this.publication = value; }
    public static class Publication {
        /** Exact Git hosts whose SSH remotes should open their web UI over HTTP. */
        private Set<String> httpWebHosts = Set.of();
        private GitLab gitlab = new GitLab();
        public Set<String> getHttpWebHosts() { return httpWebHosts; }
        public void setHttpWebHosts(Set<String> value) { this.httpWebHosts = value == null ? Set.of() : value; }
        public GitLab getGitlab() { return gitlab; }
        public void setGitlab(GitLab value) { this.gitlab = value == null ? new GitLab() : value; }
    }
    public static class GitLab {
        private String host = "";
        private URI apiBaseUrl;
        private String privateToken = "";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(10);
        public String getHost() { return host; }
        public void setHost(String value) { this.host = value; }
        public URI getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(URI value) { this.apiBaseUrl = value; }
        public String getPrivateToken() { return privateToken; }
        public void setPrivateToken(String value) { this.privateToken = value; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration value) { this.connectTimeout = value; }
        public Duration getRequestTimeout() { return requestTimeout; }
        public void setRequestTimeout(Duration value) { this.requestTimeout = value; }
    }
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
