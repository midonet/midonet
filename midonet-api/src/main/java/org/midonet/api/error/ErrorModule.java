/*
* Copyright 2012 Midokura PTE LTD.
*/
package org.midonet.api.error;

import com.google.inject.AbstractModule;

/**
 * Bindings specific to error handling in api.
 */
public class ErrorModule extends AbstractModule {

    @Override
    protected void configure() {

        bind(ThrowableMapper.class).asEagerSingleton();

    }

}
