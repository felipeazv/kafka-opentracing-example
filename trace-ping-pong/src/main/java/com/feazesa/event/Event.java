package com.feazesa.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

import static com.feazesa.event.Event.Ping;
import static com.feazesa.event.Event.Pong;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Ping.class, name = "ping"),
        @JsonSubTypes.Type(value = Pong.class, name = "pong")
})
@Getter
@NoArgsConstructor
public abstract class Event {
    private String time;
    private String name;

    protected Event(String time, String name) {
        this.time = time;
        this.name = name;
    }

    @AllArgsConstructor
    @Getter
    public static class Ping extends Event {
        public Ping(String time) {
            super(time, Ping.class.getSimpleName());
        }
    }

    @AllArgsConstructor
    @Getter
    public static class Pong extends Event {
        public Pong(String time) {
            super(time, Pong.class.getSimpleName());
        }
    }

}

