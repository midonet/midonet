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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import rx.Scheduler;
import rx.schedulers.Schedulers;


/**
 * Reactor implementation that catches, logs, and discards any exceptions thrown
 * by scheduled or submitted work items.
 */
public class TryCatchReactor implements Reactor {

    private static final Logger log = LoggerFactory
        .getLogger(TryCatchReactor.class);

    ScheduledThreadPoolExecutor executor;

    public TryCatchReactor(final String identifier, Integer nOfThreads) {
        executor = new ScheduledThreadPoolExecutor(
            nOfThreads,
            new ThreadFactory() {
                private AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    int thread_id = counter.incrementAndGet();
                    Thread t = new Thread(r, identifier + "-" + thread_id);
                    t.setDaemon(true);
                    return t;
                }
            },
            new RejectedExecutionHandler() {
                @Override
                public void rejectedExecution(Runnable r,
                                              ThreadPoolExecutor executor) {
                    // Do nothing, as this was the result of a race with shutdown.
                }
            }
        );
    }

    /**
     * Wraps the provided Runnable in another Runnable that catches, logs, and
     * discards any exceptions thrown by run().
     * @param runnable Runnable to wrap.
     * @return Wrapper runnable.
     */
    private Runnable wrapRunnable(final Runnable runnable) {
        final long startTime;
        if (log.isDebugEnabled()) {
            startTime = System.currentTimeMillis();
        } else {
            startTime = 0;
        }
        return new Runnable() {
            @Override
            public void run() {
                long runTime = 0;
                if (log.isDebugEnabled()) {
                    runTime = System.currentTimeMillis();
                    log.debug("After {}ms on queue, running ({})",
                              runTime - startTime, runnable);
                }
                try {
                    runnable.run();
                } catch (Throwable tt) {
                    log.error("Reactor encountered Throwable", tt);
                } finally {
                    if (log.isDebugEnabled()) {
                        log.debug("Finished after {}ms, ({})",
                                  System.currentTimeMillis() - runTime,
                                  runnable);
                    }
                }
            }
        };
    }

    /**
     * Wraps the provided Callable in another Callable that catches, logs, and
     * discards any exceptions thrown by call().
     * @param callable Callable to wrap.
     * @return Wrapper callable.
     */
    private <V> Callable<V> wrapCallable(final Callable<V> callable) {
        final long startTime;
        if (log.isDebugEnabled()) {
            startTime = System.currentTimeMillis();
        } else {
            startTime = 0;
        }

        return new Callable<V>() {
            @Override
            public V call() throws Exception {
                long runTime = 0;
                if (log.isDebugEnabled()) {
                    runTime = System.currentTimeMillis();
                    log.debug("After {}ms on queue, running ({})",
                              runTime - startTime, callable);
                }

                try {
                    return callable.call();
                } catch (Throwable tt) {
                    log.error("Reactor encountered Throwable", tt);
                } finally {
                    if (log.isDebugEnabled()) {
                        log.debug("Finished after {}ms, ({})",
                                  System.currentTimeMillis() - runTime,
                                  callable);
                    }
                }
                return null;
            }
        };
    }

    private static String shutdownErrMsg =
        "Could not submit task {} for execution: underlying executor service " +
        "has been stopped.";

    @Override
    public Future<?> submit(final Runnable runnable) {
        if (!executor.isShutdown() && !executor.isTerminated()) {
            return executor.submit(wrapRunnable(runnable));
        } else {
            log.warn(shutdownErrMsg, runnable);
            return null;
        }
    }

    @Override
    public ScheduledFuture<?> schedule(final Runnable runnable,
                                       long delay, TimeUnit unit) {
        if (!executor.isShutdown() && !executor.isTerminated()) {
            return executor.schedule(wrapRunnable(runnable), delay, unit);
        } else {
            log.warn(shutdownErrMsg, runnable);
            return null;
        }
    }

    @Override
    public <V> Future<V> submit(final Callable<V> work) {
        if (!executor.isShutdown() && !executor.isTerminated()) {
            return executor.submit(wrapCallable(work));
        } else {
            log.warn(shutdownErrMsg, work);
            return null;
        }
    }

    @Override
    public <V> ScheduledFuture<V> schedule(final Callable<V> work,
                                           long delay, TimeUnit unit) {
        if (!executor.isShutdown() && !executor.isTerminated()) {
            return executor.schedule(wrapCallable(work), delay, unit);
        } else {
            log.warn(shutdownErrMsg, work);
            return null;
        }
    }

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public void shutDownNow() {
        executor.shutdownNow();
    }

    @Override
    public boolean isShutDownOrTerminated() {
        return executor.isShutdown() || executor.isTerminated();
    }

    @Override
    public Scheduler rxScheduler() {
        return Schedulers.from(executor);
    }
}
