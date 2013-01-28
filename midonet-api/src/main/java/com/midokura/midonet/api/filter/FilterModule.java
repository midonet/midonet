/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.filter;

import com.google.inject.AbstractModule;
import com.midokura.midonet.api.filter.validation.ChainNameConstraintValidator;

/**
 *　Filter module.
 */
public class FilterModule extends AbstractModule {

    @Override
    protected void configure() {

        bind(ChainNameConstraintValidator.class).asEagerSingleton();

    }

}
