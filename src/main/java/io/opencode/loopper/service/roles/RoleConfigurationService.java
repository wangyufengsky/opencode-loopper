package io.opencode.loopper.service.roles;

import io.opencode.loopper.persistence.RoleConfigurationMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import io.opencode.loopper.runtime.KnowledgeSessionPolicy;
import io.opencode.loopper.service.assist.AssistToolCatalog;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Runtime-facing role revision boundary. A role id never substitutes for a SessionProfile. */
@Service
public class RoleConfigurationService {
    public static final String ROLE_ADAPTER_V1 = "ROLE_ADAPTER_V1";
    public static final Set<String> NATIVE_TOOLS = Set.of("read", "glob", "grep", "question", "todowrite",
            "todoread", "bash", "edit", "write", "patch", "apply_patch", "webfetch", "task", "skill");
    private final RoleConfigurationMapper mapper;
    private final ObjectMapper json;
    private final RolePublishingService publishing;

    @Autowired
    public RoleConfigurationService(RoleConfigurationMapper mapper, ObjectMapper json,
                                    RolePublishingService publishing) {
        this.mapper = mapper;
        this.json = json;
        this.publishing = publishing;
    }

    /** Only for pure policy tests; Spring uses the bootstrap-aware constructor. */
    RoleConfigurationService(RoleConfigurationMapper mapper, ObjectMapper json) {
        this(mapper, json, null);
    }

    public record OwnerRef(String type, String id) {
        public OwnerRef {
            if (type == null || !type.matches("[A-Z][A-Z0-9_]{0,63}")
                    || id == null || id.isBlank() || id.length() > 256) throw invalid("Invalid role owner");
        }
    }
    public record RoleContext(OwnerRef owner, String slot) {
        public RoleContext {
            Objects.requireNonNull(owner);
            if (slot == null || !slot.matches("[A-Z][A-Z0-9_]{0,95}")) throw invalid("Invalid role slot");
        }
    }
    public record ResolvedRole(String roleId, String revisionId, String contentSha256, String slot,
                               String adapterProfile, Map<String, String> fragments,
                               String permissionMode, List<String> nativeTools, List<String> mcpTools,
                               List<String> requiredMcpTools, String modelPolicy, String runtimePolicy) {
        public ResolvedRole {
            fragments = Map.copyOf(fragments);
            nativeTools = List.copyOf(nativeTools);
            mcpTools = List.copyOf(mcpTools);
            requiredMcpTools = List.copyOf(requiredMcpTools);
        }
        public ResolvedRole(String roleId, String revisionId, String contentSha256, String slot,
                            String adapterProfile, Map<String, String> fragments, String permissionMode,
                            List<String> nativeTools, List<String> mcpTools, List<String> requiredMcpTools,
                            String modelPolicy) {
            this(roleId, revisionId, contentSha256, slot, adapterProfile, fragments, permissionMode,
                    nativeTools, mcpTools, requiredMcpTools, modelPolicy,
                    "ACCOUNTING_COMMAND".equals(adapterProfile) ? "ACCOUNTING_COMMAND" : "WORKFLOW_ADAPTER");
        }
    }
    public record SessionSnapshot(String sessionKey, RoleContext context, String revisionId,
                                  String revisionSha256, String adapterProfile, String adapterVersion,
                                  List<OpenCodeClient.SessionPermissionRule> permissionPolicy,
                                  String permissionPolicySha256, String safePromptSha256) {
        public SessionSnapshot { permissionPolicy = List.copyOf(permissionPolicy); }
        public SessionSnapshot(String sessionKey, RoleContext context, String revisionId,
                               String revisionSha256, String adapterProfile,
                               List<OpenCodeClient.SessionPermissionRule> permissionPolicy,
                               String permissionPolicySha256, String safePromptSha256) {
            this(sessionKey, context, revisionId, revisionSha256, adapterProfile, ROLE_ADAPTER_V1,
                    permissionPolicy, permissionPolicySha256, safePromptSha256);
        }
    }
    public record PromptIdentity(String sessionKey, String messageKey, String businessSha256,
                                 String effectiveSha256) { }

