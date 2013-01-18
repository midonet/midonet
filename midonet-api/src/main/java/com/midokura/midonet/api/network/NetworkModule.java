/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network;

import com.google.inject.AbstractModule;
import com.midokura.midonet.api.network.validation.*;
import com.midokura.midonet.api.filter.validation.ChainNameConstraintValidator;
import com.midokura.midonet.cluster.DataClient;

/**
 *　Network module.
 */
public class NetworkModule extends AbstractModule {

    @Override
    protected void configure() {

        bind(BridgeNameConstraintValidator.class).asEagerSingleton();
        bind(ChainNameConstraintValidator.class).asEagerSingleton();

        requireBinding(DataClient.class);
        bind(RouteNextHopPortConstraintValidator.class).asEagerSingleton();

        bind(RouterNameConstraintValidator.class).asEagerSingleton();
        bind(PortsLinkableConstraintValidator.class).asEagerSingleton();
        bind(PortIdValidator.class).asEagerSingleton();

    }

}
