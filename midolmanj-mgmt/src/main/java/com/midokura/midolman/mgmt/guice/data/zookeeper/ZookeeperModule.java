/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.data.zookeeper;

import com.google.inject.AbstractModule;
import com.midokura.midolman.guice.zookeeper.ZookeeperConnectionModule;
import com.midokura.midolman.state.ZkConnection;

/**
 * Zookeeper module
 */
public class ZookeeperModule extends AbstractModule {

    @Override
    protected void configure() {

        install(new ZookeeperConnectionModule());


    }

}
