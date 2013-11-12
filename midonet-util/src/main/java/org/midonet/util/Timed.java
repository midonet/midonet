package org.midonet.util;

/**
 * This class allows one to wait for a task to get completed. It allows to
 * specify the total amount of time to wait, and the wait time between two executions.
 * It will run the job until the total time expired or if the job specifies that
 * the expected condition has been reached.
 * <p/>
 * Very useful when doing a busy wait type of thing on a generic condition.
 * For example one could wait for some routes to appear inside a midonet router
 * as a direct result of starting a bgp daemon because one port was configured
 * as a bgp endpoint.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 12/2/11
 */
public class Timed {

    public static Builder newTimedExecution() {
        return new Builder();
    }

    public static class Builder {

        long timeoutMillis;
        long waitingMillis;

        public Builder until(long millis) {
            this.timeoutMillis = millis;
            return this;
        }

        public Builder waiting(long waitingMillis) {
            this.waitingMillis = waitingMillis;
            return this;
        }

        public <T> Timed.ExecutionResult<T> execute(Execution<T> e)
            throws Exception {

            long start = System.currentTimeMillis();
            long remainingTime;

            do {
                e.setRemainingMillis(timeoutMillis - _passedSince(start));
                e.run();
                if (e.isCompleted()) {
                    return new ExecutionResult<T>(e.isCompleted, e.getResult());
                }

                remainingTime = timeoutMillis - _passedSince(start);
                if (remainingTime > 0 ) {
                    Thread.sleep(Math.min(waitingMillis,
                                          timeoutMillis - _passedSince(start)));
                }

            } while (_passedSince(start) < timeoutMillis);

            return new ExecutionResult<T>(false, null);
        }

        private long _passedSince(long start) {
            return System.currentTimeMillis() - start;
        }
    }

    public static abstract class Execution<T> {

        long remainingMillis;
        boolean isCompleted;
        T result;

        public final void run() throws Exception {
            setCompleted(false);
            setResult(null);

            _runOnce();
        }

        protected abstract void _runOnce() throws Exception;

        public boolean isCompleted() {
            return isCompleted;
        }

        protected void setCompleted(boolean completed) {
            isCompleted = completed;
        }

        public T getResult() {
            return result;
        }

        public void setResult(T result) {
            this.result = result;
        }

        public long getRemainingTime() {
            return remainingMillis;
        }

        public void setRemainingMillis(long remainingMillis) {
            this.remainingMillis = remainingMillis;
        }
    }

    public static class ExecutionResult<T> {

        boolean completed;
        T result;

        public ExecutionResult(boolean completed, T result) {
            this.completed = completed;
            this.result = result;
        }

        public boolean completed() {
            return completed;
        }

        public T result() {
            return result;
        }
    }
}
