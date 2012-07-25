/*
 * Copyright 2011 Midokura KK
 */
package com.midokura.util.eventloop;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public interface Reactor {

    long currentTimeMillis();

    Future<?> submit(Runnable runnable);

    <V> Future<V> submit(Callable<V> work);

    ScheduledFuture<?> schedule(Runnable runnable, long delay, TimeUnit unit);

    <V> ScheduledFuture<V> schedule(Callable<V> work, long delay, TimeUnit unit);
}
