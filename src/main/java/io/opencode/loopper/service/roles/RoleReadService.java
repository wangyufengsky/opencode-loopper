package io.opencode.loopper.service.roles;

import io.opencode.loopper.persistence.RoleConfigurationMapper;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.NotFoundException;
import io.opencode.loopper.service.ProjectService;
import io.opencode.loopper.service.assist.AssistToolCatalog;
import io.opencode.loopper.service.assist.AssistToolPolicyService;
import io.opencode.loopper.runtime.DocumentDevelopmentProfiles;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import io.opencode.loopper.runtime.KnowledgeSessionPolicy;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodePermissionPolicy;
import io.opencode.loopper.runtime.SourceDevelopmentProfiles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Read-only published configuration, including explicitly incomplete permission previews. */
@Service
public class RoleReadService {
    private final RoleConfigurationMapper mapper;
    private final RolePublishingService publishing;
    private final RoleConfigurationService roles;
    private final AssistToolPolicyService assistPolicies;
    private final ProjectService projects;
    private final ObjectMapper json;

    public RoleReadService(RoleConfigurationMapper mapper, RolePublishingService publishing,
                           RoleConfigurationService roles, AssistToolPolicyService assistPolicies,
                           ProjectService projects, ObjectMapper json) {
        this.mapper = mapper;
        this.publishing = publishing;
        this.roles = roles;
        this.assistPolicies = assistPolicies;
        this.projects = projects;
        this.json = json;
    }

    public record CatalogItem(String roleId, String displayName, String description, String groupKey,
                              String groupLabel, String origin, String latestRevisionId,
                              int latestRevisionNumber, List<String> activeSlots) { }
    public record CatalogPage(List<CatalogItem> items, String nextCursor) { }
    public record RevisionSummary(String revisionId, int revisionNumber, String contentSha256,
                                  String publishedAt) { }
    public record RevisionPage(List<RevisionSummary> items, String nextCursor) { }
    public record RoleDetail(String roleId, String displayName, String description, String groupKey,
                             String groupLabel, String origin, String latestRevisionId,
                             int latestRevisionNumber, List<String> activeSlots,
                             List<RevisionSummary> revisions, List<RolePublishingService.BindingView> slots) { }
    public record Revision(String revisionId, int revisionNumber, String contentSha256, String publishedAt,
                           String roleId, Map<String, Object> manifest, Map<String, String> promptFragments,
                           String permissionMode, List<String> nativeTools, List<String> mcpTools,
                           List<String> requiredMcpTools, String modelPolicy, String runtimePolicy,
                           List<String> promptVariables) { }
    public record Comparison(String fromRevisionId, String toRevisionId,
                             List<RolePublishingService.Change> changes) { }
    public record PreviewRule(String permission, String pattern, String action, String source) { }
    public record PreviewTool(String name, String server, String source, boolean required,
                              boolean available) { }
    public record Preview(String scope, String slot, String projectId, List<PreviewRule> rules,
                          List<PreviewTool> mcpTools, boolean complete, List<String> limitations) { }

    public CatalogPage list(String query, String cursor, Integer limit) {
        int count = pageLimit(limit);
        String after = cursor == null ? "" : cursor;
        if (!after.isEmpty() && !after.matches("[a-z][a-z0-9._-]{2,119}")) throw bad("角色分页游标无效");
        String search = query == null ? "" : query.strip();
        if (search.length() > 120) throw bad("角色搜索条件过长");
        List<RoleConfigurationMapper.Summary> rows = mapper.summaries(after, search, count + 1);
        boolean more = rows.size() > count;
        List<CatalogItem> items = rows.stream().limit(count).map(this::item).toList();
        return new CatalogPage(items, more && !items.isEmpty() ? items.getLast().roleId() : null);
    }

    public RoleDetail detail(String roleId) {
        var row = mapper.summary(roleId);
        if (row == null) throw new NotFoundException("角色不存在");
        CatalogItem item = item(row);
        var history = history(roleId, "", 10).items();
        var slots = publishing.bindings().stream().filter(binding -> roleId.equals(binding.activeRoleId())).toList();
        return new RoleDetail(item.roleId(), item.displayName(), item.description(), item.groupKey(),
                item.groupLabel(), item.origin(), item.latestRevisionId(), item.latestRevisionNumber(),
                item.activeSlots(), history, slots);
    }

