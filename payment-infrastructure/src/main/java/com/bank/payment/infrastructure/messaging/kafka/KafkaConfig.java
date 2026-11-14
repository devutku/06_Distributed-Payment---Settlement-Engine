package com.bank.payment.infrastructure.messaging.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
@EnableKafka
public class KafkaConfig {

    public static final String TRANSFER_EVENTS_TOPIC = "transfer-events";
    public static final String TRANSFER_EVENTS_DLT_TOPIC = "transfer-events.DLT";

    @Bean
    @ConditionalOnProperty(name = "spring.kafka.admin.auto-create", havingValue = "true", matchIfMissing = true)
    public NewTopic transferEventsTopic() {
        return TopicBuilder.name(TRANSFER_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "spring.kafka.admin.auto-create", havingValue = "true", matchIfMissing = true)
    public NewTopic transferEventsDltTopic() {
        return TopicBuilder.name(TRANSFER_EVENTS_DLT_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Exponential Backoff Retry with Dead Letter Queue (DLQ).
     * Non-blocking retry without head-of-line blocking.
     */
    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(TRANSFER_EVENTS_DLT_TOPIC, record.partition())
        );

        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(10000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        // This factory is defined here, so spring.kafka.listener.observation-enabled does not reach it:
        // enable observation explicitly so consumers continue the producer's trace (traceparent header).
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
