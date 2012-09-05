/*
* Copyright 2012 Midokura PTE LTD.
*/
package com.midokura.midolman.mgmt.validation;

import com.google.inject.Inject;
import com.google.inject.Provider;

import javax.validation.Configuration;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

/**
 *　Validator provider
 */
public class ValidatorProvider implements Provider<Validator> {

    private final GuiceConstraintValidatorFactory guiceValidatorFactory;

    @Inject
    public ValidatorProvider(
            GuiceConstraintValidatorFactory guiceValidatorFactory) {
        this.guiceValidatorFactory = guiceValidatorFactory;
    }

    @Override
    public Validator get() {
        // Create a custom validator and make it available for the
        // resources.
        Configuration<?> configuration = Validation.byDefaultProvider()
                .configure();
        ValidatorFactory factory = configuration
                .constraintValidatorFactory(guiceValidatorFactory)
                .buildValidatorFactory();
        return factory.getValidator();
    }

}