    public RevisionPage history(String roleId, String cursor, Integer limit) {
        requireRole(roleId);
        int before = Integer.MAX_VALUE;
        if (cursor != null && !cursor.isBlank()) {
            try { before = Integer.parseInt(cursor); }
            catch (NumberFormatException invalid) { throw bad("修订分页游标无效"); }
            if (before < 1) throw bad("修订分页游标无效");
        }
        int count = pageLimit(limit);
        List<RoleConfigurationMapper.Revision> rows = mapper.revisions(roleId, before, count + 1);
        boolean more = rows.size() > count;
        List<RevisionSummary> items = rows.stream().limit(count).map(this::summary).toList();
        return new RevisionPage(items, more && !items.isEmpty()
                ? Integer.toString(items.getLast().revisionNumber()) : null);
    }

    public Revision revision(String roleId, String revisionId) {
        return view(requireRevision(roleId, revisionId));
    }

    public Comparison compare(String roleId, String fromId, String toId) {
        Revision from = view(requireRevision(roleId, fromId));
        Revision to = view(requireRevision(roleId, toId));
        List<RolePublishingService.Change> changes = new ArrayList<>();
        Map<String, Object> left = new TreeMap<>(from.manifest());
        Map<String, Object> right = new TreeMap<>(to.manifest());
        var keys = new LinkedHashSet<>(left.keySet()); keys.addAll(right.keySet());
        for (String key : keys) if (!Objects.equals(left.get(key), right.get(key)))
            changes.add(new RolePublishingService.Change("/roles/" + roleId + "/" + key,
                    left.get(key), right.get(key)));
        keys = new LinkedHashSet<>(from.promptFragments().keySet()); keys.addAll(to.promptFragments().keySet());
        for (String key : keys) if (!Objects.equals(from.promptFragments().get(key), to.promptFragments().get(key)))
            changes.add(new RolePublishingService.Change("/roles/" + roleId + "/prompts/" + key,
                    from.promptFragments().get(key), to.promptFragments().get(key)));
        return new Comparison(fromId, toId, List.copyOf(changes));
    }

