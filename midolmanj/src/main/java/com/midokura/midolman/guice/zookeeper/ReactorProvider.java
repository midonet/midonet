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

import com.midokura.util.eventloop.Reactor;

public class ReactorProvider implements Provider<Reactor> {

    @Override
    public Reactor get() {
        return new ScheduledReactor();
    }

    class ScheduledReactor implements Reactor {

        ScheduledExecutorService executorService =
            Executors.newScheduledThreadPool(1);


        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public Future<?> submit(final Runnable runnable) {
            return submit(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    runnable.run();
                    return null;
                }
            });
        }

        @Override
        public <V> Future<V> submit(Callable<V> work) {
            return executorService.submit(work);
        }

        @Override
        public ScheduledFuture<?> schedule(final Runnable runnable, long delay,
                                           TimeUnit unit) {
            return schedule(
                new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        runnable.run();
                        return null;
                    }
                }, delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> work, long delay,
                                               TimeUnit unit) {
            return executorService.schedule(work, delay, unit);
        }
    }
}
