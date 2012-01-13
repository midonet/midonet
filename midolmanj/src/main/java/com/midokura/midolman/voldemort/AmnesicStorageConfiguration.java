/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.voldemort;

import voldemort.server.VoldemortConfig;
import voldemort.store.StorageConfiguration;
import voldemort.store.StorageEngine;
import voldemort.utils.ByteArray;

/**
 * A storage engine that forgets items as time passes.
 *
 * 'amnesic.lifetime' in the properties will specify the minimum lifetime for
 * items in the amnesic store. If not specified, the default minimum lifetime
 * will be set to DEFAULT_LIFETIME. The number is specified in milliseconds.
 *
 * @author Yoo Chung
 */
public class AmnesicStorageConfiguration implements StorageConfiguration {

    /** Default type name for the store. */
    public static final String TYPE_NAME = "amnesic";

    /** Default minimum lifetime for items in milliseconds. */
    public static final long DEFAULT_LIFETIME = 60000L;

    /** Minimum lifetime for items in milliseconds. */
    private final long lifetime;

    /**
     * Construct storage configuration for amnesic storage engine.
     *
     * The 'amnesic.lifetime' property specifies a minimum lifetime for items.
     *
     * @param config
     */
    public AmnesicStorageConfiguration(VoldemortConfig config) {
        lifetime = config.getAllProps().getLong("amnesic.lifetime",
                DEFAULT_LIFETIME);
    }

    @Override
    public StorageEngine<ByteArray, byte[], byte[]> getStore(String name) {
        return new AmnesicStorageEngine<ByteArray, byte[], byte[]>(name,
                lifetime);
    }

    @Override
    public String getType() {
        return TYPE_NAME;
    }

    /** Return minimum lifetime for items in milliseconds. */
    public long getLifetime() {
        return lifetime;
    }

    @Override
    public void close() {
        // nothing to do
    }

}
