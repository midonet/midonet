// Copyright 2013 Midokura Inc.

package org.midonet.util.throttling;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A ThrottlingGuard that drops tokens randomly when they fall between
 * a low and a high water mark. Likelihood of dropping is directly
 * proportional to how close to the high water mark is the number of
 * tokens currently in the system.
 */
public class RandomEarlyDropThrottlingGuard implements ThrottlingGuard {
    private final ThrottlingCounter counter;
    private final int highWaterMark;
    private final int lowWaterMark;
    private final AtomicLong droppedTokens = new AtomicLong();
    private final AtomicLong allowedTokens = new AtomicLong();
    private final String name;
    private final Logger logger = LoggerFactory.getLogger(
            RandomEarlyDropThrottlingGuard.class);


    public RandomEarlyDropThrottlingGuard(String name, ThrottlingCounter c,
                                          int highWaterMark, int lowWaterMark) {
        this.name = name;
        this.counter = c;
        this.lowWaterMark = lowWaterMark;
        if (highWaterMark < lowWaterMark)
            this.highWaterMark = lowWaterMark;
        else
            this.highWaterMark = highWaterMark;
    }

    @Override
    public void tokenIn() {
        counter.tokenIn();
    }

    @Override
    public int numTokens() {
        return counter.get();
    }

    @Override
    public boolean tokenInIfAllowed() {
        counter.tokenIn();
        if (allowed()) {
            return true;
        } else {
            counter.tokenOut();
            return false;
        }
    }

    @Override
    public boolean allowed() {
        final int n = counter.get();
        if (highWaterMark <= 0) {
            tokenAllowed();
            return true;
        }
        if (n <= lowWaterMark) {
            tokenAllowed();
            return true;
        }
        if (n > highWaterMark) {
            tokenDropped();
            return false;
        }
        final double likelihood = ((double) (n - lowWaterMark)) /
                                  ((double) (highWaterMark - lowWaterMark));
        final Object[] objs = {likelihood, lowWaterMark, highWaterMark, n};
        if (likelihood < Math.random()) {
            tokenAllowed();
            return true;
        } else {
            tokenDropped();
            return false;
        }
    }

    @Override
    public void tokenOut() {
        counter.tokenOut();
    }

    private void tokenDropped() {
        final long tokens = droppedTokens.incrementAndGet();
        if (tokens % 1000 == 0) {
            logger.info("{} dropped 1000 tokens ({} tokens in the system)",
                        this.name, counter.get());
        }
    }

    private void tokenAllowed() {
        allowedTokens.incrementAndGet();
    }

    @Override
    public long numAllowedTokens() {
        return allowedTokens.get();
    }

    @Override
    public long numDroppedTokens() {
        return droppedTokens.get();
    }
}
