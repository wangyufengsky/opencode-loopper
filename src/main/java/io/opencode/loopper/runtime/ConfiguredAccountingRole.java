package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.service.roles.RoleConfigurationService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Supplies a frozen, static role fragment to one native accounting command round. */
@Component
public final class ConfiguredAccountingRole {
    private static final Pattern CONFIGURED_MESSAGE = Pattern.compile("msg_loopper_aicoding_[0-9a-f]{32}_role");
    private static final String SLOT = "ACCOUNTING_COMMAND";
    private static final String FRAGMENT = "accounting.instructions";
    private final RoleConfigurationService roles;
    private final ObjectMapper json;
    private final Path dataDirectory;

    public record Descriptor(Path path, String sha256, String revisionId, String roleSha256) { }

    @Autowired
    public ConfiguredAccountingRole(RoleConfigurationService roles, ObjectMapper json, LoopperProperties properties) {
        this(roles, json, properties.getDataDir());
    }

    ConfiguredAccountingRole(RoleConfigurationService roles, ObjectMapper json, Path dataDirectory) {
        this.roles = roles;
        this.json = json;
        this.dataDirectory = dataDirectory;
    }

    /** Unmarked persisted calls keep their historical Agent behavior. Marked calls must resolve a frozen role. */
    public Descriptor prepare(OpenCodeClient.OpenCodeSession session, OpenCodeClient.CommandRequest request) {
        String messageId = request.messageId();
        if (!messageId.endsWith("_role")) return null;
        if (!CONFIGURED_MESSAGE.matcher(messageId).matches()) throw failure("角色统计消息标识无效");
        var snapshot = roles.sessionSnapshot(session.id()).orElseThrow(() -> failure("业务会话缺少冻结角色快照"));
        var role = roles.resolveFrozen(snapshot.context().owner(), SLOT)
                .orElseThrow(() -> failure("统计命令缺少冻结角色配置"));
        if (!SLOT.equals(role.adapterProfile()) || !SLOT.equals(role.runtimePolicy())
                || !"BASELINE".equals(role.permissionMode())
                || !role.nativeTools().isEmpty() || !role.mcpTools().isEmpty() || !role.requiredMcpTools().isEmpty())
            throw failure("统计角色超出固定原生工具边界");
        String prompt = role.fragments().get(FRAGMENT);
        if (prompt == null || prompt.isBlank() || prompt.getBytes(StandardCharsets.UTF_8).length > 256_000)
            throw failure("统计角色静态提示缺失或过长");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("sessionID", session.id());
        value.put("messageID", messageId);
        value.put("roleID", role.roleId());
        value.put("revisionID", role.revisionId());
        value.put("roleSha256", role.contentSha256());
        value.put("promptSha256", sha256(prompt.getBytes(StandardCharsets.UTF_8)));
        value.put("prompt", prompt);
        byte[] content = json.writeValueAsBytes(value);
        String digest = sha256(content);
        Path directory = dataDirectory.resolve("opencode-plugins").resolve("accounting-role-dispatch");
        Path target = directory.resolve(messageId + "." + digest + ".json");
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw new IOException("Descriptor directory is a symbolic link");
            restrict(directory, true);
            try (var paths = Files.list(directory)) {
                var existing = paths.filter(path -> path.getFileName().toString().startsWith(messageId + ".")
                        && path.getFileName().toString().endsWith(".json")).toList();
                if (!existing.isEmpty()) {
                    if (existing.size() != 1 || !existing.getFirst().equals(target)
                            || !Arrays.equals(Files.readAllBytes(existing.getFirst()), content))
                        throw failure("统计消息已绑定到不同的角色修订");
                    return new Descriptor(target, digest, role.revisionId(), role.contentSha256());
                }
            }
            Path temporary = Files.createTempFile(directory, ".accounting-dispatch-", ".tmp");
            try {
                restrict(temporary, false);
                Files.write(temporary, content);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            try (var paths = Files.list(directory)) {
                if (paths.filter(path -> path.getFileName().toString().startsWith(messageId + ".")
                        && path.getFileName().toString().endsWith(".json")).count() != 1)
                    throw failure("统计消息的角色描述文件存在冲突");
            }
            return new Descriptor(target, digest, role.revisionId(), role.contentSha256());
        } catch (SessionFailure failure) { throw failure; }
        catch (IOException failure) { throw failure("统计角色描述文件无法安全写入"); }
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, directory
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static SessionFailure failure(String message) {
        return new SessionFailure("ROLE_ACCOUNTING_DISPATCH_INVALID", message);
    }
}
