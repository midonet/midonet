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

package org.midonet.util;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
                Timed.newTimedExecution()
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
        log.debug(String.format("Sleeping %d seconds because: \"%s\"", seconds, condition));

        TimeUnit.SECONDS.sleep(seconds);

        log.debug(String.format("Sleeping done: \"%s\"", condition));
    }
}
