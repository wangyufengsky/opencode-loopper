package io.opencode.loopper.service.roles;

import io.opencode.loopper.service.BadRequestException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Bounded in-memory ZIP/YAML decoder; it never extracts paths to disk. */
@Component
public final class RoleArchive {
    private static final int MAX_ZIP = 2 * 1024 * 1024;
    private static final int MAX_ENTRY = 512 * 1024;
    private static final int MAX_TOTAL = 3 * 1024 * 1024;

    public record Parsed(String sourceSha256, RoleManifest.Document manifest,
                         Map<String, Map<String, String>> fragmentsByRole) { }

    public Parsed parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_ZIP) throw bad("角色 ZIP 大小无效或超过 2 MiB");
        Map<String, String> files = new LinkedHashMap<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name == null || !name.matches("(?:manifest\\.yaml|prompts/[a-zA-Z0-9_./-]+\\.md)")
                        || name.contains("..") || name.startsWith("/") || files.containsKey(name)
                        || files.size() >= 1024) throw bad("角色 ZIP 含无效条目或条目过多");
                byte[] content = zip.readNBytes(MAX_ENTRY + 1);
                if (content.length > MAX_ENTRY || (total += content.length) > MAX_TOTAL)
                    throw bad("角色 ZIP 解压内容超过上限");
                files.put(name, utf8(content));
                zip.closeEntry();
            }
        } catch (IOException failure) { throw bad("角色 ZIP 无法读取"); }
        String manifestText = files.remove("manifest.yaml");
        if (manifestText == null) throw bad("角色 ZIP 缺少 manifest.yaml");
        RoleManifest.Document manifest = parseYaml(manifestText);
        if (!manifest.slots().isEmpty()) throw bad("导入包不能定义服务端工作流槽位");
        Map<String, Map<String, String>> fragments = new LinkedHashMap<>();
        Set<String> usedPaths = new HashSet<>();
        for (RoleManifest.Role role : manifest.roles()) {
            Map<String, String> values = new LinkedHashMap<>();
            role.prompts().forEach((key, path) -> {
                if (!path.startsWith("prompts/") || !files.containsKey(path)) throw bad("角色提示词文件缺失");
                if (!usedPaths.add(path)) throw bad("同一提示词文件不能重复引用");
                values.put(key, files.get(path));
            });
            fragments.put(role.roleId(), Map.copyOf(values));
        }
        if (usedPaths.size() != files.size()) throw bad("角色 ZIP 含未引用的提示词文件");
        return new Parsed(RoleConfigurationService.sha256(bytes),
                manifest, Map.copyOf(fragments));
    }

    public RoleManifest.Document parseYaml(String yaml) {
        if (yaml == null || yaml.length() > MAX_ENTRY) throw bad("角色清单过大");
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(MAX_ENTRY);
        Object loaded;
        try { loaded = new Yaml(new SafeConstructor(options)).load(yaml); }
        catch (RuntimeException invalid) { throw bad("角色 YAML 格式无效"); }
        Map<String, Object> root = map(loaded, Set.of("schemaVersion", "slots", "roles", "bindings"));
        if (!(root.get("schemaVersion") instanceof Integer version) || version != 1)
            throw bad("角色清单版本不受支持");
        List<RoleManifest.Slot> slots = new ArrayList<>();
        Object rawSlots = root.get("slots");
        if (rawSlots != null) {
            for (Object raw : list(rawSlots, 100)) {
                Map<String, Object> node = map(raw, Set.of("slot", "adapterProfile", "displayName", "purpose"));
                slots.add(new RoleManifest.Slot(identifier(node, "slot"), identifier(node, "adapterProfile"),
                        text(node, "displayName", 100), text(node, "purpose", 100)));
            }
        }
        List<RoleManifest.Role> roles = new ArrayList<>();
        for (Object raw : list(root.get("roles"), 100)) {
            Map<String, Object> node = map(raw, Set.of("roleId", "displayName", "description", "groupKey",
                    "groupLabel", "allowedSlots", "permissionMode", "nativeTools", "mcpTools",
                    "requiredMcpTools", "modelPolicy", "runtimePolicy", "prompts"));
            String mode = text(node, "permissionMode", 20);
            if (!Set.of("BASELINE", "INTERSECT").contains(mode)) throw bad("角色权限模式无效");
            List<String> nativeTools = strings(node.get("nativeTools"), 100, "[a-z][a-z0-9_]{0,63}");
            List<String> mcpTools = strings(node.get("mcpTools"), 200,
                    "(?:@loopper-(?:internal|assist)/)?[a-zA-Z0-9_-]+(?:_[a-zA-Z0-9_-]+)?");
            List<String> requiredMcpTools = strings(node.get("requiredMcpTools"), 200,
                    "(?:@loopper-(?:internal|assist)/)?[a-zA-Z0-9_-]+(?:_[a-zA-Z0-9_-]+)?");
            if (!mcpTools.containsAll(requiredMcpTools)) throw bad("必需 MCP 工具必须列在 mcpTools 中");
            String modelPolicy = node.get("modelPolicy") == null ? "INHERIT_WORKFLOW" : text(node, "modelPolicy", 64);
            if (!"INHERIT_WORKFLOW".equals(modelPolicy)) throw bad("当前版本仅支持继承工作流模型策略");
            String runtimePolicy = node.get("runtimePolicy") == null ? "WORKFLOW_ADAPTER" : text(node, "runtimePolicy", 64);
            if (!Set.of("WORKFLOW_ADAPTER", "ACCOUNTING_COMMAND").contains(runtimePolicy))
                throw bad("角色运行策略不受支持");
            Map<String, String> prompts = new LinkedHashMap<>();
            if (node.get("prompts") != null) {
                for (var entry : map(node.get("prompts"), null).entrySet()) {
                    if (!entry.getKey().matches("[a-zA-Z0-9_.-]{1,160}") || !(entry.getValue() instanceof String path)
                            || path.length() > 240) throw bad("角色提示词映射无效");
                    prompts.put(entry.getKey(), path);
                }
            }
            roles.add(new RoleManifest.Role(roleId(node), text(node, "displayName", 100),
                    optionalText(node, "description", 1000), text(node, "groupKey", 80),
                    text(node, "groupLabel", 100), strings(node.get("allowedSlots"), 100, "[A-Z][A-Z0-9_]{0,95}"),
                    mode, nativeTools, mcpTools, requiredMcpTools, modelPolicy, runtimePolicy, prompts));
        }
        if (roles.isEmpty()) throw bad("角色清单没有角色");
        if (roles.stream().map(RoleManifest.Role::roleId).distinct().count() != roles.size()
                || slots.stream().map(RoleManifest.Slot::slot).distinct().count() != slots.size())
            throw bad("角色清单包含重复标识");
        List<RoleManifest.Binding> bindings = null;
        if (root.containsKey("bindings")) {
            bindings = new ArrayList<>();
            for (Object raw : list(root.get("bindings"), 100)) {
                Map<String, Object> node = map(raw, Set.of("slot", "roleId"));
                bindings.add(new RoleManifest.Binding(identifier(node, "slot"), roleId(node)));
            }
            if (bindings.stream().map(RoleManifest.Binding::slot).distinct().count() != bindings.size())
                throw bad("角色激活目标包含重复槽位");
        }
        return new RoleManifest.Document(1, slots, roles, bindings);
    }

    private static String roleId(Map<String, Object> node) {
        String value = text(node, "roleId", 120);
        if (!value.matches("[a-z][a-z0-9._-]{2,119}")) throw bad("角色 ID 无效");
        return value;
    }
    private static String identifier(Map<String, Object> node, String key) {
        String value = text(node, key, 96);
        if (!value.matches("[A-Z][A-Z0-9_]{0,95}")) throw bad("角色槽位或适配器标识无效");
        return value;
    }
    private static String text(Map<String, Object> node, String key, int max) {
        if (!(node.get(key) instanceof String value) || value.isBlank() || value.length() > max)
            throw bad("角色字段无效：" + key);
        return value;
    }
    private static String optionalText(Map<String, Object> node, String key, int max) {
        if (node.get(key) == null) return "";
        if (!(node.get(key) instanceof String value) || value.length() > max) throw bad("角色字段无效：" + key);
        return value;
    }
    private static List<String> strings(Object input, int max, String regex) {
        if (input == null) return List.of();
        List<String> values = new ArrayList<>();
        for (Object raw : list(input, max)) {
            if (!(raw instanceof String value) || !value.matches(regex) || values.contains(value))
                throw bad("角色列表含无效或重复项");
            values.add(value);
        }
        return List.copyOf(values);
    }
    private static List<?> list(Object value, int max) {
        if (!(value instanceof List<?> result) || result.size() > max) throw bad("角色列表格式无效或超过上限");
        return result;
    }
    private static Map<String, Object> map(Object value, Set<String> keys) {
        if (!(value instanceof Map<?, ?> raw)) throw bad("角色对象格式无效");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key) || keys != null && !keys.contains(key))
                throw bad("角色清单含未知字段");
            result.put(key, entry.getValue());
        }
        return result;
    }
    private static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { throw bad("角色 ZIP 文本不是 UTF-8"); }
    }
    private static BadRequestException bad(String message) {
        return new BadRequestException("ROLE_ARCHIVE_INVALID", message);
    }
}
