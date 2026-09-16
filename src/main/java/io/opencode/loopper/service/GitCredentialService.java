package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Global default and explicit project override; encrypted versions stay outside SQLite. */
@Service
public class GitCredentialService implements GitCredentialProvider {
    private final GitCredentialMapper mapper;
    private final LoopperMapper projects;
    private final EncryptedSecretStore secrets;
    private final TransactionTemplate transactions;
    @org.springframework.beans.factory.annotation.Autowired
    public GitCredentialService(GitCredentialMapper mapper, LoopperMapper projects, LoopperProperties properties,
                                PlatformTransactionManager manager) {
        this(mapper, projects, new EncryptedSecretStore(properties.getDataDir().resolve("git-secrets"),
                Path.of(System.getProperty("user.home"), ".opencode-loopper", "keys", "git-master.key"),
                System.getenv("LOOPPER_GIT_MASTER_KEY")), manager);
    }
    GitCredentialService(GitCredentialMapper mapper, LoopperMapper projects, EncryptedSecretStore secrets,
                         PlatformTransactionManager manager) {
        this.mapper = mapper; this.projects = projects; this.secrets = secrets;
        this.transactions = new TransactionTemplate(manager);
    }
    public record Request(String mode, String serverUrl, String username, String kind, String secret, long version, String repositoryUrl) {
        @Override public String toString() { return "GitCredentialRequest[redacted]"; }
    }
    public record View(String mode, String serverUrl, String username, String kind, boolean configured,
                       String source, long version, String updatedAt) { }
    public View get(String projectId) {
        String scope = scope(projectId);
        var own = mapper.find(scope).orElse(null);
        var effective = effective(own, projectId);
        String mode = own == null ? projectId == null ? "DISABLED" : "INHERIT" : own.mode();
        return new View(mode, effective == null ? "" : effective.serverUrl(), effective == null ? "" : effective.username(),
                effective == null ? "TOKEN" : effective.kind(), effective != null,
                effective == null ? "SYSTEM" : effective.projectId() == null ? "GLOBAL" : "PROJECT",
                own == null ? 0 : own.version(), own == null ? null : own.updatedAt());
    }
    public View save(String projectId, Request request) {
        String scope = scope(projectId);
        var old = mapper.find(scope).orElse(null);
        validateVersion(old, request);
        String mode = mode(projectId, request);
        String origin = null, username = null, kind = null, reference = null;
        if (mode.equals("CUSTOM")) {
            origin = server(request.serverUrl()); username = username(request.username()); kind = kind(request.kind());
            String password = password(request, old, origin, username, kind);
            reference = request.secret() == null || request.secret().isEmpty() ? old.secretRef() : encrypt(password);
        }
        String now = Instant.now().toString();
        var row = new GitCredentialMapper.Row(scope, projectId, mode, origin, username, kind, reference, request.version() + 1, now);
        transactions.executeWithoutResult(status -> {
            if (mapper.save(row, request.version()) != 1) throw conflict();
            mapper.audit(UUID.randomUUID().toString(), scope, mode, row.version(), now);
        });
        return get(projectId);
    }
    @Override public Map<String, String> environment(Path registeredProject, String remoteUrl) {
        String projectId;
        try {
            projectId = projects.findProjectByRoot(registeredProject.toRealPath().toString()).map(ProjectRow::id).orElse(null);
        } catch (java.io.IOException invalid) { throw unavailable(); }
        // A private execution directory must be accompanied by its actual registered project path.
        if (projectId == null) return Map.of();
        var row = effective(mapper.find("project:" + projectId).orElse(null), projectId);
        if (row == null) return Map.of();
        String target = GitHttpAuthentication.origin(remoteUrl);
        if (!row.serverUrl().equals(target)) {
            if (row.projectId() != null) throw new TaskFailure("GIT_CREDENTIAL_HOST_MISMATCH", "项目独立账号与 Git 服务器不匹配，请检查项目 Git 账号");
            return Map.of();
        }
        return GitHttpAuthentication.environment(row.serverUrl(), row.username(), decrypt(row.secretRef()), remoteUrl);
    }
    public Map<String, String> testEnvironment(String projectId, Request request, String remoteUrl) {
        var old = mapper.find(scope(projectId)).orElse(null);
        validateVersion(old, request);
        if (mode(projectId, request).equals("INHERIT")) {
            var global = effective(null, projectId);
            if (global == null) throw new BadRequestException("GIT_CREDENTIAL_REQUIRED", "请先配置全局 Git 账号，或为项目设置独立账号");
            return GitHttpAuthentication.environment(global.serverUrl(), global.username(), decrypt(global.secretRef()), remoteUrl);
        }
        if (!request.mode().equals("CUSTOM")) throw new BadRequestException("GIT_CREDENTIAL_REQUIRED", "请先填写 Git 账号");
        String origin = server(request.serverUrl()), username = username(request.username()), kind = kind(request.kind());
        return GitHttpAuthentication.environment(origin, username, password(request, old, origin, username, kind), remoteUrl);
    }
    public Path projectRoot(String projectId) {
        return Path.of(projects.findProject(projectId).orElseThrow(() -> new NotFoundException("项目不存在")).rootPath());
    }
    private String scope(String projectId) {
        if (projectId != null) projectRoot(projectId);
        return projectId == null ? "global" : "project:" + projectId;
    }
    private GitCredentialMapper.Row effective(GitCredentialMapper.Row own, String projectId) {
        if (own != null && own.mode().equals("CUSTOM")) return own;
        if (projectId == null) return null;
        return mapper.find("global").filter(row -> row.mode().equals("CUSTOM")).orElse(null);
    }
    private static String mode(String projectId, Request r) {
        if (r == null || r.mode() == null || !(projectId == null ? Set.of("CUSTOM", "DISABLED") : Set.of("CUSTOM", "INHERIT")).contains(r.mode()))
            throw new BadRequestException("GIT_CREDENTIAL_MODE_INVALID", "请选择全局默认账号或项目独立账号");
        return r.mode();
    }
    private String password(Request request, GitCredentialMapper.Row old, String origin, String username, String kind) {
        if (request.secret() != null && !request.secret().isEmpty()) {
            if (request.secret().length() > 4096 || request.secret().chars().anyMatch(Character::isISOControl))
                throw new BadRequestException("GIT_CREDENTIAL_SECRET_INVALID", "密码或令牌格式无效");
            return request.secret();
        }
        if (old == null || !old.mode().equals("CUSTOM") || !origin.equals(old.serverUrl()) || !username.equals(old.username()) || !kind.equals(old.kind()))
            throw new BadRequestException("GIT_CREDENTIAL_SECRET_REQUIRED", "首次保存或更换服务器、账号、认证方式时，请重新输入密码或令牌");
        return decrypt(old.secretRef());
    }
    private String encrypt(String value) { try { return secrets.save(value); } catch (RuntimeException failure) { throw unavailable(); } }
    private String decrypt(String ref) { try { return secrets.read(ref); } catch (RuntimeException failure) { throw unavailable(); } }
    private static String server(String value) {
        if (value == null || value.length() > 2048) throw new BadRequestException("GIT_CREDENTIAL_URL_INVALID", "请填写 Git 服务器地址");
        String origin = GitHttpAuthentication.origin(value.trim());
        String path = java.net.URI.create(value.trim()).getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/"))
            throw new BadRequestException("GIT_CREDENTIAL_URL_INVALID", "服务器地址只填写协议、主机和端口，不包含仓库路径");
        return origin;
    }
    private static String username(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.contains(":") || value.chars().anyMatch(Character::isISOControl))
            throw new BadRequestException("GIT_CREDENTIAL_USERNAME_INVALID", "请填写有效的 Git 用户名");
        return value.trim();
    }
    private static String kind(String value) {
        if (!Set.of("TOKEN", "PASSWORD").contains(value == null ? "" : value)) throw new BadRequestException("GIT_CREDENTIAL_KIND_INVALID", "请选择访问令牌或账号密码");
        return value;
    }
    private static void validateVersion(GitCredentialMapper.Row old, Request request) {
        if (request == null || request.version() < 0 || (old == null ? 0 : old.version()) != request.version()) throw conflict();
    }
    private static ConflictException conflict() { return new ConflictException("GIT_CREDENTIAL_VERSION_CONFLICT", "Git 账号设置已变化，请重新加载后保存"); }
    private static TaskFailure unavailable() { return new TaskFailure("GIT_CREDENTIAL_UNAVAILABLE", "Git 凭据无法安全保存或解密，请检查主密钥及目录权限"); }
}
