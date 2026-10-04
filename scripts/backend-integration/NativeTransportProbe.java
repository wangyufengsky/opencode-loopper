import io.opencode.loopper.runtime.HttpOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic transport only: the server and its password belong to the parent process. */
public final class NativeTransportProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("endpoint workspace result-file required");
        var endpoint = URI.create(args[0]);
        if (!"127.0.0.1".equals(endpoint.getHost())) throw new IllegalArgumentException("loopback required");
        var cwd = Path.of(args[1]).toRealPath();
        var client = new HttpOpenCodeClient(RestClient.builder(), endpoint, "opencode",
                System.getenv("PROBE_SERVER_PASSWORD"));
        if (!client.healthy()) throw new IllegalStateException("native health failed");
        var model = new OpenCodeClient.OpenCodeModel("aicoding-test", "mock", false);
        var session = client.createSession(cwd, "isolated-local-transport", model,
                OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS);
        if (!session.worktree().equals(cwd)) throw new IllegalStateException("directory mismatch");
        String messageId = "msg_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var prompt = new OpenCodeClient.PromptRequest("BUSINESS_RESULT_OK", null,
                OpenCodeClient.ROUTER_AGENT, new OpenCodeClient.ResponseFormat.Text(), messageId, List.of());
        client.promptAsync(session, prompt); // Exactly once. No recovery write or retry.
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            var state = client.sessionStatus(session);
            if (state.failed()) throw new IllegalStateException("native session failed: " + state.state());
            if (state.completed() && "BUSINESS_RESULT_OK".equals(client.sessionLiveOutput(session))) {
                var result = client.sessionResult(session);
                if (result.errorType() != null || !"BUSINESS_RESULT_OK".equals(result.text()))
                    throw new IllegalStateException("native result mismatch");
                var refs = client.sessionMessageRefs(session);
                if (refs.stream().noneMatch(r -> messageId.equals(r.id()) && "user".equals(r.role())))
                    throw new IllegalStateException("original message identity missing");
                var transcript = client.sessionTranscript(session);
                if (transcript.parts().stream().anyMatch(p -> "tool".equals(p.type())))
                    throw new IllegalStateException("unexpected tool part");
                var report = java.util.Map.of("sessionId", session.id(), "directory", cwd.toString(),
                        "messageId", messageId, "state", state.state(), "text", result.text(),
                        "messageRefs", refs, "toolParts", 0, "profile", "ROUTER_NO_TOOLS");
                java.nio.file.Files.writeString(Path.of(args[2]), JsonMapper.builder().build()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("native transport deadline exceeded");
    }
}
