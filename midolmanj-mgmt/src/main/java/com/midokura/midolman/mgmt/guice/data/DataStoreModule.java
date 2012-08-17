/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.data;

import com.google.inject.AbstractModule;
import com.midokura.midolman.mgmt.guice.data.zookeeper.ZookeeperModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DataStore module
 */
public class DataStoreModule extends AbstractModule {

    private final static Logger log = LoggerFactory
            .getLogger(DataStoreModule.class);

    @Override
    protected void configure() {

        install(new ZookeeperModule());

    }
}
