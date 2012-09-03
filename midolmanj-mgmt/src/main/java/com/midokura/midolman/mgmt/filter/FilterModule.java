/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.filter;

import com.google.inject.AbstractModule;
import com.midokura.midolman.mgmt.filter.validation.ChainNameConstraintValidator;

/**
 *　Filter module.
 */
public class FilterModule extends AbstractModule {

    @Override
    protected void configure() {

        bind(ChainNameConstraintValidator.class).asEagerSingleton();

    }

}
