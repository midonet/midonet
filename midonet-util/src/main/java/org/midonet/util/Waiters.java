package org.midonet.util;

import org.midonet.tools.timed.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.midonet.tools.timed.Timed.newTimedExecution;
import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * This class contains some utils to wait for a task to complete.
 */
public class Waiters {

    protected final static Logger log = LoggerFactory
            .getLogger(Waiters.class);

    public static <T> T waitFor(String what, Timed.Execution<T> assertion)
            throws Exception {
        return waitFor(what,
                TimeUnit.SECONDS.toMillis(10),
                TimeUnit.MILLISECONDS.toMillis(500),
                assertion);
    }

    public static <T> T waitFor(String what, long total, long between,
                                Timed.Execution<T> assertion)
            throws Exception {
        long start = System.currentTimeMillis();
        Timed.ExecutionResult<T> executionResult =
                newTimedExecution()
                        .until(total)
                        .waiting(between)
                        .execute(assertion);

        assertThat(
                String.format("The wait for: \"%s\" didn't complete successfully " +
                        "(waited %d seconds)", what,
                        (System.currentTimeMillis() - start) / 1000),
                executionResult.completed());

        return executionResult.result();
    }

    public static void sleepBecause(String condition, long seconds)
            throws InterruptedException {
        log.debug(
                format("Sleeping %d seconds because: \"%s\"", seconds, condition));

        TimeUnit.SECONDS.sleep(seconds);

        log.debug(
                format("Sleeping done: \"%s\"", condition));
    }
}
