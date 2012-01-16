/*
 * @(#)AuthInjectableProvider        1.6 12/1/11
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.jaxrs;

import java.lang.reflect.Type;

import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;

import com.midokura.midolman.mgmt.auth.AuthChecker;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.state.StateAccessException;
import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.InjectableProvider;

/**
 * Authorizer injectable class.
 *
 * @version 1.6 11 Jan 2012
 * @author Ryu Ishimoto
 */
@Provider
public class AuthInjectableProvider implements
        InjectableProvider<Context, Type>, Injectable<Authorizer> {

    private Authorizer authorizer = null;
    private final DaoFactory daoFactory;

    public AuthInjectableProvider(DaoFactory daoFactory) {
        this.daoFactory = daoFactory;
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
                authorizer = new Authorizer(new AuthChecker(),
                        daoFactory.getTenantDao());
            } catch (StateAccessException e) {
                // Probably should refactor this part in the future.
                throw new RuntimeException("Could not initialize DAO.", e);
            }
        }
        return authorizer;
    }
}