    /** Called in the same short transaction as owner creation. An existing full snapshot is idempotent. */
    @Transactional
    public void freezeOwner(OwnerRef owner, OwnerRef optionalParent) {
        Objects.requireNonNull(owner);
        if (publishing != null) publishing.ensureBootstrapForOwner();
        var frozen = mapper.ownerSnapshot(owner.type(), owner.id());
        if (frozen != null) {
            if (!Objects.equals(frozen.parentType(), optionalParent == null ? null : optionalParent.type())
                    || !Objects.equals(frozen.parentId(), optionalParent == null ? null : optionalParent.id()))
                throw new ConflictException("ROLE_OWNER_BINDING_CONFLICT", "Owner 的父级角色绑定已不同");
            requireComplete(owner, frozen);
            return;
        }
        List<RoleConfigurationMapper.OwnerBinding> source;
        if (optionalParent == null) {
            source = mapper.bindings().stream().filter(b -> b.revisionId() != null)
                    .map(b -> new RoleConfigurationMapper.OwnerBinding(owner.type(), owner.id(), b.slot(),
                            b.revisionId(), null, null, Instant.now().toString())).toList();
        } else {
            if (optionalParent.equals(owner)) throw invalid("Role owner cannot inherit itself");
            var parentSnapshot = mapper.ownerSnapshot(optionalParent.type(), optionalParent.id());
            if (parentSnapshot == null) {
                // A parent created before this feature remains on its historical contract.
                markLegacy(owner, optionalParent);
                return;
            }
            List<RoleConfigurationMapper.OwnerBinding> parent = mapper.ownerBindings(optionalParent.type(), optionalParent.id());
            requireComplete(parentSnapshot, parent);
            source = parent.stream().map(b -> new RoleConfigurationMapper.OwnerBinding(owner.type(), owner.id(),
                    b.slot(), b.revisionId(), optionalParent.type(), optionalParent.id(), Instant.now().toString())).toList();
        }
        if (source.isEmpty() && optionalParent == null) throw new ConflictException("ROLE_BINDINGS_NOT_READY", "当前没有已发布的角色槽位绑定");
        List<RoleConfigurationMapper.OwnerBinding> existing = mapper.ownerBindings(owner.type(), owner.id());
        if (!existing.isEmpty()) throw new ConflictException("ROLE_OWNER_BINDING_PARTIAL", "Owner 角色绑定缺少完整快照");
        for (var row : source) if (mapper.insertOwnerBinding(row) != 1)
            throw new ConflictException("ROLE_OWNER_BINDING_CONFLICT", "Owner 角色绑定冻结失败");
        String digest = bindingsDigest(source);
        if (mapper.insertOwnerSnapshot(new RoleConfigurationMapper.OwnerSnapshot(owner.type(), owner.id(),
                optionalParent == null ? null : optionalParent.type(), optionalParent == null ? null : optionalParent.id(),
                source.size(), digest, Instant.now().toString())) != 1)
            throw new ConflictException("ROLE_OWNER_BINDING_CONFLICT", "Owner 角色快照冻结失败");
    }

    private void markLegacy(OwnerRef owner, OwnerRef parent) {
        if (!mapper.ownerBindings(owner.type(), owner.id()).isEmpty())
            throw new ConflictException("ROLE_OWNER_BINDING_PARTIAL", "历史 Owner 标记与角色绑定冲突");
        if (mapper.insertOwnerSnapshot(new RoleConfigurationMapper.OwnerSnapshot(owner.type(), owner.id(),
                parent.type(), parent.id(), 0, bindingsDigest(List.of()), Instant.now().toString())) != 1)
            throw new ConflictException("ROLE_OWNER_BINDING_CONFLICT", "历史角色继承冻结失败");
    }

    /** Marker presence distinguishes a frozen draft from pre-configuration legacy data. */
    public boolean hasOwnerSnapshot(OwnerRef owner) {
        Objects.requireNonNull(owner);
        return mapper.ownerSnapshot(owner.type(), owner.id()) != null;
    }

    public Optional<ResolvedRole> resolveFrozen(OwnerRef owner, String slot) {
        Objects.requireNonNull(owner);
        if (slot == null || slot.isBlank()) return Optional.empty();
        var snapshot = mapper.ownerSnapshot(owner.type(), owner.id());
        if (snapshot == null) return Optional.empty();
        var rows = mapper.ownerBindings(owner.type(), owner.id());
        requireComplete(snapshot, rows);
        if (snapshot.bindingCount() == 0) return Optional.empty();
        var selected = rows.stream().filter(b -> slot.equals(b.slot())).findFirst();
        if (selected.isEmpty()) throw new ConflictException("ROLE_SLOT_NOT_FROZEN",
                "Owner 已冻结角色配置，但缺少请求的工作流槽位：" + slot);
        return selected.map(b -> resolveRevision(b.revisionId(), slot));
    }

