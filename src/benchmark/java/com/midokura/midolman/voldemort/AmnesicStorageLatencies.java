package com.midokura.midolman.voldemort;

import com.midokura.util.Percentile;

import voldemort.client.ClientConfig;
import voldemort.client.SocketStoreClientFactory;
import voldemort.client.StoreClient;
import voldemort.client.StoreClientFactory;

public class AmnesicStorageLatencies {

    private static String format(double x) {
        return String.format("%.3f ms", x / 1000000.0);
    }

    public static void main(String[] args) {

        Percentile p;

        String bootstrapUrl = "tcp://localhost:6666";
        StoreClientFactory factory = new SocketStoreClientFactory(
                new ClientConfig().setBootstrapUrls(bootstrapUrl));

        StoreClient<String, String> client = factory.getStoreClient("test");

        p = new Percentile();
        for (int i = 0; i < 10000; i++) {
            long before = System.nanoTime();
            client.put("key-" + i, "value-" + i);
            long after = System.nanoTime();

            p.sample(after - before);
        }

        System.out.println("put latency");
        System.out.println(" 10% : " + format(p.getPercentile(0.1)));
        System.out.println(" 20% : " + format(p.getPercentile(0.2)));
        System.out.println(" 30% : " + format(p.getPercentile(0.3)));
        System.out.println(" 40% : " + format(p.getPercentile(0.4)));
        System.out.println(" 50% : " + format(p.getPercentile(0.5)));
        System.out.println(" 60% : " + format(p.getPercentile(0.6)));
        System.out.println(" 70% : " + format(p.getPercentile(0.7)));
        System.out.println(" 80% : " + format(p.getPercentile(0.8)));
        System.out.println(" 90% : " + format(p.getPercentile(0.9)));
        System.out.println("100% : " + format(p.getPercentile(1.0)));

        System.out.println();

        p = new Percentile();
        for (int i = 0; i < 10000; i++) {
            long before = System.nanoTime();
            client.get("key-" + i);
            long after = System.nanoTime();

            p.sample(after - before);
        }

        System.out.println("get latency");
        System.out.println(" 10% : " + format(p.getPercentile(0.1)));
        System.out.println(" 20% : " + format(p.getPercentile(0.2)));
        System.out.println(" 30% : " + format(p.getPercentile(0.3)));
        System.out.println(" 40% : " + format(p.getPercentile(0.4)));
        System.out.println(" 50% : " + format(p.getPercentile(0.5)));
        System.out.println(" 60% : " + format(p.getPercentile(0.6)));
        System.out.println(" 70% : " + format(p.getPercentile(0.7)));
        System.out.println(" 80% : " + format(p.getPercentile(0.8)));
        System.out.println(" 90% : " + format(p.getPercentile(0.9)));
        System.out.println("100% : " + format(p.getPercentile(1.0)));
    }

}
