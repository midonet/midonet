/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.util;

import java.util.concurrent.CyclicBarrier;

/**
 * This class implements an efficient multi-threaded counter. This is achieved
 * by segregating writes into independent per-thread counters that are
 * aggregated on reads. As such, it is not a precise counter.
 */
public class StatisticalCounter {
    // This is used to pad the counters so they fall on different cache lines,
    // to prevent those lines from ping-ponging across CPUs. We assume 64 byte
    // cache lines.
    private static final int PADDING = 8;

    private final long[] counters;

    public StatisticalCounter(int counters) {
        this.counters = new long[counters * PADDING];
    }

    public long incAndGet(int index) {
        return addAndGet(index, 1);
    }

    public long addAndGet(int index, int count) {
        return counters[index * PADDING] += count;
    }

    public long getValue() {
        long sum = 0;
        for (int i = 0; i < counters.length; i += PADDING) {
            sum += counters[i];
        }
        return sum;
    }

    /*
    public static void main(String[] args) {
        final long WARMUP_ITERATIONS = 100L * 1000L;
        final long ITERATIONS = 125L * 1000L * 1000L;
        final int NUM_THREADS = 4;

        executeTest(WARMUP_ITERATIONS, NUM_THREADS);

        final long start = System.nanoTime();
        long res = executeTest(ITERATIONS, NUM_THREADS);
        final long duration = System.nanoTime() - start;

        System.out.printf("%d threads, duration %,d (ns)\n",
                          NUM_THREADS, duration);
        System.out.printf("counter = %d\n", res);
        System.out.printf("%,d ns/op\n", duration / (ITERATIONS * NUM_THREADS));
        System.out.printf("%,d ops/s\n",
                          (ITERATIONS * NUM_THREADS * 1000000000L) / duration);
    }

    private static long executeTest(final long iterations, int numThreads) {
        final StatisticalCounter counter = new StatisticalCounter(numThreads);
        Thread[] ts = new Thread[numThreads];
        final CyclicBarrier barrier = new CyclicBarrier(numThreads);

        for (int i = 0; i < ts.length; ++i) {
            final int c = i;
            ts[i] = new Thread() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        // do nothing
                    }

                    for (int i = 0; i < iterations; ++i) {
                        counter.incAndGet(c);
                        if ((i % 1000) == c) {
                            Thread.yield();
                        }
                    }
                }
            };
        }

        for (Thread t : ts) {
            t.start();
        }

        for (Thread t : ts) {
            try {
                t.join();
            } catch (Exception e) {
                // do nothing
            }
        }

        return counter.getValue();
    }
    */
}
