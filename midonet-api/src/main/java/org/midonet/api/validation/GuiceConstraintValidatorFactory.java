/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.validation;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorFactory;

@Singleton
public class GuiceConstraintValidatorFactory implements
        ConstraintValidatorFactory {

    private final Injector injector;

    @Inject
    public GuiceConstraintValidatorFactory(final Injector injector) {
        this.injector = injector;
    }

    @Override
    public <T extends ConstraintValidator<?, ?>> T getInstance(
            final Class<T> key) {
        return injector.getInstance(key);
    }

    public void releaseInstance(ConstraintValidator<?,?> instance) {}
}
