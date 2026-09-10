package io.opencode.loopper.runtime;

import io.opencode.loopper.service.NotFoundException;
import io.opencode.loopper.service.ServiceUnavailableException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only projection of skills discovered by OpenCode; never opens a client-supplied file path. */
@Service
public class OpenCodeSkillInventory {
    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    static final int MAX_DOCUMENT_CHARS = 256 * 1024;
    static final int MAX_SKILLS = 512;
    private final OpenCodeRuntimeManager runtime;
    private final OpenCodeHttpTransport transport = new OpenCodeHttpTransport(
            RestClient.builder(), Duration.ofSeconds(3), Duration.ofSeconds(8));
    private final JsonMapper json = JsonMapper.builder().build();

    public record Skill(String name, String description, String location) { }
    public record Inventory(List<Skill> skills, String checkedAt, boolean complete) { }
    public record Document(String name, String description, String location, String content) { }

    public OpenCodeSkillInventory(OpenCodeRuntimeManager runtime) { this.runtime = runtime; }

    public Inventory inventory(Path directory) {
        JsonNode body = read(directory);
        var skills = new ArrayList<Skill>();
        for (JsonNode entry : body) {
            if (skills.size() == MAX_SKILLS) break;
            skills.add(summary(entry));
        }
        return new Inventory(List.copyOf(skills), Instant.now().toString(), body.size() <= MAX_SKILLS);
    }

    public Document document(Path directory, String name) {
        if (name == null || name.isBlank() || name.length() > 256) throw missing();
        for (JsonNode entry : read(directory)) {
            if (!name.equals(entry.path("name").asText())) continue;
            Skill skill = summary(entry);
            JsonNode content = entry.path("content");
            if (!content.isTextual()) throw unavailable();
            if (content.asText().length() > MAX_DOCUMENT_CHARS) {
                throw new ServiceUnavailableException("OPENCODE_SKILL_DOCUMENT_TOO_LARGE",
                        "Skill 文档超过展示上限，请在本地编辑器查看原文");
            }
            return new Document(skill.name(), skill.description(), skill.location(), content.asText());
        }
        throw missing();
    }

    private Skill summary(JsonNode entry) {
        if (!entry.path("name").isTextual() || entry.path("name").asText().isBlank()
                || entry.path("name").asText().length() > 256 || !entry.path("location").isTextual()) {
            throw unavailable();
        }
        return new Skill(entry.path("name").asText(), entry.path("description").asText(""), entry.path("location").asText());
    }

    private JsonNode read(Path directory) {
        try {
            var connection = runtime.connectionForClient();
            var client = transport.client(new OpenCodeConnectionDetails(connection.endpoint(), connection.username(),
                    connection.password(), connection.managed(), connection.generation(), connection.internalMcpServer()));
            JsonNode body = client.get().uri(uri -> OpenCodeHttpTransport.directoryUri(uri, "/skill", directory))
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) throw unavailable();
                        byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (bytes.length > MAX_RESPONSE_BYTES) throw unavailable();
                        return json.readTree(bytes);
                    });
            if (body == null || !body.isArray()) throw unavailable();
            return body;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static NotFoundException missing() {
        return new NotFoundException("当前项目中未找到此 Skill，请刷新列表后重试");
    }

    private static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException("OPENCODE_SKILLS_UNAVAILABLE",
                "无法读取 OpenCode 的 Skill，请检查运行环境及其 Skill 支持后重试");
    }
}
