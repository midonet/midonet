/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.jaxrs;

import java.lang.reflect.Type;

import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;

import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.DaoInitializationException;
import com.midokura.midolman.mgmt.data.DataStoreSelector;
import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.InjectableProvider;

@Provider
public class DataStoreInjectableProvider implements
        InjectableProvider<Context, Type>, Injectable<DaoFactory> {

    private DaoFactory daoFactory = null;
    private final DataStoreSelector selector;

    public DataStoreInjectableProvider(DataStoreSelector selector) {
        this.selector = selector;
    }

    @Override
    public ComponentScope getScope() {
        return ComponentScope.Singleton;
    }

    @Override
    public DaoFactory getValue() {
        if (daoFactory == null) {
            try {
                daoFactory = selector.getDaoFactory();
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
