package io.opencode.loopper.service.roles;

import io.opencode.loopper.persistence.RoleConfigurationMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.DocumentDevelopmentProfiles;
import io.opencode.loopper.runtime.OpenCodePermissionPolicy;
import io.opencode.loopper.runtime.SourceDevelopmentProfiles;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.assist.AssistToolCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Validates a bounded import before the short atomic publish/activation transaction. */
@Service
public class RolePublishingService {
    private static final Pattern TEMPLATE_DIRECTIVE = Pattern.compile(
            "\\{\\{[^{}\\n]{1,160}}|\\$\\{[^}\\n]{1,160}}|\\{%\\s*include\\b[^%\\n]*%}|(?m)^\\s*(?:!include|@include|#include)\\s+\\S+",
            Pattern.CASE_INSENSITIVE);
    private final RoleConfigurationMapper mapper;
    private final RoleArchive archive;
    private final ObjectMapper json;

    public RolePublishingService(RoleConfigurationMapper mapper, RoleArchive archive, ObjectMapper json) {
        this.mapper = mapper;
        this.archive = archive;
        this.json = json;
    }

    public record Diagnostic(String code, String path, String message) { }
    public record Change(String path, Object before, Object after) { }
    public record RoleChange(String roleId, String displayName, Integer revisionNumber,
                             String change, String contentSha256) { }
    public record Activation(String slot, String roleId, long expectedVersion,
                             String currentRoleId, String currentRevisionId) { }
    public record Validation(String sourceSha256, boolean valid, List<RoleChange> roles,
                             List<Activation> activations, List<Diagnostic> diagnostics,
                             List<Change> changes) { }
    public record PublishRequest(String sourceSha256, String idempotencyKey,
                                 List<Activation> activations) { }
    public record PublishedRole(String roleId, String revisionId, int revisionNumber,
                                String contentSha256) { }
    public record BindingView(String slot, String profile, String activeRoleId,
                              String activeRevisionId, long bindingVersion,
                              String label, String purpose) { }
    public record Publication(String sourceSha256, List<PublishedRole> roles,
                              List<BindingView> bindings, boolean replayed) { }

