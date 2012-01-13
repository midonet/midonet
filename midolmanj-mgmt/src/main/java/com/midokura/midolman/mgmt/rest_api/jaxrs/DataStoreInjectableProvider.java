/*
 * @(#)DataStoreInjectableProvider        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.jaxrs;

import java.lang.reflect.Type;

import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.DaoInitializationException;
import com.midokura.midolman.mgmt.data.DatastoreSelector;
import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.InjectableProvider;

@Provider
public class DataStoreInjectableProvider implements
        InjectableProvider<Context, Type>, Injectable<DaoFactory> {

    DaoFactory daoFactory = null;

    public DataStoreInjectableProvider() {
    }

    @Override
    public ComponentScope getScope() {
        return ComponentScope.Singleton;
    }

    @Context
    AppConfig appConfig;

    @Override
    public DaoFactory getValue() {
        if (daoFactory == null) {
            try {
                daoFactory = DatastoreSelector.getDaoFactory(appConfig);
            } catch (DaoInitializationException e) {
                throw new UnsupportedOperationException(
                        "Could not instantiate and initialize DaoFactory.", e);
            }
        }

        return daoFactory;
    }

    @Override
    public Injectable<DaoFactory> getInjectable(ComponentContext arg0,
            Context arg1, Type type) {
        if (type.equals(DaoFactory.class))
            return this;

        return null;
    }
}
