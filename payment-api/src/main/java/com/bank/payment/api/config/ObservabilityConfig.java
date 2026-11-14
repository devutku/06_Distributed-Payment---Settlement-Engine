package com.bank.payment.api.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Keeps traces about business work. Without this, every 1 s outbox poll, every JDBC call made
 * outside a request (scheduler claim query, outbox gauge) and every Prometheus scrape would each
 * become a separate trace and bury the transfer traces in Jaeger.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public ObservationPredicate ignoreBackgroundNoise() {
        return (name, context) -> {
            if (name.startsWith("tasks.scheduled")) {
                return false;
            }
            // JDBC observed only as part of something else (a request, a Kafka consumer)
            if (name.startsWith("jdbc") && context.getParentObservation() == null) {
                return false;
            }
            if (context instanceof ServerRequestObservationContext http
                    && http.getCarrier() != null
                    && http.getCarrier().getRequestURI().startsWith("/actuator")) {
                return false;
            }
            return true;
        };
    }
}
