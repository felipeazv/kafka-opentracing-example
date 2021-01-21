package com.feazesa.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feazesa.event.Event;
import com.feazesa.event.Event.Ping;
import com.feazesa.event.Event.Pong;
import com.feazesa.infrastructure.trace.EventTracer;
import io.opentracing.Scope;
import io.opentracing.util.GlobalTracer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.feazesa.infrastructure.trace.EventTracer.KafkaProducerTracer.createSpan;
import static com.feazesa.infrastructure.trace.EventTracer.traceError;

@Getter
public class Kafka {

    /**
     * Kafka Producer
     * Config
     **/
    @AllArgsConstructor(onConstructor = @__(@Autowired))
    @Component
    @Getter
    public static class KafkaProducer {

        /**
         * Config
         */
        @Configuration
        @EnableAsync
        @Getter
        @Validated
        static class Config {

            @Value("${feazesa.kafka.bootstrapserver}")
            private String bootstrapServers;

            @Bean
            public KafkaTemplate<String, String> kafkaTemplate() {
                final var props = new HashMap<String, Object>();
                props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
                props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
                props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
                props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);

                return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
            }
        }

        /**
         * Produce
         */
        @AllArgsConstructor(onConstructor = @__(@Autowired))
        @Component
        @Log4j2
        public static class Produce {
            private final KafkaTemplate<String, String> kafkaTemplate;
            private final ObjectMapper objectMapper;
            private final EventTracer.KafkaProducerTracer kafkaProducerTracer;

            @Async
            public void produce(final Event event, final String topic) {
                final var tracer = GlobalTracer.get();
                final var span = createSpan(event, topic);

                try (Scope ignored = tracer.scopeManager().activate(span)) {
                    try {
                        final var message = objectMapper.writeValueAsString(event);
                        final var producerRecord = new ProducerRecord<>(topic, null, event.getName(), message, kafkaProducerTracer.injectTracingContext(span.context()));
                        kafkaTemplate.send(producerRecord);
                        log.info("Event {} sent at {} to topic {}.", event.getName(), event.getTime(), producerRecord.topic());

                    } catch (JsonProcessingException e) {
                        log.error(" Failed to send event {} sent at {} to topic {}.", event.getName(), event.getTime(), topic);
                        traceError(span, e);
                    }
                } finally {
                    span.finish();
                }
            }
        }
    }


    /**
     * Kafka Consumer
     **/
    @AllArgsConstructor(onConstructor = @__(@Autowired()))
    @Component
    @Log4j2
    static class KafkaConsumer {
        private final KafkaProducer.Produce produce;
        private final EventTracer.KafkaConsumerTracer kafkaConsumerTracer;

        @KafkaListener(topics = "${feazesa.kafka.topic}")
        public void consume(ConsumerRecord<String, String> record) throws JsonProcessingException {
            final var topic = record.topic();
            final var eventString = record.value();
            final var tracer = GlobalTracer.get();
            if (eventString != null) {
                final var span = kafkaConsumerTracer.createSpan(record);

                try (final var ignored = tracer.scopeManager().activate(span)) {
                    try {
                        // Counter set on baggageItems to limit the "ping-pong"
                        final var counter = new AtomicInteger(Integer.parseInt(span.getBaggageItem("counter")));
                        final var MAX = 3;

                        if (counter.intValue() < MAX) {
                            log.info("BaggageItems counter with value {}", counter.intValue());
                            final var mapper = new ObjectMapper();
                            final var receivedEvent = mapper.readValue(eventString, Event.class);
                            final var time = Instant.now().toString();
                            // If ping is received, then pong is sent, vice-versa
                            final var sendEvent = receivedEvent.getName().equalsIgnoreCase("ping") ? new Pong(time) : new Ping(time);

                            produce.produce(sendEvent, topic);
                        } else {
                            log.info("BaggageItems counter has reached limit of {}", MAX);
                        }

                    } catch (Exception e) {
                        traceError(span, e);
                        e.printStackTrace();
                    }
                } finally {
                    span.finish();
                }
            }
        }

        @EnableKafka
        @Configuration
        @Log4j2
        static class Config {

            @Value("${feazesa.kafka.bootstrapserver}")
            private String bootstrapServers;
            @Value("${feazesa.kafka.topic}")
            private String topic;
            @Value("${feazesa.kafka.consumer.groupId}")
            private String groupId;
            @Value("${feazesa.kafka.consumer.auto-offset-reset}")
            private String autoOffsetReset;

            @Bean
            public ConsumerFactory<String, String> consumerFactory() {
                final var props = new HashMap<String, Object>();
                props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
                log.info("Kafka broker-addresses: " + bootstrapServers);
                props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
                props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
                props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                return new DefaultKafkaConsumerFactory<>(props);
            }

            @Bean
            public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
                final var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
                factory.setConsumerFactory(consumerFactory());
                factory.getContainerProperties().setIdleEventInterval(10000L);
                return factory;
            }
        }
    }
}