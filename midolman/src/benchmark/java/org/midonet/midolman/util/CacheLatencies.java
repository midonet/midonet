/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.util;

import java.io.PrintStream;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;

import org.midonet.cache.Cache;
import org.midonet.midolman.CacheFactory;
import org.midonet.util.Percentile;

/**
 * Benchmark for measuring latencies in ephemeral stores.
 *
 * @author Yoo Chung
 */
public class CacheLatencies {

    /** Whether to ignore the very first operations. */
    private boolean ignoreFirst = true;

    private Cache cache;

    /**
     * Construct the benchmark for the given ephemeral store.
     *
     * @param cache ephemeral store to benchmark
     */
    public CacheLatencies(Cache cache) {
        this.cache = cache;
    }

    /**
     * Set whether the very first operation in a sequence of operations should
     * be ignored when collecting timing measurements.
     *
     * @param flag true if first operation is to be ignored
     */
    public void setIgnoreFirst(boolean flag) {
        ignoreFirst = flag;
    }

    protected String formatPercentile(int pct, Percentile p) {
        assert pct >= 0 && pct < 100;

        double val = p.getPercentile((double)pct / 100.0) / 1000000.0;
        return String.format("%3d%% : %8.4f ms", pct, val);
    }

    /**
     * Output percentile distribution of timings.
     *
     * @param out output stream
     * @param p percentile distribution
     */
    public void outputDistribution(PrintStream out, Percentile p) {
        for (int pct = 10; pct < 100; pct += 10)
            out.println(formatPercentile(pct, p));

        out.println(formatPercentile(99, p));
        out.println(formatPercentile(100, p));
    }

    /**
     * Measure put latencies.
     *
     * @param n number of puts to try
     * @return distribution of latencies
     */
    public Percentile measurePutLatencies(int n) {
        Percentile p = new Percentile();

        if (ignoreFirst) {
            // call nanoTime() just to be consistent
            System.nanoTime();
            cache.set("key--", "value--");
            System.nanoTime();
        }

        for (int i = 0; i < n; i++) {
            String key = "key-" + i;
            String value = "value-" + i;

            long before = System.nanoTime();
            cache.set(key, value);
            long after = System.nanoTime();

            p.sample(after - before);
        }

        return p;
    }

    /**
     * Measure get latencies.
     *
     * @param n number of gets to try
     * @return distribution of latencies
     */
    public Percentile measureGetLatencies(int n) {
        Percentile p = new Percentile();

        if (ignoreFirst) {
            // call nanoTime() just to be consistent
            System.nanoTime();
            cache.get("key--");
            System.nanoTime();
        }

        for (int i = 0; i < n; i++) {
            String key = "key-" + i;

            long before = System.nanoTime();
            cache.get(key);
            long after = System.nanoTime();

            p.sample(after - before);
        }

        return p;
    }

    /**
     * Measure refresh latencies.
     *
     * @param n number of get and touches to try
     * @return distribution of latencies
     */
    public Percentile measureRefreshLatencies(int n) {
        Percentile p = new Percentile();

        if (ignoreFirst) {
            // call nanoTime() just to be consistent
            System.nanoTime();
            cache.getAndTouch("key--");
            System.nanoTime();
        }

        for (int i = 0; i < n; i++) {
            String key = "key-" + i;

            long before = System.nanoTime();
            cache.getAndTouch(key);
            long after = System.nanoTime();

            p.sample(after - before);
        }

        return p;
    }

    public static void main(String[] args) throws Exception {
        // TODO use command-line options instead of hardcoding

        int n = 10000;

        HierarchicalConfiguration config =
            new HierarchicalINIConfiguration("./conf/midolman.conf");
        Cache cache = CacheFactory.create(config, "benchmark");
        CacheLatencies measure = new CacheLatencies(cache);
        measure.setIgnoreFirst(true);

        System.out.println("put latencies");
        measure.outputDistribution(System.out, measure.measurePutLatencies(n));
        System.out.println();
        System.out.println("get latencies");
        measure.outputDistribution(System.out, measure.measureGetLatencies(n));
        System.out.println();
        System.out.println("refresh latencies");
        measure.outputDistribution(System.out, measure.measureRefreshLatencies(n));
    }
}
