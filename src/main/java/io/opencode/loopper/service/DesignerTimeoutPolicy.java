package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DesignerTimeoutMapper;
import io.opencode.loopper.persistence.LoopperMapper;
import java.time.Instant;
import java.time.Duration;

/** New designs freeze their timeout policy; historical designs retain the legacy default behavior. */
final class DesignerTimeoutPolicy {
    private DesignerTimeoutPolicy() { }
    static int insertWithPolicy(LoopperMapper mapper, io.opencode.loopper.persistence.DesignerSessionRow session,
                                io.opencode.loopper.config.LoopperProperties defaults) {
        int inserted = mapper.insertDesignerSession(session);
        if (inserted == 1) mapper.freezeDesignerTimeout(session.id(), defaults.isTimeoutEnabled(), defaults.getDesignerTimeout().toSeconds());
        return inserted;
    }
    static String reviewerDeadline(LoopperMapper mapper, String id, Instant started) {
        Duration timeout = DocumentRequirementContext.attemptTimeout(mapper, id, duration(mapper, id, Duration.ofSeconds(120)));
        return timeout == null ? null : started.plus(timeout).toString();
    }
    static boolean expired(LoopperMapper mapper, String id, String started, String remote, Duration legacy) {
        Duration timeout = DocumentRequirementContext.attemptTimeout(mapper, id, duration(mapper, id, legacy));
        if (timeout == null || timeout.isZero() || timeout.isNegative()) return false;
        try { return Duration.between(Instant.parse(started), StoryAccountingClock.sessionNow(mapper, remote, started)).compareTo(timeout) > 0; }
        catch (RuntimeException invalidTimestamp) { return false; }
    }
    static Duration duration(DesignerTimeoutMapper mapper, String id, Duration legacy) {
        var value = mapper.designerTimeout(id);
        if (value.isEmpty()) return legacy;
        return value.get().enabled() ? Duration.ofSeconds(value.get().seconds()) : null;
    }
}
