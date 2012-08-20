/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.data.zookeeper;

import com.midokura.midolman.guice.cluster.ClusterClientModule;

/**
 * Client cluster module used in mgmt.
 */
public class MgmtClusterClientModule extends ClusterClientModule {

    @Override
    protected void configure() {

        bindManagers();

    }

}
