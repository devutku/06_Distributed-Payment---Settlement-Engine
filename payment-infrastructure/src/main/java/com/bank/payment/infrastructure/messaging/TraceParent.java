package com.bank.payment.infrastructure.messaging;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W3C Trace Context "traceparent" values (version 00), used to carry a request's trace
 * across the transactional outbox, where the publishing thread has no link to the request.
 */
public final class TraceParent {

    private static final Pattern FORMAT =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private TraceParent() {}

    /** Traceparent of the tracer's current span, or null when no span is active. */
    public static String ofCurrentSpan(Tracer tracer) {
        if (tracer == null || tracer.currentSpan() == null) {
            return null;
        }
        TraceContext ctx = tracer.currentSpan().context();
        return "00-" + ctx.traceId() + "-" + ctx.spanId() + "-" + (Boolean.FALSE.equals(ctx.sampled()) ? "00" : "01");
    }

    /** Parses a stored traceparent; empty when absent or malformed. */
    public static Optional<TraceContext> parse(String value, Tracer tracer) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(value.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(tracer.traceContextBuilder()
                .traceId(m.group(1))
                .spanId(m.group(2))
                .sampled("01".equals(m.group(3)))
                .build());
    }
}
