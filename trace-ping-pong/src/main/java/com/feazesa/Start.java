/**
 *  * Copyright (c) 2020 Vanderlande Industries GmbH
 *  * All rights reserved.
 *  * <p>
 *  * The copyright to the computer program(s) herein is the property of
 *  * Vanderlande Industries. The program(s) may be used and/or copied
 *  * only with the written permission of the owner or in accordance with
 *  * the terms and conditions stipulated in the contract under which the
 *  * program(s) have been supplied.
 *  
 */

package com.feazesa;

import com.feazesa.event.Event.Ping;
import com.feazesa.infrastructure.kafka.Kafka;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

import java.time.Instant;

@EnableAsync
@SpringBootApplication
public class Start {
    public static void main(String[] args) {
        SpringApplication.run(Start.class, args);
    }

    @Bean
    CommandLineRunner run(Kafka.KafkaProducer.Produce kafkaProducer, @Value("${feazesa.kafka.topic}") String topic) {
        return args -> {
            final var ping = new Ping(Instant.now());
            kafkaProducer.produce(ping, topic);
        };
    }
}
