package io.opencode.loopper.runtime;

import static io.opencode.loopper.runtime.DurableCommandProtocol.*;
import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class DurableCommandProtocolTest {
    private final String root = Path.of(".").toAbsolutePath().normalize().toString();
    private final Identity worker = new Identity(42, "2026-01-01T00:00:00Z");
    private Request spec(List<Preparation> steps) { return new Request("00000000-0000-0000-0000-000000000001", root, List.of("java", "-version"), 10, steps); }
    private Result success() { return new Result("a".repeat(64), worker, 0, true, false, false, false, true, "okay", "", List.of()); }
    @Test void historicalV1ReceiptIsByteIdenticalAfterDecodeAndEncode() throws Exception {
        byte[] frozen = Base64.getDecoder().decode("AAAAAQAAAEBhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhAAAAAAAAACoAAAAUMjAyNi0wMS0wMVQwMDowMDowMFoBAAAAAAEAAAABAAAABG9rYXkAAAAAAAAAAA==");
        assertThat(result(frozen)).isEqualTo(success()); assertThat(result(result(frozen))).isEqualTo(frozen);
        assertThat(ByteBuffer.wrap(request(spec(List.of()))).getInt()).isEqualTo(1);
        // Old valid wire requests may contain a non-normalized absolute path or over 32 KiB of arguments.
        var old = new Request(UUID.randomUUID().toString(), Path.of(root, "sub", "..", "other").toString(), Collections.nCopies(12, "x".repeat(4096)), 10);
        assertThat(request(request(old))).isEqualTo(old);
    }
    @Test void versionTwoRoundTripsPreparationsAndBindsMainLaunchToAllSuccessfulReceipts() throws Exception {
        var request = spec(List.of(new Preparation("INSTALL", root, List.of("npm", "ci"))));
        assertThat(ByteBuffer.wrap(request(request)).getInt()).isEqualTo(2); assertThat(request(request(request))).isEqualTo(request);
        var complete = new Result(success().requestSha256(), worker, 0, true, false, false, false, true, "test", "", List.of(), List.of(success()));
        assertThat(result(result(complete))).isEqualTo(complete); assertThat(matches(request, complete)).isTrue();
        assertThat(matches(request, success())).isFalse(); assertThat(matches(spec(List.of()), complete)).isFalse();
        var failed = new Result(success().requestSha256(), worker, 7, true, false, false, false, true, "install failed", "", List.of());
        var forged = new Result(success().requestSha256(), worker, 0, true, false, false, false, true, "test", "", List.of(), List.of(failed));
        assertThat(matches(request, forged)).isFalse();
    }
    @Test void preparationsCannotEscapeTheirWorkspaceOrNestReceiptsOrCrossIdentities() {
        assertThatThrownBy(() -> spec(List.of(new Preparation("INSTALL", Path.of(root).getParent().toString(), List.of("npm", "ci"))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spec(Collections.nCopies(5, new Preparation("INSTALL", root, List.of("npm", "ci"))))).isInstanceOf(IllegalArgumentException.class);
        var first = new Result(success().requestSha256(), worker, 0, true, false, false, false, true, "", "", List.of(), List.of(success()));
        assertThatThrownBy(() -> new Result(success().requestSha256(), worker, 0, true, false, false, false, true, "", "", List.of(), List.of(first))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Result("other", worker, 0, true, false, false, false, true, "", "", List.of(), List.of(success()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void malformedTrailingAndFutureVersionEvidenceIsRejected() throws Exception {
        byte[] value = result(success()), trailing = Arrays.copyOf(value, value.length + 1);
        assertThatThrownBy(() -> result(trailing)).isInstanceOf(IOException.class);
        ByteBuffer.wrap(value).putInt(3); assertThatThrownBy(() -> result(value)).isInstanceOf(IOException.class);
    }
}
