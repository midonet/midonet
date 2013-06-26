/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.version;

import com.google.inject.AbstractModule;

/**
 * Bindings specific to version in api.
 */
public class VersionModule extends AbstractModule {

    @Override
    protected void configure() {

        bind(VersionParser.class).asEagerSingleton();
    }
}