    public Preview preview(String roleId, String slot, String projectId) {
        RoleConfigurationMapper.Revision revision = mapper.latest(roleId);
        if (revision == null) throw new NotFoundException("角色不存在或尚未发布修订");
        RoleManifest.Role role = json.readValue(revision.manifestJson(), RoleManifest.Role.class);
        var binding = slot == null ? null : mapper.binding(slot);
        if (binding == null || !role.allowedSlots().contains(slot))
            throw bad("角色不支持该工作流槽位");
        String project = projectId == null ? "" : projectId.strip();
        var projectRow = project.isEmpty() ? null : projects.get(project);
        if ("ACCOUNTING_COMMAND".equals(slot))
            return new Preview("CONFIG_ONLY", slot, project, List.of(),
                    List.of(), false, List.of("统计辅助使用独立 native Agent 命令；工具、作用域凭证及提交协议由服务端固定。",
                    "此处仅能查看或导入 accounting.instructions 提示词；预览不执行命令。"));
        OpenCodeClient.SessionProfile profile = OpenCodeClient.SessionProfile.valueOf(binding.adapterProfile());
        String internal = "role-preview-internal";
        List<OpenCodeClient.SessionPermissionRule> baseline = new ArrayList<>(
                OpenCodePermissionPolicy.previewRules(profile, List.of(), internal));
        if (profile != OpenCodeClient.SessionProfile.PPT_AGENT) {
            baseline.add(new OpenCodeClient.SessionPermissionRule(
                    AssistToolCatalog.serverName(internal) + "_*", "*", "deny"));
            if (DocumentDevelopmentProfiles.supports(profile.name())) {
                DocumentDevelopmentProfiles.TOOLS.forEach(tool -> baseline.add(
                        new OpenCodeClient.SessionPermissionRule(internal + "_" + tool, "*", "allow")));
                SourceDevelopmentProfiles.TOOLS.forEach(tool -> baseline.add(
                        new OpenCodeClient.SessionPermissionRule(internal + "_" + tool, "*", "allow")));
            }
            var allowed = AssistToolCatalog.allowed(profile.name());
            var bundledTools = AssistToolCatalog.tools().stream().map(AssistToolCatalog.Tool::name).toList();
            for (var setting : assistPolicies.readCatalog(project, AssistToolCatalog.SERVER, bundledTools, true))
                if (setting.enabled() && allowed.contains(setting.name()))
                    baseline.add(new OpenCodeClient.SessionPermissionRule(
                            AssistToolCatalog.serverName(internal) + "_" + setting.name(), "*", "allow"));
        }
        java.util.Set<String> knownExact = baseline.stream().filter(rule -> "allow".equals(rule.action()))
                .map(OpenCodeClient.SessionPermissionRule::permission)
                .filter(name -> !name.endsWith("_*") && !RoleConfigurationService.NATIVE_TOOLS.contains(name)
                        && !KnowledgeSessionPolicy.MARKER.equals(name))
                .collect(java.util.stream.Collectors.toSet());
        var resolved = roles.resolveRevision(revision.revisionId(), slot);
        List<String> limitations = new ArrayList<>();
        if (projectRow != null && projectRow.managed() != 1)
            limitations.add("项目当前未处于托管状态，不能据此判断新会话可创建。");
        List<OpenCodeClient.SessionPermissionRule> compiled;
        try { compiled = roles.compileNarrowedPermissions(resolved, baseline, knownExact, internal); }
        catch (ConflictException unavailable) {
            compiled = List.of();
            limitations.add(unavailable.getMessage());
        }
        List<PreviewRule> rules = compiled.stream().map(rule -> new PreviewRule(rule.permission(),
                rule.pattern(), rule.action(), "ADAPTER_PROJECT_ROLE_ESTIMATE")).toList();
        List<PreviewTool> tools = new ArrayList<>(systemRequiredTools(profile));
        if (!compiled.isEmpty()) for (String name : RoleConfigurationService.NATIVE_TOOLS.stream().sorted().toList()) {
            String action = profile == OpenCodeClient.SessionProfile.IMPLEMENTATION ? "allow" : "deny";
            for (var rule : compiled) {
                if ("*".equals(rule.pattern()) && ("*".equals(rule.permission()) || name.equals(rule.permission())))
                    action = rule.action();
            }
            if ("allow".equals(action)) tools.add(new PreviewTool(name, "native", "NATIVE_POLICY", false, false));
        }
        java.util.Set<String> systemNames = tools.stream().map(PreviewTool::name)
                .collect(java.util.stream.Collectors.toSet());
        // BASELINE manifests intentionally have empty tool declarations. Project the
        // adapter's exact bundled tools as well, without claiming a live connection.
        var actions = new LinkedHashMap<String, String>();
        compiled.stream().filter(rule -> "*".equals(rule.pattern()))
                .forEach(rule -> actions.put(rule.permission(), rule.action()));
        actions.forEach((name, action) -> {
            if (!"allow".equals(action) || name.endsWith("_*") || !name.startsWith(internal + "_")) return;
            String server = name.startsWith(internal + "_assist_") ? "@loopper-assist" : "@loopper-internal";
            String prefix = server.equals("@loopper-assist") ? internal + "_assist_" : internal + "_";
            String stableName = server + "/" + name.substring(prefix.length());
            if (systemNames.add(stableName)) tools.add(new PreviewTool(stableName, server,
                    "BUNDLED_POLICY", role.requiredMcpTools().contains(stableName), false));
        });
        role.mcpTools().stream().filter(tool -> !systemNames.contains(tool)).forEach(tool ->
                tools.add(new PreviewTool(tool,
                        tool.startsWith("@loopper-internal/") ? "@loopper-internal"
                                : tool.startsWith("@loopper-assist/") ? "@loopper-assist" : "exact",
                        "ROLE_DECLARATION", role.requiredMcpTools().contains(tool), false)));
        return new Preview("CONFIG_ONLY", slot, project, rules, List.copyOf(tools), false,
                List.copyOf(limitations));
    }