    private void requireComplete(OwnerRef owner, RoleConfigurationMapper.OwnerSnapshot snapshot) {
        requireComplete(snapshot, mapper.ownerBindings(owner.type(), owner.id()));
    }

    private void requireComplete(RoleConfigurationMapper.OwnerSnapshot snapshot,
                                 List<RoleConfigurationMapper.OwnerBinding> rows) {
        if (rows.size() != snapshot.bindingCount() || !bindingsDigest(rows).equals(snapshot.bindingsSha256()))
            throw new ConflictException("ROLE_OWNER_BINDING_CORRUPT", "Owner 冻结角色绑定不完整");
    }

    private static String bindingsDigest(List<RoleConfigurationMapper.OwnerBinding> rows) {
        return sha256(rows.stream().sorted(java.util.Comparator.comparing(RoleConfigurationMapper.OwnerBinding::slot))
                .map(row -> row.slot() + "\n" + row.revisionId()).reduce("", (left, right) -> left + right + "\n"));
    }

    public Optional<ResolvedRole> resolveActive(String slot) {
        var binding = mapper.binding(slot);
        return binding == null || binding.revisionId() == null ? Optional.empty()
                : Optional.of(resolveRevision(binding.revisionId(), slot));
    }

    public ResolvedRole resolveRevision(String revisionId, String slot) {
        RoleConfigurationMapper.Revision row = mapper.revision(revisionId);
        RoleConfigurationMapper.Binding binding = mapper.binding(slot);
        if (row == null || binding == null) throw new ConflictException("ROLE_REVISION_MISSING", "冻结角色修订不存在");
        RoleManifest.Role role = json.readValue(row.manifestJson(), RoleManifest.Role.class);
        if (!role.allowedSlots().contains(slot)) throw new ConflictException("ROLE_SLOT_MISMATCH", "角色修订不支持已冻结的槽位");
        Map<String, String> fragments = json.readValue(row.promptFragmentsJson(), new TypeReference<>() { });
        return new ResolvedRole(row.roleId(), row.revisionId(), row.contentSha256(), slot,
                binding.adapterProfile(), fragments, role.permissionMode(), role.nativeTools(), role.mcpTools(),
                role.requiredMcpTools(), role.modelPolicy(), role.runtimePolicy());
    }

    /** Required declarations are checked only against tools discovered for this exact new Session. */
    public void requireMcpToolsAvailable(ResolvedRole role, Set<String> discoveredExactTools, String internalMcpServer) {
        Set<String> exact = discoveredExactTools == null ? Set.of() : Set.copyOf(discoveredExactTools);
        for (String stable : role.requiredMcpTools()) {
            String actual = actualTool(stable, internalMcpServer);
            if (!exact.contains(actual)) throw new ConflictException("ROLE_REQUIRED_MCP_UNAVAILABLE",
                    "当前会话缺少角色要求的 MCP 工具：" + stable);
        }
    }

