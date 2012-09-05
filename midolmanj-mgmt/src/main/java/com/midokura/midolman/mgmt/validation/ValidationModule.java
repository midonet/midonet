/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.validation;

import com.google.inject.AbstractModule;
import com.midokura.midolman.mgmt.filter.validation.ChainNameConstraintValidator;
import com.midokura.midolman.mgmt.network.validation.BridgeNameConstraintValidator;
import com.midokura.midolman.mgmt.network.validation.RouteNextHopPortConstraintValidator;
import com.midokura.midolman.mgmt.network.validation.RouterNameConstraintValidator;

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

    }

}
