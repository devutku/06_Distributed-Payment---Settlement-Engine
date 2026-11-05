package com.bank.payment.api.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class MetricsConfig {

    @Bean
    public Timer paymentTransferTimer(MeterRegistry registry) {
        return Timer.builder("payment.transfer.duration")
                .description("Execution latency of money transfers")
                .publishPercentiles(0.5, 0.90, 0.95, 0.99)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(registry);
    }

    @Bean
    public Counter paymentSuccessCounter(MeterRegistry registry) {
        return Counter.builder("payment.transfers.total")
                .description("Total successful transfers")
                .tag("status", "SUCCESS")
                .register(registry);
    }

    @Bean
    public Counter paymentFailureCounter(MeterRegistry registry) {
        return Counter.builder("payment.transfers.total")
                .description("Total rejected/failed transfers")
                .tag("status", "FAILED")
                .register(registry);
    }
}
