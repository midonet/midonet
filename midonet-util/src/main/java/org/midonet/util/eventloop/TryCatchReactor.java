/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.util.eventloop;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TryCatchReactor implements Reactor {

    private static final Logger log = LoggerFactory
        .getLogger(TryCatchReactor.class);

    ScheduledExecutorService executor;

    public TryCatchReactor(final String identifier, Integer nOfThreads) {
        executor = Executors.newScheduledThreadPool(
            nOfThreads,
            new ThreadFactory() {
                private AtomicInteger counter = new AtomicInteger(0);
                public Thread newThread(Runnable r) {
                    int thread_id = counter.incrementAndGet();
                    return new Thread(r, identifier + "-" + thread_id);
                }
            }
        );
    }

    private Runnable wrapRunnable(final Runnable runnable) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    runnable.run();
                } catch (Throwable tt) {
                    log.error("Reactor encountered Throwable", tt);
                }
            }
        };
    }

    private <V> Callable<V> wrapCallable(final Callable<V> callable) {
        return new Callable<V>() {
            @Override
            public V call() throws Exception {
                try {
                    return callable.call();
                } catch (Throwable tt) {
                    log.error("Reactor encountered Throwable", tt);
                }
                return null;
            }
        };
    }


    @Override
    public Future<?> submit(final Runnable runnable) {
        if(!executor.isShutdown() && !executor.isTerminated()) {
            return executor.submit(wrapRunnable(runnable));
        } else {
            log.error("Couldn't execute task {}, executor has stopped", runnable);
            return null;
        }
    }

    @Override
    public ScheduledFuture<?> schedule(final Runnable runnable,
                                       long delay, TimeUnit unit) {
        if(!executor.isShutdown() && !executor.isTerminated()) {
            return executor.schedule(wrapRunnable(runnable), delay, unit);
        } else {
            log.error("Couldn't execute task {}, executor has stopped", runnable);
            return null;
        }
    }

    @Override
    public <V> Future<V> submit(final Callable<V> work) {
        if(!executor.isShutdown() && !executor.isTerminated()) {
            return executor.submit(wrapCallable(work));
        } else {
            log.error("Couldn't execute task {}, executor has stopped", work);
            return null;
        }
    }

    @Override
    public <V> ScheduledFuture<V> schedule(final Callable<V> work,
                                           long delay, TimeUnit unit) {
        if(!executor.isShutdown() && !executor.isTerminated()) {
            return executor.schedule(wrapCallable(work), delay, unit);
        } else {
            log.error("Couldn't execute task {}, executor has stopped", work);
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
        return (executor.isShutdown() || executor.isTerminated());
    }
}
