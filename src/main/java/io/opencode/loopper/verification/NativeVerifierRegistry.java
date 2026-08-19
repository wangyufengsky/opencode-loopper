package io.opencode.loopper.verification;

import io.opencode.loopper.domain.LoopSpec.VerifierSpec;
import io.opencode.loopper.domain.TaskFailure;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Registry intentionally contains only product-owned deterministic handlers. */
final class NativeVerifierRegistry {
    private final Map<String, NativeVerifierHandler> handlers;

    NativeVerifierRegistry() {
        handlers = java.util.List.<NativeVerifierHandler>of(
                new HttpStatusVerifier(), new JsonPathVerifier(), new FileContentVerifier(), new FileHashVerifier(),
                new JunitXmlVerifier(), new BrowserVerifier(), new DatabaseQueryVerifier(),
                new DocumentStructureVerifier(), new TabularDataVerifier()).stream()
                .collect(Collectors.toUnmodifiableMap(NativeVerifierHandler::type, Function.identity()));
    }

    VerifierOutcome verify(NativeVerifierContext context, VerifierSpec spec) {
        NativeVerifierHandler handler = handlers.get(spec.type());
        if (handler == null) throw new TaskFailure("VERIFIER_TYPE_INVALID", "Unknown verifier type: " + spec.type());
        return handler.verify(context, spec);
    }

    boolean supports(String type) { return handlers.containsKey(type); }
}
