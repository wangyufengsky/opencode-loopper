package io.opencode.loopper.service.assist;

import io.opencode.loopper.config.LoopperProperties;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/** Exact-instance, bounded REST reads; cancellation bounds body transfer as well as headers. */
@Component
public class GitLabReadTransport {
    public static final int LIMIT = 1024 * 1024;
    private final LoopperProperties properties;
    private volatile HttpClient client;
    private volatile Duration clientTimeout;
    private final Semaphore permits = new Semaphore(4);
    public GitLabReadTransport(LoopperProperties properties) { this.properties = properties; }
    public record Reply(int status, String body, boolean truncated, String nextPage) { }
    public String instance() {
        var config = properties.getPublication().getGitlab();
        URI base = config.getApiBaseUrl();
        if (base == null || base.getHost() == null || !base.getHost().equalsIgnoreCase(config.getHost())
                || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
            throw new AssistFailure("GITLAB_CONFIG_INVALID", "请在设置页配置匹配的 GitLab 主机和接口地址");
        return base.toString().replaceFirst("/+$", "");
    }
    public boolean credentialConfigured() {
        String token = properties.getPublication().getGitlab().getPrivateToken();
        return token != null && !token.isBlank();
    }
    private synchronized HttpClient client() {
        Duration timeout = properties.getPublication().getGitlab().getConnectTimeout();
        if (client == null || !timeout.equals(clientTimeout)) {
            if (client != null) client.shutdown();
            client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
            clientTimeout = timeout;
        }
        return client;
    }
    public Reply get(String instance, String path) {
        if (!instance().equals(instance) || !path.startsWith("/projects/"))
            throw new AssistFailure("GITLAB_INSTANCE_CHANGED", "GitLab 实例与冻结授权不一致，请使用新任务");
        if (!credentialConfigured()) throw new AssistFailure("GITLAB_TOKEN_MISSING", "请通过环境变量配置 GitLab Token 并重启服务");
        if (!permits.tryAcquire()) throw new AssistFailure("GITLAB_BUSY", "GitLab 查询繁忙，请稍后重试");
        CompletableFuture<HttpResponse<byte[]>> request = null;
        try {
            var config = properties.getPublication().getGitlab();
            var http = HttpRequest.newBuilder(URI.create(instance + path)).timeout(config.getRequestTimeout())
                    .header("PRIVATE-TOKEN", config.getPrivateToken()).header("Accept", "application/json").GET().build();
            request = client().sendAsync(http, ignored -> new LimitedBody());
            var response = request.get(config.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            byte[] bytes = response.body();
            return new Reply(response.statusCode(), new String(bytes, 0, Math.min(bytes.length, LIMIT), StandardCharsets.UTF_8),
                    bytes.length > LIMIT, response.headers().firstValue("X-Next-Page").orElse(""));
        } catch (TimeoutException failure) {
            throw new AssistFailure("GITLAB_TIMEOUT", "GitLab 请求超时，请检查网络后重试");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssistFailure("GITLAB_INTERRUPTED", "GitLab 请求已中断");
        } catch (ExecutionException | IllegalArgumentException failure) {
            throw new AssistFailure("GITLAB_UNAVAILABLE", "GitLab 连接失败，请检查地址、证书和内网连接");
        } finally {
            if (request != null && !request.isDone()) request.cancel(true);
            permits.release();
        }
    }
    public static void requireSuccess(Reply reply) {
        if (reply.status() == 200) return;
        String code = switch (reply.status()) {
            case 401 -> "GITLAB_UNAUTHORIZED"; case 403 -> "GITLAB_FORBIDDEN";
            case 404 -> "GITLAB_NOT_FOUND"; case 429 -> "GITLAB_RATE_LIMITED";
            case 405 -> "GITLAB_UNSUPPORTED"; default -> "GITLAB_HTTP_ERROR";
        };
        throw new AssistFailure(code, "GitLab 读取失败（HTTP " + reply.status() + "），请检查凭证权限、资源和接口支持情况", "STOP_AND_INSPECT");
    }
    public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int count = Math.min(buffer.remaining(), LIMIT + 1 - bytes.size());
                byte[] part = new byte[count]; buffer.get(part); bytes.writeBytes(part);
                if (bytes.size() > LIMIT) { subscription.cancel(); result.complete(bytes.toByteArray()); return; }
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
