/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.util.eventloop;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Interface for submitting and executing Runnable and Callable tasks.
 */
public interface Reactor {

    /**
     * Returns the "current time" in milliseconds. Depending on implementation,
     * this may or may not be the actual current time.
     */
    long currentTimeMillis();

    /**
     * Submits a Runnable for execution as soon as possible.
     * @param runnable Runnable to execute.
     * @return Future indicating execution status.
     */
    Future<?> submit(Runnable runnable);

    /**
     * Submits a callable for execution as soon as possible.
     * @param work Callable to execute.
     * @param <V> Callable's return type.
     * @return Future indicating status and results of execution.
     */
    <V> Future<V> submit(Callable<V> work);

    /**
     * Submits a Runnable for execution after a a specified delay.
     * @param runnable Runnable to execute.
     * @param delay Milliseconds to delay execution.
     * @return Future indicating execution status.
     */
    ScheduledFuture<?> schedule(Runnable runnable, long delay, TimeUnit unit);

    /**
     * Submits a callable for execution after a specified delay.
     * @param work Callable to execute.
     * @param delay Milliseconds to delay execution.
     * @param <V> Callable's return type.
     * @return Future indicating status and results of execution.
     */
    <V> ScheduledFuture<V> schedule(Callable<V> work, long delay, TimeUnit unit);

    /**
     * Cancels all pending tasks and attempts to stop any running tasks.
     */
    void shutDownNow();

    /**
     * Returns true if the reactor has been shut down or terminated.
     */
    boolean isShutDownOrTerminated();
}
