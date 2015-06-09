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

import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Scheduler;
import rx.schedulers.Schedulers;

public class MockReactor implements Reactor {

    private static final Logger log = LoggerFactory
        .getLogger(MockReactor.class);

    // public so that unittests can access.
    /* DelayedCall is parametrized to Any since the reactor can accept any
     * typed Callable */
    public PriorityQueue<DelayedCall<?>> calls = new PriorityQueue<>();
    long currentTimeMillis;

    public class DelayedCall<V> implements ScheduledFuture<V> {
        public Callable<V> callable;
        long executeAfterTimeMillis;
        boolean canceled;
        boolean completed;

        V value;

        private DelayedCall(Callable<V> callable, long delayMillis) {
            this.callable = callable;
            this.executeAfterTimeMillis = currentTimeMillis + delayMillis;
        }

        private void complete() {
            if (!canceled) {
                completed = true;
                try {
                    value = callable.call();
                } catch (Exception e) {
                    log.error("Exception completing work.", e);
                }
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long delay =
                unit.convert(executeAfterTimeMillis - currentTimeMillis,
                             TimeUnit.MILLISECONDS);

            return delay < 0 ? 0 : delay;
        }

        @Override
        public int compareTo(Delayed arg0) {
            long diff = this.executeAfterTimeMillis -
                ((DelayedCall) arg0).executeAfterTimeMillis;
            return
                diff > 0
                    ? 1
                    : diff < 0 ? -1 : 0;
        }

        @Override
        public boolean cancel(boolean arg0) {
            if (completed) {
                return false;
            }

            canceled = true;

            return true;
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return value;
        }

        @Override
        public V get(long arg0, TimeUnit arg1) throws InterruptedException,
                                                      ExecutionException,
                                                      TimeoutException {
            return value;
        }

        @Override
        public boolean isCancelled() {
            return canceled;
        }

        @Override
        public boolean isDone() {
            return completed;
        }
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
        DelayedCall<V> dc = new DelayedCall<V>(work, 0);
        calls.add(dc);
        return dc;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> work, long delay, TimeUnit unit) {
        DelayedCall<V> dc =
            new DelayedCall<V>(work,
                               TimeUnit.MILLISECONDS.convert(delay, unit));
        calls.add(dc);
        return dc;
    }

    @Override
    public ScheduledFuture<?> schedule(final Runnable runnable, long delay, TimeUnit unit) {
        return schedule(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                runnable.run();
                return null;
            }
        }, delay, unit);
    }

    public void incrementTime(long interval, TimeUnit unit) {
        long intervalMillis = TimeUnit.MILLISECONDS.convert(interval, unit);
        currentTimeMillis += intervalMillis;

        while (!calls.isEmpty() &&
            calls.peek().executeAfterTimeMillis <= currentTimeMillis) {
            calls.remove().complete();
        }
    }

    @Override
    public long currentTimeMillis() {
        return currentTimeMillis;
    }

    @Override
    public void shutDownNow() {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public boolean isShutDownOrTerminated() {
        return false;
    }

    @Override
    public Scheduler rxScheduler() {
        return Schedulers.trampoline();
    }
}
