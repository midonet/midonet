package com.midokura.util.eventloop.eventloop;

import java.util.PriorityQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.midokura.util.eventloop.eventloop.Reactor;

public class MockReactor implements Reactor {

    // public so that unittests can access.
    public PriorityQueue<DelayedCall> calls = new PriorityQueue<DelayedCall>();
    long currentTimeMillis;

    public class DelayedCall implements ScheduledFuture {
        public Runnable runnable;
        long executeAfterTimeMillis;
        boolean canceled;
        boolean completed;

        private DelayedCall(Runnable runnable, long delayMillis) {
            this.runnable = runnable;
            this.executeAfterTimeMillis = currentTimeMillis + delayMillis;
        }

        private void complete() {
            if (!canceled) {
                completed = true;

                runnable.run();
            }
        }

        @Override
        public long getDelay(TimeUnit arg0) {
            long delay = arg0.convert(
                             executeAfterTimeMillis - currentTimeMillis,
                             TimeUnit.MILLISECONDS);
            if (delay < 0) {
                return 0;
            }
            return delay;
        }

        @Override
        public int compareTo(Delayed arg0) {
            long diff = this.executeAfterTimeMillis -
                            ((DelayedCall) arg0).executeAfterTimeMillis;
            if (diff > 0) return 1;
            if (diff < 0) return -1;
            return 0;
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
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long arg0, TimeUnit arg1) throws InterruptedException,
                ExecutionException, TimeoutException {
            return null;
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
    public Future submit(Runnable runnable) {
        DelayedCall dc = new DelayedCall(runnable, 0);
        calls.add(dc);
        return dc;
    }

    @Override
    public ScheduledFuture schedule(Runnable runnable, long delay,
                                    TimeUnit unit) {
        DelayedCall dc = new DelayedCall(runnable,
                                 TimeUnit.MILLISECONDS.convert(delay, unit));
        calls.add(dc);
        return dc;
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

}
