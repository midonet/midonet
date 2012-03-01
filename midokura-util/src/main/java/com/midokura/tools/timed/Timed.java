package com.midokura.tools.timed;

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

        long completeTimeout;
        long sleepTimeout;

        public Builder until(long millis) {
            this.completeTimeout = millis;
            return this;
        }

        public Builder waiting(long waitTime) {
            this.sleepTimeout = waitTime;
            return this;
        }

        public <T> Timed.ExecutionResult<T> execute(Execution<T> e)
            throws Exception {

            long start = System.currentTimeMillis();

            long delta = 0;
            do {

                Thread.sleep(sleepTimeout);

                e.run();
                if (e.isCompleted()) {
                    return new ExecutionResult<T>(e.isCompleted, e.getResult());
                }

                delta = System.currentTimeMillis() - start;
            } while (delta < completeTimeout);

            return new ExecutionResult<T>(false, null);
        }
    }

    public static abstract class Execution<T> {

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
