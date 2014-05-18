/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.neutron;

import com.google.inject.AbstractModule;

/**
 * Guice module for Neutron REST API.
 */
public class NeutronRestApiModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(NeutronResource.class);
    }
}
