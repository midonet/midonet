/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.neutron;

import com.google.inject.AbstractModule;
import com.google.inject.assistedinject.FactoryModuleBuilder;

import org.midonet.api.neutron.loadbalancer.LBResource;
import org.midonet.api.neutron.loadbalancer.LBResourceFactory;

/**
 * Guice module for Neutron REST API.
 */
public class NeutronRestApiModule extends AbstractModule {

    @Override
    protected void configure() {
        install(new FactoryModuleBuilder().build(NeutronResourceFactory.class));
        bind(NeutronResource.class);

        install(new FactoryModuleBuilder().build(LBResourceFactory.class));
        bind(LBResource.class);
    }
}
