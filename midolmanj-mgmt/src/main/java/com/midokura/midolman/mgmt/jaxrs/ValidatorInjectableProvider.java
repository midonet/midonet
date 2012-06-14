/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.jaxrs;

import java.lang.reflect.Type;

import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;

import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.InjectableProvider;

/**
 * Validator injectable class.
 */
@Provider
public class ValidatorInjectableProvider implements
        InjectableProvider<Context, Type>, Injectable<Validator> {

    private Validator validator = null;

    /*
     * (non-Javadoc)
     *
     * @see
     * com.sun.jersey.spi.inject.InjectableProvider#getInjectable(com.sun.jersey
     * .core.spi.component.ComponentContext, java.lang.annotation.Annotation,
     * java.lang.Object)
     */
    @Override
    public Injectable<Validator> getInjectable(ComponentContext arg0,
            Context arg1, Type type) {
        if (type.equals(Validator.class)) {
            return this;
        }
        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.sun.jersey.spi.inject.InjectableProvider#getScope()
     */
    @Override
    public ComponentScope getScope() {
        return ComponentScope.Singleton;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.sun.jersey.spi.inject.Injectable#getValue()
     */
    @Override
    public Validator getValue() {
        if (validator == null) {
            // In the future, we would want to specify the validator but for
            // now just use the default.
            ValidatorFactory factory = Validation
                    .buildDefaultValidatorFactory();
            validator = factory.getValidator();
        }
        return validator;
    }
}
