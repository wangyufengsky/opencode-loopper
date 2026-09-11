package io.opencode.loopper.template;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/** Beijing civil dates serialized as dates, never as browser-local instants. */
public record TemplateDateRange(LocalDate startDate, LocalDate endDate) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    public TemplateDateRange {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("结束日期不能早于开始日期");
        }
        if (startDate.getYear() < 1900 || endDate.getYear() > 9998) {
            throw new IllegalArgumentException("日期超出支持范围");
        }
    }

    public static TemplateDateRange parse(String start, String end, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        return new TemplateDateRange(date(start, today.minusDays(6)), date(end, today));
    }

    private static LocalDate date(String text, LocalDate fallback) {
        if (text == null || text.isBlank()) return fallback;
        if (!text.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("日期格式应为 YYYY-MM-DD");
        return LocalDate.parse(text, FORMAT);
    }

    public Instant startInclusive() { return startDate.atStartOfDay(ZONE).toInstant(); }
    public Instant endExclusive() { return endDate.plusDays(1).atStartOfDay(ZONE).toInstant(); }
    public boolean contains(Instant instant) {
        return !instant.isBefore(startInclusive()) && instant.isBefore(endExclusive());
    }
}
