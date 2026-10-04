import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.runtime.HttpOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Four bounded local transport contracts, never a real model or production business owner. */
public final class NativeTransportFaultProbe {
    private static final List<String> CASES = List.of("normal", "provider-error", "transport-deadline", "cancel");
    private static final Map<String, String> MARKERS = Map.of("normal", "BUSINESS_RESULT_NORMAL",
            "provider-error", "BUSINESS_RESULT_ERROR", "transport-deadline", "BUSINESS_RESULT_RECOVERED",
            "cancel", "BUSINESS_RESULT_CANCELLED");
    private static String phase = "input";

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !CASES.contains(args[3])) throw new IllegalArgumentException("endpoint work result case required");
        URI endpoint = URI.create(args[0]);
        require("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()), "loopback endpoint");
        Path work = Path.of(args[1]).toRealPath(), output = Path.of(args[2]);
        String mode = args[3], password = System.getenv("PROBE_SERVER_PASSWORD");
        require(password != null && !password.isBlank(), "private nonce required");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("caseId", mode); report.put("realModelCalls", 0); report.put("status", "FAIL");
        int exit = 1;
        try {
            HttpOpenCodeClient client = client(endpoint, password, Duration.ofSeconds(3));
            RestClient raw = raw(endpoint, password);
            phase = "create";
            var model = new OpenCodeClient.OpenCodeModel("aicoding-test", "mock", false);
            var session = client.createSession(work, "isolated-fault-" + mode, model, OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS);
            require(session.worktree().equals(work), "original directory");
            String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");
            var prompt = new OpenCodeClient.PromptRequest(MARKERS.get(mode), null, OpenCodeClient.ROUTER_AGENT,
                    new OpenCodeClient.ResponseFormat.Text(), messageId, List.of());
            String originalHash = OpenCodeClient.promptRequestSha256(prompt);
            report.put("sessionId", session.id()); report.put("messageId", messageId);
            report.put("requestSha256", originalHash); report.put("directory", work.toString());
            phase = "original-prompt";
            client.promptAsync(session, prompt); // One original POST. No retry/replacement on any path.
            report.put("acceptedTransport", true);
            await(() -> client.findPromptMessage(session, prompt, originalHash).exists(), 5000);
            if (mode.equals("transport-deadline")) {
                phase = "arm-transport-read-deadline";
                JsonNode armed = raw.post().uri("/probe/control").body(Map.of("action", "armReadDeadline")).retrieve().body(JsonNode.class);
                require(armed != null && armed.path("armed").asBoolean(), "owned GET delay armed");
                HttpOpenCodeClient bounded = client(endpoint, password, Duration.ofMillis(500));
                bounded.restoreDesignTurn(session, OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS, model, messageId);
                long before = System.nanoTime();
                report.put("transportReadStartedAt", java.time.Instant.now().toString());
                try { bounded.sessionStatus(session); throw new IllegalStateException("transport timeout required"); }
                catch (SessionFailure failure) {
                    require("OPENCODE_STATUS_FAILED".equals(failure.code()), "transport failure code");
                    report.put("transportFailureCode", failure.code());
                    report.put("transportElapsedMs", (System.nanoTime() - before) / 1_000_000);
                    report.put("transportFailureObservedAt", java.time.Instant.now().toString());
                }
                phase = "accepted-read-only-recovery";
                require(client.findPromptMessage(session, prompt, originalHash).exists(), "original accepted lookup after timeout");
            }
            if (mode.equals("cancel")) {
                phase = "pending-model-stream";
                await(() -> evidence(raw).path("activeStream").asBoolean(), 7000);
                var active = client.sessionStatus(session);
                require(!active.completed() && !active.failed(), "active authority before cancellation");
                phase = "explicit-abort";
                var acknowledgement = client.abortWithConfirmation(session);
                require(acknowledgement == OpenCodeClient.AbortConfirmation.ACKNOWLEDGED, "positive abort receipt");
                report.put("abortAcknowledgement", acknowledgement.name());
                phase = "authoritative-stop-observation";
                await(() -> client.sessionStatus(session).failed(), 7000);
                JsonNode status = raw.get().uri("/session/status?directory={directory}", work.toString()).retrieve().body(JsonNode.class);
                JsonNode entry = status == null ? null : status.get(session.id());
                require(entry == null || entry.path("type").asText().equals("idle") || entry.path("status").asText().equals("idle"), "native session no longer busy");
                require(client.sessionResult(session).errorType() != null, "native cancelled assistant outcome");
                await(() -> evidence(raw).path("modelClosed").asBoolean(), 3000);
                report.put("receiverStreamClosed", true);
            } else {
                phase = "authoritative-terminal-read";
                await(() -> {
                    var state = client.sessionStatus(session);
                    return mode.equals("provider-error") ? state.failed() : state.completed();
                }, 12000);
                var result = client.sessionResult(session);
                if (mode.equals("provider-error")) {
                    require(result.errorType() != null && result.errorDetail() != null
                            && result.errorDetail().contains("SYNTHETIC_PROVIDER_ERROR"), "typed synthetic provider failure");
                    report.put("providerErrorType", result.errorType());
                    report.put("providerErrorObserved", true);
                } else require(result.errorType() == null && MARKERS.get(mode).equals(result.text()), "original deterministic output");
            }
            phase = "read-only-original-identity";
            require(client.findPromptMessage(session, prompt, originalHash).exists(), "original message/body identity remains");
            require(originalHash.equals(OpenCodeClient.promptRequestSha256(prompt)), "original body hash remains");
            require(client.sessionMessageRefs(session).stream().filter(r -> messageId.equals(r.id()) && "user".equals(r.role())).count() == 1,
                    "one original user message");
            require(client.sessionTranscript(session).parts().stream().noneMatch(r -> "tool".equalsIgnoreCase(r.type())), "no tool parts");
            JsonNode proof = evidence(raw);
            require(proof.path("creates").asInt() == 1 && proof.path("prompts").asInt() == 1 && proof.path("modelRequests").asInt() == 1,
                    "one session and original prompt/provider request");
            require(proof.path("aborts").asInt() == (mode.equals("cancel") ? 1 : 0), "only explicit cancellation writes");
            require(proof.path("violations").size() == 0, "no receiver violation");
            if (mode.equals("transport-deadline")) require(proof.path("delayedGets").asInt() == 1, "one delayed authoritative GET");
            report.put("state", client.sessionStatus(session).state());
            report.put("receiptRecovery", "original-id/body exact GET only");
            report.put("profile", "ROUTER_NO_TOOLS"); report.put("toolParts", 0);
            report.put("evidence", proof); report.put("status", "PASS"); exit = 0;
        } catch (Exception failure) {
            // Do not log exception bodies that could include headers or nonce values.
            report.put("failedPhase", phase); report.put("exceptionType", failure.getClass().getSimpleName());
            if (failure instanceof SessionFailure f) report.put("failureCode", f.code());
            System.err.println("fault probe failed at " + phase + ": " + failure.getClass().getSimpleName());
        } finally {
            Files.writeString(output, JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        }
        System.exit(exit);
    }

    private static HttpOpenCodeClient client(URI endpoint, String password, Duration timeout) {
        LoopperProperties p = new LoopperProperties();
        p.getOpenCode().setBaseUrl(endpoint); p.getOpenCode().setUsername("opencode"); p.getOpenCode().setPassword(password);
        p.getOpenCode().setConnectTimeout(Duration.ofSeconds(1)); p.getOpenCode().setRequestTimeout(timeout);
        return new HttpOpenCodeClient(RestClient.builder(), p);
    }
    private static RestClient raw(URI endpoint, String password) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1)); factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().requestFactory(factory).baseUrl(endpoint.toString())
                .defaultHeaders(h -> h.setBasicAuth("opencode", password)).build();
    }
    private static JsonNode evidence(RestClient raw) { return raw.get().uri("/probe/evidence").retrieve().body(JsonNode.class); }
    private static void await(BooleanSupplier ready, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(millis).toNanos();
        while (System.nanoTime() < deadline) { if (ready.getAsBoolean()) return; Thread.sleep(100); }
        throw new IllegalStateException("bounded observation did not complete");
    }
    private static void require(boolean value, String contract) { if (!value) throw new IllegalStateException(contract); }
}