    /** Preserve baseline order and every denial. Config may remove grants, never add authority. */
    public List<OpenCodeClient.SessionPermissionRule> compileNarrowedPermissions(
            ResolvedRole role, List<OpenCodeClient.SessionPermissionRule> orderedBaseline,
            Set<String> discoveredExactTools, String internalMcpServer) {
        Objects.requireNonNull(role);
        Objects.requireNonNull(orderedBaseline);
        if ("ACCOUNTING_COMMAND".equals(role.adapterProfile()))
            throw invalid("统计命令使用独立 native Agent，不能编译 SessionProfile 权限");
        Set<String> exact = discoveredExactTools == null ? Set.of() : Set.copyOf(discoveredExactTools);
        requireMcpToolsAvailable(role, exact, internalMcpServer);
        for (String stable : role.requiredMcpTools()) {
            if (!baselineAllows(orderedBaseline, actualTool(stable, internalMcpServer)))
                throw new ConflictException("ROLE_REQUIRED_MCP_NOT_AUTHORIZED",
                        "角色要求的 MCP 工具超出当前适配器授权：" + stable);
        }
        if ("BASELINE".equals(role.permissionMode())) return List.copyOf(orderedBaseline);
        if (!"INTERSECT".equals(role.permissionMode())) throw invalid("Unknown permission mode");
        String internal = internalMcpServer == null ? "" : internalMcpServer.replaceAll("[^a-zA-Z0-9_-]", "_") + "_";
        String required = InternalMcpContractCatalog.toolName(OpenCodeClient.SessionProfile.valueOf(role.adapterProfile()))
                .map(tool -> internal + tool).orElse(null);
        Set<String> configuredMcp = role.mcpTools().stream()
                .map(name -> actualTool(name, internalMcpServer)).collect(java.util.stream.Collectors.toSet());
        if (!NATIVE_TOOLS.containsAll(role.nativeTools())) throw invalid("角色包含不受支持的原生工具");
        if ("IMPLEMENTATION".equals(role.adapterProfile())) {
            List<OpenCodeClient.SessionPermissionRule> result = new ArrayList<>();
            result.add(new OpenCodeClient.SessionPermissionRule("*", "*", "deny"));
            for (String name : role.nativeTools()) {
                if (!name.matches("[a-z][a-z0-9_]{0,63}") || Set.of("external_directory", "permission").contains(name))
                    throw invalid("Unsupported native tool permission");
                result.add(new OpenCodeClient.SessionPermissionRule(name, "*", "allow"));
            }
            // Native path, Git, service and deletion denials must follow native grants.
            orderedBaseline.stream().filter(rule -> !"allow".equals(rule.action())).forEach(result::add);
            // A prior broad server deny can be overridden only by an exact tool already
            // allowed by the complete ordered adapter baseline.
            for (String name : configuredMcp) if (exact.contains(name) && baselineAllows(orderedBaseline, name))
                result.add(new OpenCodeClient.SessionPermissionRule(name, "*", "allow"));
            requireCompiledToolsAvailable(role, result, internalMcpServer);
            return List.copyOf(result);
        }
        List<OpenCodeClient.SessionPermissionRule> result = new ArrayList<>();
        for (var rule : orderedBaseline) {
            if (!"allow".equals(rule.action())) { result.add(rule); continue; }
            String name = rule.permission();
            if (mandatory(name, internal, required)
                    || role.adapterProfile().startsWith("KNOWLEDGE_RESEARCH_")
                    && name.equals(KnowledgeSessionPolicy.MARKER)) { result.add(rule); continue; }
            if (name.endsWith("_*")) continue;
            if (NATIVE_TOOLS.contains(name)) {
                if (role.nativeTools().contains(name)) result.add(rule);
            } else if (exact.contains(name) && configuredMcp.contains(name)) result.add(rule);
        }
        for (String stable : role.mcpTools()) {
            String name = actualTool(stable, internalMcpServer);
            if (!exact.contains(name) || stable.startsWith("@loopper-internal/")) continue;
            boolean granted = result.stream().anyMatch(rule -> "allow".equals(rule.action()) && name.equals(rule.permission()));
            if (!granted && baselineAllows(orderedBaseline, name)) {
                result.add(new OpenCodeClient.SessionPermissionRule(name, "*", "allow"));
            }
        }
        requireCompiledToolsAvailable(role, result, internalMcpServer);
        return List.copyOf(result);
    }

    private static void requireCompiledToolsAvailable(ResolvedRole role,
            List<OpenCodeClient.SessionPermissionRule> compiled, String internalMcpServer) {
        for (String stable : role.requiredMcpTools())
            if (!baselineAllows(compiled, actualTool(stable, internalMcpServer)))
                throw new ConflictException("ROLE_REQUIRED_MCP_NOT_AUTHORIZED",
                        "角色要求的 MCP 工具未获最终权限：" + stable);
    }

    private static boolean mandatory(String name, String internal, String required) {
        return name.equals(required) || !internal.isEmpty()
                && name.equals(internal + InternalMcpContractCatalog.DESCRIBE_TOOL) && required != null;
    }

    private static boolean baselineAllows(List<OpenCodeClient.SessionPermissionRule> baseline, String name) {
        boolean allowed = false;
        for (var rule : baseline) {
            String permission = rule.permission();
            if (permission.equals("*") || permission.equals(name)
                    || permission.endsWith("_*") && name.startsWith(permission.substring(0, permission.length() - 1)))
                allowed = "allow".equals(rule.action());
        }
        return allowed;
    }

    private static String actualTool(String configured, String internalMcpServer) {
        if (configured.startsWith("@loopper-internal/")) {
            if (internalMcpServer == null) throw invalid("Internal MCP generation is unavailable");
            return internalMcpServer.replaceAll("[^a-zA-Z0-9_-]", "_") + "_" + configured.substring(18);
        }
        if (configured.startsWith("@loopper-assist/")) {
            if (internalMcpServer == null) throw invalid("Assist MCP generation is unavailable");
            return AssistToolCatalog.serverName(internalMcpServer).replaceAll("[^a-zA-Z0-9_-]", "_")
                    + "_" + configured.substring(16);
        }
        if (!configured.matches("[a-zA-Z0-9_-]+_[a-zA-Z0-9_-]+")) throw invalid("MCP tool must be exact");
        return configured;
    }

