package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded, exact-host GitLab API reader. Credentials never leave the configured host. */
@Component
public final class GitLabMergeRequestClient {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final LoopperProperties properties;
    private final ObjectMapper json;

    public GitLabMergeRequestClient(LoopperProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    public boolean configuredFor(String remoteHost) {
        var config = properties.getPublication().getGitlab();
        URI base = config.getApiBaseUrl();
        return present(config.getPrivateToken()) && present(config.getHost()) && base != null
                && exactHost(remoteHost, config.getHost()) && exactHost(base.getHost(), config.getHost())
                && ("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()));
    }

    public Lookup lookup(String remoteHost, String projectPath, String sourceBranch,
                         String targetBranch, String taskCommitSha) {
        if (!configuredFor(remoteHost)) {
            throw new LookupException("GITLAB_RECONCILIATION_NOT_CONFIGURED", "未配置该 GitLab 主机的合并状态查询凭据");
        }
        var config = properties.getPublication().getGitlab();
        String base = config.getApiBaseUrl().toString().replaceFirst("/+$", "");
        String url = base + "/projects/" + encode(projectPath) + "/merge_requests?scope=all&per_page=100"
                + "&source_branch=" + encode(sourceBranch) + "&target_branch=" + encode(targetBranch)
                + "&order_by=updated_at&sort=desc";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(config.getRequestTimeout())
                .header("Accept", "application/json")
                .header("PRIVATE-TOKEN", config.getPrivateToken())
                .GET().build();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(config.getConnectTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new LookupException("GITLAB_API_HTTP_" + response.statusCode(),
                        "GitLab 合并状态查询失败（HTTP " + response.statusCode() + "）");
            }
            byte[] responseBytes;
            try (InputStream body = response.body()) {
                responseBytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (responseBytes.length > MAX_RESPONSE_BYTES) {
                throw new LookupException("GITLAB_API_RESPONSE_TOO_LARGE", "GitLab 合并状态响应超过大小上限");
            }
            JsonNode root;
            try {
                root = json.readTree(new String(responseBytes, StandardCharsets.UTF_8));
            } catch (RuntimeException invalid) {
                throw new LookupException("GITLAB_API_INVALID_RESPONSE", "GitLab 返回了无法解析的合并请求数据");
            }
            if (!root.isArray()) throw new LookupException("GITLAB_API_INVALID_RESPONSE", "GitLab 返回了无效的合并请求列表");
            List<MergeRequest> matches = new ArrayList<>();
            for (JsonNode node : root) {
                String sha = text(node, "sha");
                if (taskCommitSha.equalsIgnoreCase(sha)) matches.add(parse(node));
            }
            if (matches.isEmpty()) return new Lookup(null, Instant.now().toString());
            if (matches.size() != 1) {
                throw new LookupException("GITLAB_MERGE_REQUEST_AMBIGUOUS", "找到多个匹配任务提交的 GitLab 合并请求，无法唯一确认");
            }
            return new Lookup(matches.getFirst(), Instant.now().toString());
        } catch (LookupException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LookupException("GITLAB_API_INTERRUPTED", "GitLab 合并状态查询被中断");
        } catch (Exception failure) {
            throw new LookupException("GITLAB_API_UNAVAILABLE", "GitLab 合并状态查询失败：" + safe(failure.getMessage()));
        }
    }

    private MergeRequest parse(JsonNode node) {
        long iid = node.path("iid").asLong(0);
        String state = text(node, "state").toLowerCase(Locale.ROOT);
        if (iid <= 0 || !(state.equals("opened") || state.equals("closed") || state.equals("merged"))) {
            throw new LookupException("GITLAB_API_INVALID_RESPONSE", "GitLab 合并请求字段不完整");
        }
        return new MergeRequest(iid, state, text(node, "web_url"), text(node, "sha"),
                first(text(node, "merge_commit_sha"), text(node, "squash_commit_sha")),
                nullable(node, "created_at"), nullable(node, "merged_at"));
    }

    private static String text(JsonNode node, String name) { return node.path(name).asText("").strip(); }
    private static String nullable(JsonNode node, String name) { String value = text(node, name); return value.isBlank() ? null : value; }
    private static String first(String left, String right) { return left == null || left.isBlank() ? nullable(right) : left; }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : value; }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static boolean exactHost(String left, String right) { return left != null && right != null && left.equalsIgnoreCase(right.strip()); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String safe(String value) { return value == null || value.isBlank() ? "连接异常" : value.substring(0, Math.min(240, value.length())); }

    public record Lookup(MergeRequest mergeRequest, String checkedAt) { }
    public record MergeRequest(long iid, String state, String webUrl, String headSha,
                               String mergeCommitSha, String openedAt, String mergedAt) { }
    public static final class LookupException extends RuntimeException {
        private final String code;
        public LookupException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
