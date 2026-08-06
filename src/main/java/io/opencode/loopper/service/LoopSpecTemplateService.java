package io.opencode.loopper.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.LoopSpecTemplateRow;
import io.opencode.loopper.persistence.LoopSpecTemplateVersionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable template-version store; import preview deliberately never writes a draft or template row. */
@Service
public class LoopSpecTemplateService {
    private final LoopperMapper mapper;
    private final ObjectMapper json;
    private final LoopDraftService drafts;

    public LoopSpecTemplateService(LoopperMapper mapper, ObjectMapper json, LoopDraftService drafts) {
        this.mapper = mapper; this.json = json; this.drafts = drafts;
    }

    @Transactional
    public TemplateView create(String name, String description) {
        String now = now();
        LoopSpecTemplateRow template = new LoopSpecTemplateRow(UUID.randomUUID().toString(), required(name, "TEMPLATE_NAME_REQUIRED"),
                safe(description), "ACTIVE", now, now, 0);
        mapper.insertLoopSpecTemplate(template);
        return get(template.id());
    }

    public TemplateView get(String id) { return view(getTemplate(id)); }
    public List<TemplateView> list() { return mapper.listLoopSpecTemplates().stream().map(this::view).toList(); }
    public List<VersionView> versions(String templateId) { getTemplate(templateId); return mapper.listLoopSpecTemplateVersions(templateId).stream().map(this::versionView).toList(); }

    @Transactional
    public TemplateView update(String id, String name, String description, String state, long version) {
        LoopSpecTemplateRow old = getTemplate(id);
        String normalizedState = "ARCHIVED".equals(state) ? "ARCHIVED" : "ACTIVE";
        LoopSpecTemplateRow changed = new LoopSpecTemplateRow(old.id(), required(name, "TEMPLATE_NAME_REQUIRED"), safe(description),
                normalizedState, old.createdAt(), now(), version);
        if (mapper.updateLoopSpecTemplate(changed) != 1) throw new ConflictException("TEMPLATE_VERSION_CONFLICT", "Template changed concurrently");
        return view(getTemplate(id));
    }

    @Transactional
    public VersionView createVersion(String templateId, LoopSpec spec, boolean autoStartApproved) {
        getTemplate(templateId);
        validate(spec);
        String source = write(spec);
        LoopSpecTemplateVersionRow row = new LoopSpecTemplateVersionRow(UUID.randomUUID().toString(), templateId,
                mapper.nextLoopSpecTemplateVersion(templateId), source, sha256(source), true, autoStartApproved, now());
        try { mapper.insertLoopSpecTemplateVersion(row); }
        catch (RuntimeException conflict) { throw new ConflictException("TEMPLATE_VERSION_CONFLICT", "Template version already exists for this immutable specification"); }
        return versionView(mapper.findLoopSpecTemplateVersion(row.id()).orElseThrow());
    }

    /** Strictly parse and validate an import before an operator decides to persist it. */
    public ImportPreview previewImport(String source) {
        if (source == null || source.isBlank()) throw new BadRequestException("TEMPLATE_IMPORT_REQUIRED", "Template JSON is required");
        try {
            LoopSpec spec = json.readValue(source, LoopSpec.class);
            validate(spec);
            String normalized = write(spec);
            return new ImportPreview(spec, normalized, sha256(normalized));
        } catch (JacksonException invalid) {
            throw new BadRequestException("TEMPLATE_IMPORT_INVALID", "Template JSON cannot be read as a LoopSpec");
        }
    }

    public String export(String templateId) {
        List<LoopSpecTemplateVersionRow> versions = mapper.listLoopSpecTemplateVersions(templateId);
        if (versions.isEmpty()) throw new NotFoundException("Template has no versions: " + templateId);
        return versions.getFirst().specJson();
    }

    public LoopSpec versionSpec(String versionId) {
        LoopSpecTemplateVersionRow row = mapper.findLoopSpecTemplateVersion(versionId)
                .orElseThrow(() -> new NotFoundException("Template version not found: " + versionId));
        return readSpec(row);
    }
    public LoopSpecTemplateVersionRow version(String versionId) {
        return mapper.findLoopSpecTemplateVersion(versionId).orElseThrow(() -> new NotFoundException("Template version not found: " + versionId));
    }

    private TemplateView view(LoopSpecTemplateRow row) { return new TemplateView(row.id(), row.name(), row.description(), row.state(), row.createdAt(), row.updatedAt(), row.version(), versions(row.id())); }
    private VersionView versionView(LoopSpecTemplateVersionRow row) { return new VersionView(row.id(), row.templateId(), row.versionNumber(), readSpec(row), row.specSha256(), row.immutable(), row.autoStartApproved(), row.createdAt()); }
    private LoopSpec readSpec(LoopSpecTemplateVersionRow row) {
        try { return json.readValue(row.specJson(), LoopSpec.class); }
        catch (JacksonException invalid) { throw new ConflictException("TEMPLATE_VERSION_INVALID", "Stored template version cannot be read"); }
    }
    private LoopSpecTemplateRow getTemplate(String id) { return mapper.findLoopSpecTemplate(id).orElseThrow(() -> new NotFoundException("Template not found: " + id)); }
    private void validate(LoopSpec spec) { drafts.validateExecutionContract(spec); }
    private String write(LoopSpec spec) { try { return json.writeValueAsString(spec); } catch (JacksonException failure) { throw new BadRequestException("TEMPLATE_INVALID", "Template LoopSpec cannot be serialized"); } }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); } }
    private String required(String value, String code) { if (value == null || value.isBlank()) throw new BadRequestException(code, "Template name is required"); return value.trim(); }
    private String safe(String value) { return value == null ? "" : value.trim(); }
    private String now() { return Instant.now().toString(); }

    public record TemplateView(String id, String name, String description, String state, String createdAt,
                               String updatedAt, long version, List<VersionView> versions) { }
    public record VersionView(String id, String templateId, int versionNumber, LoopSpec spec,
                              String specSha256, boolean immutable, boolean autoStartApproved,
                              String createdAt) { }
    public record ImportPreview(LoopSpec spec, String normalizedJson, String specSha256) { }
}
