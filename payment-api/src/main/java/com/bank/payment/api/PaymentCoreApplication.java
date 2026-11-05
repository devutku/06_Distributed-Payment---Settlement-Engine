package com.bank.payment.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.bank.payment")
@EnableJpaRepositories(basePackages = "com.bank.payment.infrastructure.persistence.repository")
@EntityScan(basePackages = "com.bank.payment.infrastructure.persistence.entity")
@EnableScheduling
public class PaymentCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentCoreApplication.class, args);
    }
}
