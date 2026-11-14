package com.bank.payment.api;

import com.bank.payment.api.config.ObservabilityConfig;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationNoiseFilterTest {

    private final ObservationPredicate predicate = new ObservabilityConfig().ignoreBackgroundNoise();

    private static ServerRequestObservationContext httpContext(String uri) {
        return new ServerRequestObservationContext(new MockHttpServletRequest("GET", uri), new MockHttpServletResponse());
    }

    @Test
    @DisplayName("Scheduler polls do not become traces")
    void dropsScheduledTasks() {
        assertThat(predicate.test("tasks.scheduled.execution", new Observation.Context())).isFalse();
    }

    @Test
    @DisplayName("JDBC calls with no parent (scheduler poll, metrics gauge) are dropped; JDBC inside a request is kept")
    void dropsOnlyRootJdbc() {
        assertThat(predicate.test("jdbc.query", new Observation.Context())).isFalse();

        Observation.Context child = new Observation.Context();
        child.setParentObservation(Observation.start("http.server.requests", ObservationRegistry.create()));
        assertThat(predicate.test("jdbc.query", child)).isTrue();
    }

    @Test
    @DisplayName("Prometheus scrapes are not traced; business requests are")
    void dropsActuatorRequestsOnly() {
        assertThat(predicate.test("http.server.requests", httpContext("/actuator/prometheus"))).isFalse();
        assertThat(predicate.test("http.server.requests", httpContext("/api/v1/transfers"))).isTrue();
    }
}
