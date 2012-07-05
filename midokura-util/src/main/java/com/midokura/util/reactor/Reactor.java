/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.reactor;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * // TODO: Explain yourself.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 7/3/12
 */
public interface Reactor {

    long currentTimeMillis();

    <V> Future<V> submit(Callable<V> work);

    <V> ScheduledFuture<V> schedule(long delay, TimeUnit unit, Callable<V> work);
}
