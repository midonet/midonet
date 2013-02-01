/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter;

import com.google.inject.AbstractModule;
import org.midonet.api.filter.validation.ChainNameConstraintValidator;

/**
 *　Filter module.
 */
public class FilterModule extends AbstractModule {

    @Override
    protected void configure() {

        bind(ChainNameConstraintValidator.class).asEagerSingleton();

    }

}
