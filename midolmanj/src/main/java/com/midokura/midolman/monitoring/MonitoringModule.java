/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import me.prettyprint.hector.api.exceptions.HectorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.monitoring.store.CassandraStore;
import com.midokura.midolman.monitoring.store.Store;

/**
 * Date: 4/25/12
 */
public class MonitoringModule extends AbstractModule {
    private final static Logger log =
            LoggerFactory.getLogger(MonitoringModule.class);
    static String zkJMXUrl;
    int replicationFactor;
    int ttlInSecs;

    @Override
    protected void configure() {
        //TODO(rossella) take this configuration from conf file, waiting for the
        //Mihai's changes to be in master
        //TODO(rossella) check if there's a local ZK
        zkJMXUrl = "service:jmx:rmi:///jndi/rmi://localhost:12122/jmxrmi";
        replicationFactor = 1;
        ttlInSecs = 1000;

        bind(String.class)
                .annotatedWith(
                        Names.named(
                                MonitoringAgent.ZKJMXPATH))
                .toInstance(zkJMXUrl);
    }

    @Singleton
    @Provides
    public Store getStore() {
        // TODO get the data from the configuration
        Store store = null;
        try {
            store = new CassandraStore("localhost:9162",
                                       "Mido Cluster",
                                       "MM_Monitoring",
                                       "TestColumnFamily",
                                       replicationFactor, ttlInSecs);
        } catch (HectorException e) {
            log.error("Fatal error, enable to initialize CassandraStore", e);
        }
        return store;
    }
}
