package com.bank.payment.api;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
class TracingConfigurationTest {

    @Autowired
    private Tracer tracer;

    @Value("${management.tracing.sampling.probability}")
    private double samplingProbability;

    @Value("${spring.kafka.template.observation-enabled:false}")
    private boolean kafkaTemplateObservation;

    @Value("${spring.kafka.listener.observation-enabled:false}")
    private boolean kafkaListenerObservation;

    @Autowired
    private org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory;

    @Test
    @DisplayName("The custom listener container factory has observation on, so consumers continue the producer's trace")
    void listenerFactoryObservesConsumers() {
        assertThat(kafkaListenerContainerFactory.getContainerProperties().isObservationEnabled()).isTrue();
    }

    @Test
    @DisplayName("An OpenTelemetry-backed tracer is wired, Kafka observation is on, and tests sample nothing")
    void tracingIsWired() {
        assertThat(tracer).isInstanceOf(OtelTracer.class);
        assertThat(kafkaTemplateObservation).isTrue();
        assertThat(kafkaListenerObservation).isTrue();
        assertThat(samplingProbability).isZero();
    }
}
