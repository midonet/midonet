package com.midokura.midolman.util;

import java.util.List;

import voldemort.client.ClientConfig;
import voldemort.client.SocketStoreClientFactory;
import voldemort.client.StoreClient;
import voldemort.client.StoreClientFactory;

/**
 * A cache-like interface to a Voldemort store which expires items.
 *
 * @author Yoo Chung
 */
public class VoldemortCache implements Cache {

    /** The client to the Voldemort servers. */
    private StoreClient<String, String> client;

    /**
     * What is thought to be the minimum lifetime of items in the Voldemort
     * store.  Be cautioned that the client does not actually control the
     * lifetime, with the servers being in control.  The lifetime must be
     * obtained through some other channel, which can then be used to inform
     * others what the lifetime is.
     */
    private int lifetime;

    /**
     * Construct a cache-like interface to Voldemort stores which server as
     * an expiring store of key-value items.  The lifetime is descriptive,
     * not prescriptive, so the value used by the servers must be obtained
     * through a separate channel.
     *
     * The list of URLs are potential entry points for the client to connect
     * to the Voldemort store.  The list may include all Voldemort servers in
     * the cluster, or it may include only a subset.
     *
     * @param name the name of the store
     * @param lifetime minimum lifetime of items in store
     * @param urls bootstrap URLs to store
     */
    public VoldemortCache(String name, int lifetime, List<String> urls) {
        ClientConfig config = new ClientConfig();
        config.setBootstrapUrls(urls);

        StoreClientFactory factory = new SocketStoreClientFactory(config);
        this.client = factory.getStoreClient(name);
        this.lifetime = lifetime;
    }

    @Override
    public void set(String key, String value) {
        client.put(key, value);
    }

    @Override
    public String get(String key) {
        return client.getValue(key);
    }

    @Override
    public String getAndTouch(String key) {
        String val = client.getValue(key);
        if (val != null)
            client.put(key, val);

        return val;
    }

    @Override
    public int getExpirationSeconds() {
        return lifetime;
    }

}
