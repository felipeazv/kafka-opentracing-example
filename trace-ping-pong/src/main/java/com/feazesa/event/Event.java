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

