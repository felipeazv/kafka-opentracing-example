package com.feazesa.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@AllArgsConstructor
@Getter
public abstract class Event {
    protected Instant time;
    protected String name;

    @Getter
    public static class Ping extends Event {
        public Ping(Instant time) {
            super(time, Ping.class.getSimpleName());
        }
    }

    @Getter
    public static class Pong extends Event {
        public Pong(Instant time) {
            super(time, Pong.class.getSimpleName());
        }
    }

}

