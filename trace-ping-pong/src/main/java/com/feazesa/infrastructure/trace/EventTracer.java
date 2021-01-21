package com.feazesa.infrastructure.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feazesa.event.Event;
import io.opentracing.References;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.contrib.kafka.TracingKafkaUtils;
import io.opentracing.log.Fields;
import io.opentracing.noop.NoopTracerFactory;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static io.opentracing.propagation.Format.Builtin.TEXT_MAP;

@Configuration
@Getter
public class EventTracer {

    public io.opentracing.Tracer Tracer() {
        return GlobalTracer.get();
    }

    public static Span createSpan(TracerProperties tracerProperties, boolean setTagsAsBaggageItems) {
        final var builder = createSpanBuilder(tracerProperties);
        var span = builder.start();
        if (setTagsAsBaggageItems) {
            tracerProperties.getTags().forEach(span::setBaggageItem);
        }
        return span;
    }

    public static Map<String, String> kafkaTracingTags(String topic, String kind, Map<String, String> extraTags) {
        final var tags = new HashMap<String, String>();
        tags.put(Tags.COMPONENT.getKey(), "java-kafka");
        tags.put(Tags.PEER_SERVICE.getKey(), "kafka");
        tags.put(Tags.SPAN_KIND.getKey(), kind);
        tags.put(Tags.MESSAGE_BUS_DESTINATION.getKey(), topic);
        //extra tags
        if (extraTags != null) {
            tags.putAll(extraTags);
        }
        return tags;
    }

    public static void addTagsFromEvent(Span span, Event event) {
        span.setTag("kafka.event.name", event.getName());
        span.setTag("kafka.event.time", event.getTime().toString());
    }

    public static io.opentracing.Tracer.SpanBuilder createSpanBuilder(TracerProperties tracerProperties) {
        final var builder = GlobalTracer.get().buildSpan(tracerProperties.getSpanName());
        tracerProperties.getTags().forEach((key, value) -> builder.withTag(key, String.valueOf(value)));
        return builder;
    }

    public static void traceError(Span span, Exception e) {
        if (span != null && e != null) {
            span.setTag(Tags.ERROR, true);
            span.log(
                    Map.of(Fields.ERROR_KIND, e.getClass().getSimpleName(),
                            Fields.ERROR_OBJECT, e,
                            Fields.EVENT, "error",
                            Fields.MESSAGE, e.getMessage())
            );
        }
    }

    @Component
    @Getter
    public static class KafkaProducerTracer {
        public static Span createSpan(Event event, String topic) {
            // creating basic kafka tracing tags
            final var tags = EventTracer.kafkaTracingTags(topic, Tags.SPAN_KIND_PRODUCER, null);

            // initializing span
            final var tracerProperties = new TracerProperties(event.getName() + " sent", tags);
            final var span = EventTracer.createSpan(tracerProperties, false);

            // let's create a counter to manage a flag that will be passed as part of the baggageItems,
            // so we can limit the number of produced events
            final var counter = Optional.ofNullable(span.getBaggageItem("counter"));
            final var incrementCounter = counter
                    .map(s -> new AtomicInteger(Integer.parseInt(s)))
                    .orElseGet(() -> new AtomicInteger(0));
            ;

            //example of adding extra tags
            addTagsFromEvent(span, event);

            //example of setting baggage items
            span.setBaggageItem(event.getName(), String.format("sent at %s", event.getTime()));
            span.setBaggageItem("counter", String.valueOf(incrementCounter.incrementAndGet()));

            return span;
        }

        public Headers injectTracingContext(SpanContext spanContext) {
            final var headers = new RecordHeaders();
            GlobalTracer.get().inject(spanContext, TEXT_MAP, new HeadersInjectAdapter(headers));
            return headers;
        }

        private static class HeadersInjectAdapter implements TextMap {
            private final Headers headers;

            public HeadersInjectAdapter(Headers headers) {
                this.headers = headers;
            }

            @Override
            public Iterator<Map.Entry<String, String>> iterator() {
                throw new UnsupportedOperationException("iterator should never be used with Tracer.inject()");
            }

            @Override
            public void put(String key, String value) {
                headers.add(key, value.getBytes(StandardCharsets.UTF_8));
            }
        }

    }

    @Component
    public static class KafkaConsumerTracer {
        public <K, V> Span createSpan(ConsumerRecord<K, V> record) throws JsonProcessingException {
            // Creating tags
            final var tags = kafkaTracingTags(record.topic(), Tags.SPAN_KIND_CONSUMER, null);
            final var eventString = (String) record.value();
            final var mapper = new ObjectMapper();
            final var receivedEvent = mapper.readValue(eventString, Event.class);

            final var tracerProperties = new TracerProperties(receivedEvent.getName() + " received", tags);
            // Initializing span
            final var spanBuilder = createSpanBuilder(tracerProperties);

            Optional.ofNullable(TracingKafkaUtils.extractSpanContext(record.headers(), GlobalTracer.get()))
                    .ifPresent(spanContext -> {
                        spanBuilder.addReference(References.FOLLOWS_FROM, spanContext);
                    });

            return spanBuilder.start();
        }
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    public static final class TracerProperties {
        private String spanName;
        private Map<String, String> tags;
    }

    @Configuration
    static class TracerConfiguration {

        @Bean
        @ConditionalOnProperty(value = "opentracing.jaeger.enabled", havingValue = "false")
        public io.opentracing.Tracer tracer() {
            return NoopTracerFactory.create();
        }

    }
}