    public Validation validate(RoleArchive.Parsed parsed) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        List<RoleChange> roles = new ArrayList<>();
        List<Activation> activations = new ArrayList<>();
        Set<String> activeSlots = new HashSet<>();
        Map<String, String> targets = new LinkedHashMap<>();
        if (parsed.manifest().bindings() == null) {
            for (RoleManifest.Role item : parsed.manifest().roles())
                for (String slot : item.allowedSlots()) {
                    String old = targets.putIfAbsent(slot, item.roleId());
                    if (old != null) diagnostics.add(new Diagnostic("ROLE_SLOT_DUPLICATE", "/bindings/" + slot,
                            "同一导入包中每个槽位只能激活一次"));
                }
        } else {
            for (RoleManifest.Binding target : parsed.manifest().bindings()) {
                var selected = parsed.manifest().roles().stream()
                        .filter(item -> item.roleId().equals(target.roleId())).findFirst().orElse(null);
                if (selected == null || !selected.allowedSlots().contains(target.slot()))
                    diagnostics.add(new Diagnostic("ROLE_BINDING_NOT_ALLOWED", "/bindings/" + target.slot(),
                            "激活目标必须引用本次导入的角色及其受支持槽位"));
                else targets.put(target.slot(), target.roleId());
            }
        }
        for (RoleManifest.Role role : parsed.manifest().roles()) {
            String path = "/roles/" + role.roleId();
            if (role.allowedSlots().isEmpty()) diagnostics.add(new Diagnostic("ROLE_SLOTS_REQUIRED", path,
                    "角色必须指定至少一个受支持的工作流槽位"));
            if ("BASELINE".equals(role.permissionMode())
                    && (!role.nativeTools().isEmpty() || !role.mcpTools().isEmpty()))
                diagnostics.add(new Diagnostic("ROLE_PERMISSION_MODE_CONFLICT", path + "/permissionMode",
                        "基础权限模式不能指定工具；如需收窄权限请使用 INTERSECT"));
            if (!RoleConfigurationService.NATIVE_TOOLS.containsAll(role.nativeTools()))
                diagnostics.add(new Diagnostic("ROLE_NATIVE_TOOL_UNKNOWN", path + "/nativeTools",
                        "角色原生工具不在当前适配器支持的工具表内"));
            if (!role.mcpTools().containsAll(role.requiredMcpTools()))
                diagnostics.add(new Diagnostic("ROLE_REQUIRED_MCP_NOT_DECLARED", path + "/requiredMcpTools",
                        "必需 MCP 工具必须同时列入 mcpTools"));
            if (!"INHERIT_WORKFLOW".equals(role.modelPolicy()))
                diagnostics.add(new Diagnostic("ROLE_MODEL_POLICY_UNSUPPORTED", path + "/modelPolicy",
                        "当前仅支持继承工作流模型策略"));
            boolean accounting = role.allowedSlots().contains("ACCOUNTING_COMMAND");
            if (!role.runtimePolicy().equals(accounting ? "ACCOUNTING_COMMAND" : "WORKFLOW_ADAPTER"))
                diagnostics.add(new Diagnostic("ROLE_RUNTIME_POLICY_MISMATCH", path + "/runtimePolicy",
                        "角色运行策略必须与所选工作流槽位一致"));
            if (accounting
                    && (role.allowedSlots().size() != 1 || !"BASELINE".equals(role.permissionMode())
                    || !role.nativeTools().isEmpty() || !role.mcpTools().isEmpty()
                    || !role.requiredMcpTools().isEmpty()
                    || !role.prompts().keySet().equals(Set.of("accounting.instructions"))))
                diagnostics.add(new Diagnostic("ROLE_ACCOUNTING_COMMAND_FIXED", path,
                        "统计命令只能配置 accounting.instructions 提示词，不能配置工具或混用 Session 槽位"));
            for (String slot : role.allowedSlots()) {
                var current = mapper.binding(slot);
                if (current == null) {
                    diagnostics.add(new Diagnostic("ROLE_SLOT_UNKNOWN", path + "/allowedSlots", "工作流槽位不受支持"));
                    continue;
                }
                for (String tool : role.mcpTools()) validateTool(tool, current.adapterProfile(), diagnostics, path);
                if (!role.roleId().equals(targets.get(slot))) continue;
                if (!activeSlots.add(slot)) diagnostics.add(new Diagnostic("ROLE_SLOT_DUPLICATE", path + "/allowedSlots",
                        "同一导入包中每个槽位只能激活一次"));
                var currentRevision = current.revisionId() == null ? null : mapper.revision(current.revisionId());
                activations.add(new Activation(slot, role.roleId(), current.version(),
                        currentRevision == null ? null : currentRevision.roleId(), current.revisionId()));
                if ("INTERSECT".equals(role.permissionMode()) && "IMPLEMENTATION".equals(current.adapterProfile())
                        && role.nativeTools().stream().anyMatch(name -> Set.of("external_directory", "permission").contains(name)))
                    diagnostics.add(new Diagnostic("ROLE_NATIVE_TOOL_UNSAFE", path + "/nativeTools",
                            "开发执行角色不能授予受保护的原生工具"));
            }
            Map<String, String> overrides = parsed.fragmentsByRole().getOrDefault(role.roleId(), Map.of());
            Map<String, String> fragments = effectiveFragments(role, overrides, diagnostics, path);
            if (accounting && (fragments.get("accounting.instructions") == null
                    || fragments.get("accounting.instructions").isBlank()))
                diagnostics.add(new Diagnostic("ROLE_ACCOUNTING_PROMPT_REQUIRED",
                        path + "/prompts/accounting.instructions", "统计命令提示词不能为空"));
            for (var entry : overrides.entrySet()) {
                if (!RolePromptResources.catalog().containsKey(entry.getKey()))
                    diagnostics.add(new Diagnostic("ROLE_PROMPT_SLOT_UNKNOWN", path + "/prompts/" + entry.getKey(),
                            "当前适配器不支持该提示词片段"));
                else if (introducesTemplateDirective(RolePromptResources.catalog().get(entry.getKey()), entry.getValue()))
                    diagnostics.add(new Diagnostic("ROLE_PROMPT_TEMPLATE_UNSUPPORTED",
                            path + "/prompts/" + entry.getKey(),
                            "静态提示词不能新增变量、脚本表达式或 include 指令；动态事实由服务端装配"));
            }
            RoleManifest.Role normalized = canonicalRole(role, fragments);
            String sha = contentSha(normalized, fragments);
            var existing = mapper.latest(role.roleId());
            if (existing == null) {
                changes.add(new Change(path, null, normalized));
                for (var fragment : new TreeMap<>(fragments).entrySet())
                    changes.add(new Change(path + "/prompts/" + fragment.getKey(), null, fragment.getValue()));
            } else if (!existing.contentSha256().equals(sha)) {
                RoleManifest.Role prior = json.readValue(existing.manifestJson(), RoleManifest.Role.class);
                fieldDiff(path, prior, normalized, changes);
                Map<String, String> oldFragments = json.readValue(existing.promptFragmentsJson(), new TypeReference<>() { });
                Set<String> keys = new java.util.TreeSet<>(oldFragments.keySet()); keys.addAll(fragments.keySet());
                for (String key : keys) if (!Objects.equals(oldFragments.get(key), fragments.get(key)))
                    changes.add(new Change(path + "/prompts/" + key, oldFragments.get(key), fragments.get(key)));
            }
            roles.add(new RoleChange(role.roleId(), role.displayName(), existing == null ? 1 : existing.revisionNumber() + 1,
                    existing == null ? "NEW" : existing.contentSha256().equals(sha) ? "UNCHANGED" : "UPDATED", sha));
        }
        for (Activation activation : activations) {
            RoleChange desired = roles.stream().filter(item -> item.roleId().equals(activation.roleId()))
                    .findFirst().orElseThrow();
            var published = mapper.byContent(activation.roleId(), desired.contentSha256());
            String target = published == null ? "NEW:" + desired.contentSha256() : published.revisionId();
            if (!Objects.equals(activation.currentRevisionId(), target))
                changes.add(new Change("/bindings/" + activation.slot() + "/revisionId",
                        activation.currentRevisionId(), target));
        }
        return new Validation(parsed.sourceSha256(), diagnostics.isEmpty(), List.copyOf(roles),
                List.copyOf(activations), List.copyOf(diagnostics), List.copyOf(changes));
    }

    /** Idempotent database import. The source is fully parsed before entering this transaction. */
    @Transactional
    public Publication publish(RoleArchive.Parsed parsed, PublishRequest request) {
        if (request == null || !parsed.sourceSha256().equals(request.sourceSha256())
                || request.idempotencyKey() == null || !request.idempotencyKey().matches("[A-Za-z0-9_-]{8,128}"))
            throw new BadRequestException("ROLE_IMPORT_IDENTITY_INVALID", "角色导入来源摘要或请求标识无效");
        String requestSha = requestSha(request);
        var receipt = mapper.receipt(request.idempotencyKey());
        if (receipt != null) {
            if (!receipt.sourceSha256().equals(parsed.sourceSha256()) || !receipt.requestSha256().equals(requestSha))
                throw new ConflictException("ROLE_IMPORT_KEY_REUSED", "同一幂等键已用于不同的角色导入请求");
            Publication saved = json.readValue(receipt.resultJson(), Publication.class);
            return new Publication(saved.sourceSha256(), saved.roles(), saved.bindings(), true);
        }
        Validation preview = validate(parsed);
        if (!preview.valid()) throw new BadRequestException("ROLE_IMPORT_INVALID", "角色导入校验未通过");
        List<Activation> requested = request.activations() == null ? List.of() : request.activations();
        if (!sameActivations(preview.activations(), requested))
            throw new ConflictException("ROLE_IMPORT_ACTIVATION_STALE", "角色槽位已变化，请重新校验导入包");
        String now = Instant.now().toString();
        List<PublishedRole> published = new ArrayList<>();
        for (RoleManifest.Role role : parsed.manifest().roles()) {
            var definition = mapper.definition(role.roleId());
            if (definition == null && mapper.insertDefinition(new RoleConfigurationMapper.Definition(role.roleId(),
                    role.displayName(), role.description(), role.groupKey(), role.groupLabel(), "IMPORTED", now)) != 1)
                throw new ConflictException("ROLE_DEFINITION_CONFLICT", "角色定义创建失败");
            if (definition != null && mapper.updateDefinition(new RoleConfigurationMapper.Definition(role.roleId(),
                    role.displayName(), role.description(), role.groupKey(), role.groupLabel(), "IMPORTED",
                    definition.createdAt())) != 1)
                throw new ConflictException("ROLE_DEFINITION_CONFLICT", "角色目录元数据更新失败");
            Map<String, String> fragments = effectiveFragments(role,
                    parsed.fragmentsByRole().getOrDefault(role.roleId(), Map.of()), new ArrayList<>(),
                    "/roles/" + role.roleId());
            RoleManifest.Role normalized = canonicalRole(role, fragments);
            String sha = contentSha(normalized, fragments);
            var existing = mapper.byContent(role.roleId(), sha);
            RoleConfigurationMapper.Revision row = existing;
            if (row == null) {
                var latest = mapper.latest(role.roleId());
                row = new RoleConfigurationMapper.Revision(UUID.randomUUID().toString(), role.roleId(),
                        latest == null ? 1 : latest.revisionNumber() + 1, json.writeValueAsString(normalized),
                        json.writeValueAsString(new TreeMap<>(fragments)), sha, parsed.sourceSha256(), "IMPORTED", now);
                if (mapper.insertRevision(row) != 1) throw new ConflictException("ROLE_REVISION_CONFLICT", "角色修订发布失败");
                audit("ROLE_REVISION_PUBLISHED", role.roleId(), row.revisionId(), null, Map.of("sourceSha256", parsed.sourceSha256()), now);
            }
            published.add(new PublishedRole(role.roleId(), row.revisionId(), row.revisionNumber(), row.contentSha256()));
        }
        for (Activation activation : requested) {
            String revisionId = published.stream().filter(r -> r.roleId().equals(activation.roleId()))
                    .findFirst().orElseThrow().revisionId();
            if (mapper.activate(activation.slot(), revisionId, activation.expectedVersion(), now) != 1)
                throw new ConflictException("ROLE_BINDING_CONFLICT", "工作流角色绑定已变化，请重新校验");
            audit("ROLE_BINDING_ACTIVATED", activation.roleId(), revisionId, activation.slot(),
                    Map.of("expectedVersion", activation.expectedVersion()), now);
        }
        Publication result = new Publication(parsed.sourceSha256(), List.copyOf(published), bindings(), false);
        if (mapper.insertReceipt(new RoleConfigurationMapper.Receipt(request.idempotencyKey(), parsed.sourceSha256(),
                requestSha, json.writeValueAsString(result), now)) != 1)
            throw new ConflictException("ROLE_IMPORT_RECEIPT_CONFLICT", "角色导入回执保存失败");
        return result;
    }

    /** Seed only missing built-in slots; imported active bindings are never overwritten at startup. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedBuiltin() {
        String source;
        try (InputStream stream = RolePublishingService.class.getClassLoader().getResourceAsStream("roles/builtin.yaml")) {
            if (stream == null) throw new IllegalStateException("Missing built-in role manifest");
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Cannot load built-in roles", failure); }
        RoleManifest.Document document = archive.parseYaml(source);
        String sourceSha = builtinSourceSha(source, document);
        var previous = mapper.bootstrap();
        List<RoleConfigurationMapper.Binding> currentBindings = mapper.bindings();
        if (previous == null) {
            if (!currentBindings.isEmpty() || !mapper.definitions("", "", 1).isEmpty())
                throw new ConflictException("ROLE_BOOTSTRAP_PARTIAL", "内置角色初始化记录缺失，现有角色数据需要检查");
        } else {
            requireBootstrapComplete(previous, currentBindings);
            if (previous.sourceSha256().equals(sourceSha)) return;
        }
        String now = Instant.now().toString();
        for (RoleManifest.Slot slot : document.slots()) {
            try {
                if (!"ACCOUNTING_COMMAND".equals(slot.adapterProfile()))
                    OpenCodeClient.SessionProfile.valueOf(slot.adapterProfile());
            }
            catch (RuntimeException invalid) { throw new IllegalStateException("Unknown built-in role adapter: " + slot.slot()); }
            var old = mapper.binding(slot.slot());
            if (old == null) mapper.insertBinding(new RoleConfigurationMapper.Binding(slot.slot(), slot.adapterProfile(),
                    slot.displayName(), slot.purpose(), null, 0, now));
            else if (!old.adapterProfile().equals(slot.adapterProfile()))
                throw new IllegalStateException("Built-in role slot adapter changed: " + slot.slot());
        }
        for (RoleManifest.Role role : document.roles()) {
            if (mapper.definition(role.roleId()) == null) mapper.insertDefinition(new RoleConfigurationMapper.Definition(
                    role.roleId(), role.displayName(), role.description(), role.groupKey(), role.groupLabel(), "BUILTIN", now));
            Map<String, String> fragments = RolePromptResources.defaultsForRole(role.roleId());
            RoleManifest.Role normalized = canonicalRole(role, fragments);
            String sha = contentSha(normalized, fragments);
            var revision = mapper.byContent(role.roleId(), sha);
            if (revision == null) {
                var latest = mapper.latest(role.roleId());
                revision = new RoleConfigurationMapper.Revision(UUID.randomUUID().toString(), role.roleId(),
                        latest == null ? 1 : latest.revisionNumber() + 1, json.writeValueAsString(normalized),
                        json.writeValueAsString(fragments), sha, sourceSha, "BUILTIN", now);
                if (mapper.insertRevision(revision) != 1) throw new IllegalStateException("Cannot seed role " + role.roleId());
            }
            for (String slot : role.allowedSlots()) {
                var binding = mapper.binding(slot);
                if (binding == null) throw new IllegalStateException("Missing built-in role slot: " + slot);
                if (binding.revisionId() == null && mapper.activate(slot, revision.revisionId(), binding.version(), now) != 1)
                    throw new IllegalStateException("Cannot activate built-in role slot: " + slot);
            }
        }
        List<RoleConfigurationMapper.Binding> updated = mapper.bindings();
        var marker = new RoleConfigurationMapper.Bootstrap("builtin", sourceSha,
                bindingsSha(updated), updated.size(), now);
        int changed = previous == null ? mapper.insertBootstrap(marker)
                : mapper.updateBootstrap(sourceSha, marker.bindingsSha256(), marker.bindingCount(), now,
                        previous.sourceSha256());
        if (changed != 1) throw new ConflictException("ROLE_BOOTSTRAP_CONFLICT", "内置角色初始化状态保存失败");
    }

    /** Owner creation may follow Flyway clean/migrate in tests; only this write path may bootstrap. */
    public void ensureBootstrapForOwner() {
        var marker = mapper.bootstrap();
        var bindings = mapper.bindings();
        if (marker == null) {
            if (!bindings.isEmpty() || !mapper.definitions("", "", 1).isEmpty())
                throw new ConflictException("ROLE_BOOTSTRAP_PARTIAL", "内置角色初始化不完整");
            seedBuiltin();
            return;
        }
        requireBootstrapComplete(marker, bindings);
    }

    private static void requireBootstrapComplete(RoleConfigurationMapper.Bootstrap marker,
                                                 List<RoleConfigurationMapper.Binding> bindings) {
        if (marker.bindingCount() != bindings.size() || !marker.bindingsSha256().equals(bindingsSha(bindings))
                || bindings.stream().anyMatch(binding -> binding.revisionId() == null))
            throw new ConflictException("ROLE_BOOTSTRAP_CORRUPT", "已初始化的角色槽位不完整，拒绝创建新 Owner");
    }

    private static String bindingsSha(List<RoleConfigurationMapper.Binding> bindings) {
        return RoleConfigurationService.sha256(bindings.stream()
                .sorted(java.util.Comparator.comparing(RoleConfigurationMapper.Binding::slot))
                .map(binding -> binding.slot() + "\n" + binding.adapterProfile() + "\n")
                .reduce("", String::concat));
    }

    private static String builtinSourceSha(String source, RoleManifest.Document document) {
        StringBuilder frozen = new StringBuilder(source).append('\n');
        document.roles().stream().map(RoleManifest.Role::roleId).sorted().forEach(roleId -> {
            frozen.append(roleId).append('\n');
            new TreeMap<>(RolePromptResources.defaultsForRole(roleId)).forEach((key, value) ->
                    frozen.append(key).append('\n').append(RoleConfigurationService.sha256(value)).append('\n'));
        });
        return RoleConfigurationService.sha256(frozen.toString());
    }

    public List<BindingView> bindings() {
        return mapper.bindingViews().stream().map(binding -> new BindingView(binding.slot(), binding.profile(),
                binding.activeRoleId(), binding.activeRevisionId(), binding.bindingVersion(), binding.label(),
                binding.purpose())).toList();
    }

    private Map<String, String> effectiveFragments(RoleManifest.Role role, Map<String, String> overrides,
                                                   List<Diagnostic> diagnostics, String path) {
        Map<String, String> inherited = new TreeMap<>();
        for (String slot : role.allowedSlots()) {
            var binding = mapper.binding(slot);
            if (binding == null || binding.revisionId() == null) continue;
            var revision = mapper.revision(binding.revisionId());
            if (revision == null) {
                diagnostics.add(new Diagnostic("ROLE_ACTIVE_REVISION_MISSING", path + "/allowedSlots",
                        "槽位当前修订不存在"));
                continue;
            }
            Map<String, String> fragments = json.readValue(revision.promptFragmentsJson(), new TypeReference<>() { });
            for (var entry : fragments.entrySet()) {
                String previous = inherited.putIfAbsent(entry.getKey(), entry.getValue());
                if (previous != null && !previous.equals(entry.getValue()) && !overrides.containsKey(entry.getKey()))
                    diagnostics.add(new Diagnostic("ROLE_PROMPT_INHERIT_CONFLICT", path + "/prompts/" + entry.getKey(),
                            "所选槽位的同名提示词不同，请在导入包中明确该片段"));
            }
        }
        for (var override : overrides.entrySet()) {
            if (!inherited.containsKey(override.getKey()))
                diagnostics.add(new Diagnostic("ROLE_PROMPT_OUTSIDE_SLOT", path + "/prompts/" + override.getKey(),
                        "该提示词片段不属于所选工作流槽位"));
            inherited.put(override.getKey(), override.getValue());
        }
        return Map.copyOf(inherited);
    }

    private static RoleManifest.Role canonicalRole(RoleManifest.Role role, Map<String, String> fragments) {
        Map<String, String> keys = new TreeMap<>();
        fragments.keySet().forEach(key -> keys.put(key, key));
        return new RoleManifest.Role(role.roleId(), role.displayName(), role.description(), role.groupKey(),
                role.groupLabel(), role.allowedSlots(), role.permissionMode(), role.nativeTools(), role.mcpTools(),
                role.requiredMcpTools(), role.modelPolicy(), role.runtimePolicy(), keys);
    }

    private static boolean introducesTemplateDirective(String baseline, String candidate) {
        Map<String, Integer> original = templateTokens(baseline);
        Map<String, Integer> proposed = templateTokens(candidate);
        return proposed.entrySet().stream().anyMatch(entry -> entry.getValue() > original.getOrDefault(entry.getKey(), 0));
    }

    private static Map<String, Integer> templateTokens(String source) {
        Map<String, Integer> tokens = new java.util.HashMap<>();
        Matcher matcher = TEMPLATE_DIRECTIVE.matcher(source);
        while (matcher.find()) tokens.merge(matcher.group().strip(), 1, Integer::sum);
        return tokens;
    }

    private void validateTool(String name, String profile, List<Diagnostic> problems, String path) {
        if (name.startsWith("@loopper-assist/")) {
            String tool = name.substring(16);
            if (AssistToolCatalog.tools().stream().noneMatch(item -> item.name().equals(tool))
                    || !AssistToolCatalog.allowed(profile).contains(tool))
                problems.add(new Diagnostic("ROLE_MCP_TOOL_UNAVAILABLE", path + "/mcpTools", "该适配器不允许使用此辅助 MCP 工具"));
        } else if (name.startsWith("@loopper-internal/")) {
            String tool = name.substring("@loopper-internal/".length());
            if (!allowedInternalTools(profile).contains(tool))
                problems.add(new Diagnostic("ROLE_MCP_TOOL_UNAVAILABLE", path + "/mcpTools",
                        "该适配器不允许使用此内部 MCP 工具：" + tool));
        }
    }

    private static Set<String> allowedInternalTools(String profileName) {
        if ("ACCOUNTING_COMMAND".equals(profileName)) return Set.of();
        OpenCodeClient.SessionProfile profile = OpenCodeClient.SessionProfile.valueOf(profileName);
        String server = "role_validation_internal";
        String prefix = server + "_";
        Set<String> allowed = new HashSet<>();
        OpenCodePermissionPolicy.previewRules(profile, List.of(), server).stream()
                .filter(rule -> "allow".equals(rule.action()) && rule.permission().startsWith(prefix)
                        && !rule.permission().contains("*"))
                .map(rule -> rule.permission().substring(prefix.length()))
                .forEach(allowed::add);
        if (DocumentDevelopmentProfiles.supports(profileName)) {
            allowed.addAll(DocumentDevelopmentProfiles.TOOLS);
            allowed.addAll(SourceDevelopmentProfiles.TOOLS);
        }
        return Set.copyOf(allowed);
    }

    private String contentSha(RoleManifest.Role role, Map<String, String> fragments) {
        Map<String, Object> semantic = new TreeMap<>();
        semantic.put("roleId", role.roleId());
        semantic.put("displayName", role.displayName());
        semantic.put("description", role.description());
        semantic.put("groupKey", role.groupKey());
        semantic.put("groupLabel", role.groupLabel());
        semantic.put("allowedSlots", role.allowedSlots());
        semantic.put("permissionMode", role.permissionMode());
        semantic.put("nativeTools", role.nativeTools());
        semantic.put("mcpTools", role.mcpTools());
        semantic.put("requiredMcpTools", role.requiredMcpTools());
        semantic.put("modelPolicy", role.modelPolicy());
        semantic.put("runtimePolicy", role.runtimePolicy());
        semantic.put("prompts", new TreeMap<>(role.prompts()));
        return RoleConfigurationService.sha256(json.writeValueAsString(semantic) + "\n"
                + json.writeValueAsString(new TreeMap<>(fragments)));
    }

    private String requestSha(PublishRequest request) {
        List<Activation> submitted = request.activations() == null ? List.of() : request.activations();
        if (submitted.stream().anyMatch(item -> item == null || item.slot() == null || item.roleId() == null))
            throw new BadRequestException("ROLE_IMPORT_ACTIVATION_INVALID", "角色激活请求不完整");
        StringBuilder identity = new StringBuilder(request.sourceSha256()).append('\n');
        submitted.stream().sorted(java.util.Comparator.comparing(Activation::slot)).forEach(item ->
                identity.append(item.slot()).append('\n').append(item.roleId()).append('\n')
                        .append(item.expectedVersion()).append('\n'));
        return RoleConfigurationService.sha256(identity.toString());
    }

    private static boolean sameActivations(List<Activation> expected, List<Activation> actual) {
        if (expected.size() != actual.size()) return false;
        Map<String, Activation> bySlot = new LinkedHashMap<>();
        for (Activation item : actual) if (item == null || bySlot.putIfAbsent(item.slot(), item) != null) return false;
        return expected.stream().allMatch(item -> {
            var submitted = bySlot.get(item.slot());
            return submitted != null && item.roleId().equals(submitted.roleId())
                    && item.expectedVersion() == submitted.expectedVersion();
        });
    }

    private static void fieldDiff(String path, RoleManifest.Role before, RoleManifest.Role after, List<Change> changes) {
        if (!before.displayName().equals(after.displayName())) changes.add(new Change(path + "/displayName", before.displayName(), after.displayName()));
        if (!before.description().equals(after.description())) changes.add(new Change(path + "/description", before.description(), after.description()));
        if (!before.groupKey().equals(after.groupKey())) changes.add(new Change(path + "/groupKey", before.groupKey(), after.groupKey()));
        if (!before.groupLabel().equals(after.groupLabel())) changes.add(new Change(path + "/groupLabel", before.groupLabel(), after.groupLabel()));
        if (!before.allowedSlots().equals(after.allowedSlots())) changes.add(new Change(path + "/allowedSlots", before.allowedSlots(), after.allowedSlots()));
        if (!before.permissionMode().equals(after.permissionMode())) changes.add(new Change(path + "/permissionMode", before.permissionMode(), after.permissionMode()));
        if (!before.nativeTools().equals(after.nativeTools())) changes.add(new Change(path + "/nativeTools", before.nativeTools(), after.nativeTools()));
        if (!before.mcpTools().equals(after.mcpTools())) changes.add(new Change(path + "/mcpTools", before.mcpTools(), after.mcpTools()));
        if (!before.requiredMcpTools().equals(after.requiredMcpTools())) changes.add(new Change(path + "/requiredMcpTools", before.requiredMcpTools(), after.requiredMcpTools()));
        if (!before.modelPolicy().equals(after.modelPolicy())) changes.add(new Change(path + "/modelPolicy", before.modelPolicy(), after.modelPolicy()));
        if (!before.runtimePolicy().equals(after.runtimePolicy())) changes.add(new Change(path + "/runtimePolicy", before.runtimePolicy(), after.runtimePolicy()));
        if (!before.prompts().equals(after.prompts())) changes.add(new Change(path + "/prompts", before.prompts(), after.prompts()));
    }

    private void audit(String kind, String roleId, String revisionId, String slot, Object detail, String now) {
        if (mapper.audit(UUID.randomUUID().toString(), kind, roleId, revisionId, slot,
                json.writeValueAsString(detail), now) != 1)
            throw new ConflictException("ROLE_AUDIT_FAILED", "角色变更审计保存失败");
    }
}
