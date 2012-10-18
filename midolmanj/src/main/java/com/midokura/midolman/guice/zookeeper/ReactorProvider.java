/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.guice.zookeeper;


import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.eventloop.Reactor;

public class ReactorProvider implements Provider<Reactor> {

    private static final Logger log = LoggerFactory
        .getLogger(ReactorProvider.class);

    @Override
    public Reactor get() {
        return new ScheduledReactor();
    }

    static class ScheduledReactor implements Reactor {
        private final Logger log = LoggerFactory
            .getLogger(ScheduledReactor.class);

        ScheduledExecutorService executorService =
            Executors.newScheduledThreadPool(1);


        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        private Callable<Object> wrapRunnable(final Runnable runnable) {
            return new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    try {
                        runnable.run();
                    } catch (Throwable tt) {
                        log.error("Reactor encountered Throwable", tt);
                    }
                    return null;
                }
            };
        }

        private <V> Callable<V> wrapCallable(final Callable<V> callable) {
            return new Callable<V>() {
                @Override
                public V call() throws Exception {
                    try {
                        callable.call();
                    } catch (Throwable tt) {
                        log.error("Reactor encountered Throwable", tt);
                    }
                    return null;
                }
            };
        }

        @Override
        public Future<?> submit(final Runnable runnable) {
            return executorService.submit(wrapRunnable(runnable));
        }

        @Override
        public <V> Future<V> submit(final Callable<V> work) {
            return executorService.submit(wrapCallable(work));
        }

        @Override
        public ScheduledFuture<?> schedule(final Runnable runnable, long delay,
                                           TimeUnit unit) {
            return executorService.schedule(
                wrapRunnable(runnable), delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> work, long delay,
                                               TimeUnit unit) {
            return executorService.schedule(
                wrapCallable(work), delay, unit);
        }
    }
}
