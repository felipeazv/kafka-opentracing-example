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
            final var ping = new Ping(Instant.now().toString());
            kafkaProducer.produce(ping, topic);
        };
    }
}
