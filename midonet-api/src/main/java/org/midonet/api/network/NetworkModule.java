/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import com.google.inject.AbstractModule;
import org.midonet.api.network.validation.*;
import org.midonet.api.filter.validation.ChainNameConstraintValidator;
import org.midonet.cluster.DataClient;

/**
 *　Network module.
 */
public class NetworkModule extends AbstractModule {

    @Override
    protected void configure() {

        bind(ChainNameConstraintValidator.class).asEagerSingleton();

        requireBinding(DataClient.class);
        bind(RouteNextHopPortConstraintValidator.class).asEagerSingleton();

        bind(PortsLinkableConstraintValidator.class).asEagerSingleton();
        bind(PortIdValidator.class).asEagerSingleton();

    }

}
