/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.jaxrs.validation;

import com.google.inject.AbstractModule;
import com.midokura.midolman.mgmt.jaxrs.validation.GuiceConstraintValidatorFactory;
import com.midokura.midolman.mgmt.jaxrs.validation.constraint.*;

import javax.validation.Validator;

/**
 *　Validation module.
 */
public class ValidationModule extends AbstractModule {

    @Override
    protected void configure() {

        bind(Validator.class).toProvider(
                ValidatorProvider.class).asEagerSingleton();

        bind(GuiceConstraintValidatorFactory.class).asEagerSingleton();

        bind(AllowedValueConstraintValidator.class).asEagerSingleton();

        bind(BridgeNameConstraintValidator.class).asEagerSingleton();

        bind(ChainNameConstraintValidator.class).asEagerSingleton();

        bind(RouteNextHopPortConstraintValidator.class).asEagerSingleton();

        bind(RouterNameConstraintValidator.class).asEagerSingleton();

    }

}
