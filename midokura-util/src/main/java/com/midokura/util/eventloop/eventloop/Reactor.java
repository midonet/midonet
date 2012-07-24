/*
 * Copyright 2011 Midokura KK
 */
package com.midokura.util.eventloop.eventloop;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public interface Reactor {
    long currentTimeMillis();

    Future submit(Runnable runnable);

    ScheduledFuture schedule(Runnable runnable, long delay, TimeUnit unit);

}