    /** Static fragments are already frozen verbatim; dynamic business facts stay in server prompts. */
    public String renderPromptFragment(ResolvedRole role, String fragmentKey, Map<String, String> variables) {
        Objects.requireNonNull(role);
        String source = role.fragments().get(fragmentKey);
        if (source == null) throw new ConflictException("ROLE_PROMPT_FRAGMENT_MISSING", "角色提示词片段不存在");
        if (variables != null && !variables.isEmpty())
            throw invalid("静态角色提示词不接受运行时变量；动态事实由服务端装配");
        if (source.length() > 256_000) throw invalid("角色提示词过大");
        return source;
    }

    @Transactional
    public SessionSnapshot freezeSession(String sessionKey, RoleContext context,
            OpenCodeClient.SessionProfile profile, List<OpenCodeClient.SessionPermissionRule> permissions,
            String safePromptSha256) {
        if (sessionKey == null || sessionKey.isBlank() || context == null || profile == null || permissions == null)
            throw invalid("Incomplete role session snapshot");
        ResolvedRole role = resolveFrozen(context.owner(), context.slot())
                .orElseThrow(() -> new ConflictException("ROLE_OWNER_NOT_FROZEN", "Owner 角色修订尚未冻结"));
        if (!profile.name().equals(role.adapterProfile()))
            throw new ConflictException("ROLE_PROFILE_MISMATCH", "角色槽位适配器与 Session Profile 不一致");
        String encoded = json.writeValueAsString(permissions);
        String digest = sha256(encoded);
        if (safePromptSha256 != null && !safePromptSha256.matches("[0-9a-f]{64}")) throw invalid("Invalid safe prompt digest");
        var row = new RoleConfigurationMapper.SessionSnapshot(sessionKey, context.owner().type(), context.owner().id(),
                context.slot(), role.revisionId(), role.contentSha256(), profile.name(), ROLE_ADAPTER_V1,
                encoded, digest,
                safePromptSha256, Instant.now().toString());
        var old = mapper.sessionSnapshot(sessionKey);
        if (old != null) return requireSame(old, row);
        if (mapper.insertSessionSnapshot(row) != 1) throw new ConflictException("ROLE_SESSION_SNAPSHOT_CONFLICT", "角色会话快照保存失败");
        return toSnapshot(row);
    }

    public Optional<SessionSnapshot> sessionSnapshot(String sessionKey) {
        return Optional.ofNullable(mapper.sessionSnapshot(sessionKey)).map(this::toSnapshot);
    }

    @Transactional
    public PromptIdentity recordPromptIdentity(String sessionKey, String messageKey,
                                               String businessSha256, String effectiveSha256) {
        if (sessionKey == null || sessionKey.isBlank() || messageKey == null || messageKey.isBlank()
                || businessSha256 == null || !businessSha256.matches("[0-9a-f]{64}")
                || effectiveSha256 == null || !effectiveSha256.matches("[0-9a-f]{64}"))
            throw invalid("Incomplete role prompt identity");
        if (mapper.sessionSnapshot(sessionKey) == null)
            throw new ConflictException("ROLE_SESSION_SNAPSHOT_MISSING", "发送提示词前必须先冻结角色会话快照");
        var old = mapper.promptDispatch(sessionKey, messageKey);
        if (old != null) {
            if (!old.businessSha256().equals(businessSha256) || !old.effectiveSha256().equals(effectiveSha256))
                throw new ConflictException("ROLE_PROMPT_IDENTITY_CONFLICT", "同一消息的角色提示词身份已不同");
            return new PromptIdentity(sessionKey, messageKey, businessSha256, effectiveSha256);
        }
        if (mapper.insertPromptDispatch(new RoleConfigurationMapper.PromptDispatch(sessionKey, messageKey,
                businessSha256, effectiveSha256, Instant.now().toString())) != 1)
            throw new ConflictException("ROLE_PROMPT_IDENTITY_CONFLICT", "角色提示词身份保存失败");
        return new PromptIdentity(sessionKey, messageKey, businessSha256, effectiveSha256);
    }

