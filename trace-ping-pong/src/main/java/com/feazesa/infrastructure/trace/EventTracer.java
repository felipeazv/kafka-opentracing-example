package com.feazesa.infrastructure.trace;

import com.feazesa.event.Event;
import io.jaegertracing.internal.JaegerTracer;
import io.opentracing.*;
import io.opentracing.contrib.java.spring.jaeger.starter.TracerBuilderCustomizer;
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
import nl.talsmasoftware.context.opentracing.ContextScopeManager;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

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
        public Span createSpan(Event event, String topic) {
            // creating basic kafka tracing tags
            final var tags = EventTracer.kafkaTracingTags(topic, Tags.SPAN_KIND_PRODUCER, null);

            // initializing span
            final var tracerProperties = new TracerProperties("Ping sent", tags);
            final var span = EventTracer.createSpan(tracerProperties, false);

            //example of adding extra tags
            addTagsFromEvent(span, event);

            //example of setting baggage items
            span.setBaggageItem(event.getName(), String.format("sent at %s", event.getTime().toString()));

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
    public class KafkaConsumerTracer {
        public <K, V> Span createSpan(ConsumerRecord<K, V> record) {
            // creating tags
            final var tags = kafkaTracingTags(record.topic(), Tags.SPAN_KIND_CONSUMER, null);
            // initializing span
            final var tracerProperties = new TracerProperties("Ping received", tags);
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
    class TracerConfiguration {

        @Bean
        @ConditionalOnProperty(value = "opentracing.jaeger.enabled", havingValue = "false")
        public io.opentracing.Tracer tracer() {
            return NoopTracerFactory.create();
        }

        @Bean
        public TracerBuilderCustomizer threadContextScopeManager() {
            return new ThreadContextScopeManagerTracerBuilderCustomizer();
        }

        private class ThreadContextScopeManagerTracerBuilderCustomizer implements TracerBuilderCustomizer {
            @Override
            public void customize(JaegerTracer.Builder builder) {
                builder.withScopeManager(new ThreadContextScopeManager());
            }
        }
    }

    static class ThreadContextScopeManager extends ContextScopeManager {
        @Override
        public Scope activate(Span span) {
            return new ScopeWrapper(super.activate(span), span.context());
        }

        @Deprecated
        public Scope activate(Span span, boolean finishSpanOnClose) {
            return new ScopeWrapper(super.activate(span, finishSpanOnClose), span.context());
        }

        private static class ScopeWrapper implements Scope {
            private final Scope scope;
            private final String previousTraceId;
            private final String previousSpanId;

            ScopeWrapper(Scope scope, SpanContext context) {
                this.scope = scope;
                this.previousTraceId = lookup("traceId");
                this.previousSpanId = lookup("spanId");

                String traceId = context.toTraceId();
                String spanId = context.toSpanId();

                replace("traceId", traceId);
                replace("spanId", spanId);
            }

            @Override
            public void close() {
                this.scope.close();
                replace("traceId", previousTraceId);
                replace("spanId", previousSpanId);
            }
        }

        private static String lookup(String key) {
            return ThreadContext.get(key);
        }

        private static void replace(String key, String value) {
            if (value == null) {
                ThreadContext.remove(key);
            } else {
                ThreadContext.put(key, value);
            }
        }
    }

}

