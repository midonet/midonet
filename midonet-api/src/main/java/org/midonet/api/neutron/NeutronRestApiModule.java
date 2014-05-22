/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.neutron;

import com.google.inject.AbstractModule;
import com.google.inject.assistedinject.FactoryModuleBuilder;

/**
 * Guice module for Neutron REST API.
 */
public class NeutronRestApiModule extends AbstractModule {

    @Override
    protected void configure() {
        install(new FactoryModuleBuilder().build(NeutronResourceFactory.class));
        bind(NeutronResource.class);
    }
}
