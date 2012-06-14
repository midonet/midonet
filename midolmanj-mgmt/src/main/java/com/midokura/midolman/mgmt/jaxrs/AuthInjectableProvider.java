/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.jaxrs;

import java.lang.reflect.Type;

import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;

import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.AuthorizerSelector;
import com.midokura.midolman.mgmt.config.InvalidConfigException;
import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.InjectableProvider;

/**
 * Authorizer injectable class.
 */
@Provider
public class AuthInjectableProvider implements
        InjectableProvider<Context, Type>, Injectable<Authorizer> {

    private Authorizer authorizer = null;
    private final AuthorizerSelector selector;

    public AuthInjectableProvider(AuthorizerSelector selector) {
        this.selector = selector;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.sun.jersey.spi.inject.InjectableProvider#getInjectable(com.sun.jersey
     * .core.spi.component.ComponentContext, java.lang.annotation.Annotation,
     * java.lang.Object)
     */
    @Override
    public Injectable<Authorizer> getInjectable(ComponentContext arg0,
            Context arg1, Type type) {
        if (type.equals(Authorizer.class)) {
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
    public Authorizer getValue() {
        if (authorizer == null) {
            try {
                authorizer = selector.getAuthorizer();
            } catch (InvalidConfigException e) {
                throw new UnsupportedOperationException(
                        "Could not instantiate and initialize Authorizer.", e);
            }
        }
        return authorizer;
    }
}