    static List<PreviewTool> systemRequiredTools(OpenCodeClient.SessionProfile profile) {
        return InternalMcpContractCatalog.toolName(profile).stream()
                .flatMap(submit -> java.util.stream.Stream.of(submit,
                        InternalMcpContractCatalog.DESCRIBE_TOOL))
                .map(tool -> new PreviewTool("@loopper-internal/" + tool, "@loopper-internal",
                        "SYSTEM_REQUIRED", true, false))
                .toList();
    }

    /** A revision exports its frozen fragment bytes, so an editor can round-trip the exact archive. */
    public byte[] export(String roleId, String revisionId) {
        Revision value = revision(roleId, revisionId == null || revisionId.isBlank()
                ? requireLatest(roleId).revisionId() : revisionId);
        Map<String, Object> manifestRole = new LinkedHashMap<>(value.manifest());
        Map<String, String> promptPaths = new TreeMap<>();
        value.promptFragments().keySet().forEach(key -> promptPaths.put(key, "prompts/" + key + ".md"));
        manifestRole.put("prompts", promptPaths);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("roles", List.of(manifestRole));
        manifest.put("bindings", publishing.bindings().stream()
                .filter(binding -> roleId.equals(binding.activeRoleId()))
                .map(binding -> Map.of("slot", binding.slot(), "roleId", roleId)).toList());
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "manifest.yaml", new Yaml().dump(manifest));
            for (var fragment : new TreeMap<>(value.promptFragments()).entrySet())
                put(zip, promptPaths.get(fragment.getKey()), fragment.getValue());
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException failure) { throw new IllegalStateException("角色导出失败", failure); }
    }

    private static void put(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private RoleConfigurationMapper.Revision requireLatest(String roleId) {
        var row = mapper.latest(roleId);
        if (row == null) throw new NotFoundException("角色不存在或尚未发布修订");
        return row;
    }

    private RoleConfigurationMapper.Revision requireRevision(String roleId, String revisionId) {
        var row = mapper.revision(revisionId);
        if (row == null || !row.roleId().equals(roleId)) throw new NotFoundException("角色修订不存在");
        return row;
    }

    private void requireRole(String roleId) {
        if (mapper.definition(roleId) == null) throw new NotFoundException("角色不存在");
    }

    private CatalogItem item(RoleConfigurationMapper.Summary row) {
        List<String> slots = json.readValue(row.activeSlotsJson(), new TypeReference<>() { });
        return new CatalogItem(row.roleId(), row.displayName(), row.description(), row.groupKey(),
                row.groupLabel(), row.origin(), row.latestRevisionId(), row.latestRevisionNumber(), slots);
    }

    private RevisionSummary summary(RoleConfigurationMapper.Revision row) {
        return new RevisionSummary(row.revisionId(), row.revisionNumber(), row.contentSha256(), row.publishedAt());
    }

    private Revision view(RoleConfigurationMapper.Revision row) {
        RoleManifest.Role role = json.readValue(row.manifestJson(), RoleManifest.Role.class);
        Map<String, Object> manifest = json.readValue(row.manifestJson(), new TypeReference<>() { });
        Map<String, String> fragments = json.readValue(row.promptFragmentsJson(), new TypeReference<>() { });
        return new Revision(row.revisionId(), row.revisionNumber(), row.contentSha256(), row.publishedAt(),
                row.roleId(), manifest, fragments, role.permissionMode(), role.nativeTools(), role.mcpTools(),
                role.requiredMcpTools(), role.modelPolicy(), role.runtimePolicy(), List.of());
    }

    private static int pageLimit(Integer requested) {
        return requested == null ? 20 : Math.max(1, Math.min(100, requested));
    }

    private static BadRequestException bad(String message) {
        return new BadRequestException("ROLE_QUERY_INVALID", message);
    }
}