    public Optional<PromptIdentity> promptIdentity(String sessionKey, String messageKey) {
        var row = mapper.promptDispatch(sessionKey, messageKey);
        return row == null ? Optional.empty() : Optional.of(new PromptIdentity(row.sessionKey(), row.messageKey(),
                row.businessSha256(), row.effectiveSha256()));
    }

    @Transactional
    public SessionSnapshot copySessionSnapshot(String fromKey, String toKey) {
        var source = mapper.sessionSnapshot(fromKey);
        if (source == null || toKey == null || toKey.isBlank())
            throw new ConflictException("ROLE_SESSION_SNAPSHOT_MISSING", "预备角色会话快照不存在");
        var copy = new RoleConfigurationMapper.SessionSnapshot(toKey, source.ownerType(), source.ownerId(),
                source.slot(), source.revisionId(), source.revisionSha256(), source.adapterProfile(),
                source.adapterVersion(),
                source.permissionPolicyJson(), source.permissionPolicySha256(), source.safePromptSha256(),
                source.frozenAt());
        var old = mapper.sessionSnapshot(toKey);
        if (old != null) return requireSame(old, copy);
        if (mapper.insertSessionSnapshot(copy) != 1) throw new ConflictException("ROLE_SESSION_SNAPSHOT_CONFLICT", "角色会话绑定保存失败");
        return toSnapshot(copy);
    }

    public String slotFor(OpenCodeClient.SessionProfile profile, String explicitPurpose) {
        Objects.requireNonNull(profile);
        String purpose = explicitPurpose == null ? "" : explicitPurpose.trim().toUpperCase();
        if (profile == OpenCodeClient.SessionProfile.JUDGE_CANDIDATE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.JUDGE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.JUDGE_FINALIZER_NO_TOOLS) {
            if (!Set.of("REQUIREMENT", "RISK").contains(purpose)) throw invalid("Judge role purpose is required");
            return "JUDGE_" + purpose + (profile == OpenCodeClient.SessionProfile.JUDGE_READ_ONLY ? "_LEGACY"
                    : profile == OpenCodeClient.SessionProfile.JUDGE_FINALIZER_NO_TOOLS ? "_FINALIZER" : "_CANDIDATE");
        }
        if (!purpose.isEmpty()) {
            String special = profile.name() + "_" + purpose;
            if (mapper.binding(special) == null) throw invalid("Unsupported role purpose for profile");
            return special;
        }
        return profile.name();
    }

    private SessionSnapshot requireSame(RoleConfigurationMapper.SessionSnapshot old,
                                        RoleConfigurationMapper.SessionSnapshot expected) {
        if (!old.ownerType().equals(expected.ownerType()) || !old.ownerId().equals(expected.ownerId())
                || !old.slot().equals(expected.slot()) || !old.revisionId().equals(expected.revisionId())
                || !old.revisionSha256().equals(expected.revisionSha256())
                || !old.adapterProfile().equals(expected.adapterProfile())
                || !old.adapterVersion().equals(expected.adapterVersion())
                || !old.permissionPolicyJson().equals(expected.permissionPolicyJson())
                || !old.permissionPolicySha256().equals(expected.permissionPolicySha256())
                || !Objects.equals(old.safePromptSha256(), expected.safePromptSha256()))
            throw new ConflictException("ROLE_SESSION_SNAPSHOT_CONFLICT", "同一会话的角色快照已不同");
        return toSnapshot(old);
    }

    private SessionSnapshot toSnapshot(RoleConfigurationMapper.SessionSnapshot row) {
        List<OpenCodeClient.SessionPermissionRule> rules = json.readValue(row.permissionPolicyJson(), new TypeReference<>() { });
        if (!sha256(row.permissionPolicyJson()).equals(row.permissionPolicySha256()))
            throw new ConflictException("ROLE_SESSION_SNAPSHOT_CORRUPT", "角色会话权限快照摘要不一致");
        if (!ROLE_ADAPTER_V1.equals(row.adapterVersion()))
            throw new ConflictException("ROLE_ADAPTER_VERSION_UNSUPPORTED", "角色会话适配器版本不受支持");
        return new SessionSnapshot(row.sessionKey(), new RoleContext(new OwnerRef(row.ownerType(), row.ownerId()), row.slot()),
                row.revisionId(), row.revisionSha256(), row.adapterProfile(), row.adapterVersion(), rules,
                row.permissionPolicySha256(), row.safePromptSha256());
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    static BadRequestException invalid(String message) {
        return new BadRequestException("ROLE_CONFIGURATION_INVALID", message);
    }
}
