package com.midokura.midolman.util;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.HierarchicalConfiguration;

import com.midokura.util.Percentile;

/**
 * Benchmark for measuring latencies in ephemeral stores.
 *
 * @author Yoo Chung
 */
public class CacheLatencies {

    /** Whether to ignore the very first operations. */
    private boolean ignoreFirst = true;

    private Cache cache;

    /** Factories for producing Cache objects. */
    private static Map<String, CacheFactory> factories =
            new HashMap<String, CacheFactory>();

    /**
     * Exception raised when a CacheFactory cannot create a Cache object.
     */
    @SuppressWarnings("serial")
    public static class CreateException extends Exception {
        public CreateException(Exception e) {
            super(e);
        }
    }

    /**
     * Constructs Cache objects for benchmark purposes.
     */
    protected static abstract class CacheFactory {

        /**
         * Short string denoting ephemeral store type.
         * Needs to be unique among CacheFactory classes.
         */
        public abstract String typeName();

        /**
         * Create a Cache object to be benchmarked.  The Cache object is
         * set up according to the given configuration, which should use
         * the same properties as is expected during normal operation.
         * It is alright for benchmark-specific properties to be defined,
         * however.
         *
         * @param config configuration for the Cache object
         * @return a Cache object
         * @throws CreateException if an error occurs
         */
        public abstract Cache create(Configuration config)
                throws CreateException;

    }

    /**
     * Registers a CacheFactory.
     *
     * @param c class of the CacheFactory
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    public static void registerFactory(Class<? extends CacheFactory> c)
            throws InstantiationException, IllegalAccessException {
        CacheFactory f = c.newInstance();
        factories.put(f.typeName(), f);
    }

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
        registerFactory(VoldemortCacheFactory.class);
        registerFactory(MemcacheCacheFactory.class);

        // TODO use command-line options instead of hardcoding

        int n = 10000;

        Configuration config = new HierarchicalConfiguration();

        config.setProperty("voldemort.store", "latency");
        config.setProperty("voldemort.lifetime", "60000");
        config.setProperty("voldemort.servers", "tcp://localhost:6666");

        config.setProperty("memcache.memcache_hosts", "127.0.0.1:11211");

        Cache cache = factories.get("voldemort").create(config);
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

    protected static class VoldemortCacheFactory extends CacheFactory {

        @Override
        public String typeName() {
            return "voldemort";
        }

        @Override
        public Cache create(Configuration config) {
            String store = config.getString("voldemort.store");
            String servers = config.getString("voldemort.servers");
            long lifetime = config.getLong("voldemort.lifetime");

            List<String> urls = Arrays.asList(servers.split(","));

            return new VoldemortCache(store, (int)(lifetime / 1000), urls);
        }

    }

    protected static class MemcacheCacheFactory extends CacheFactory {

        @Override
        public String typeName() {
            return "memcache";
         }

        @Override
        public Cache create(Configuration config) throws CreateException {
            try {
                String hosts = config.getString("memcache.memcache_hosts");
                return new MemcacheCache(hosts, 60);
            } catch (Exception e) {
                throw new CreateException(e);
            }
        }

    }

}
