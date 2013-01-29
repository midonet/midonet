/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.validation;

import com.google.inject.AbstractModule;

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
