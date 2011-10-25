package com.midokura.midolman.mgmt.data;

import java.lang.reflect.Type;

import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;

import com.midokura.midolman.mgmt.config.InvalidConfigException;
import com.sun.jersey.api.core.HttpContext;
import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.server.impl.inject.AbstractHttpContextInjectable;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.InjectableProvider;


@Provider
public class DataStoreInjectableProvider extends
        AbstractHttpContextInjectable<DaoFactory> implements
        InjectableProvider<Context, Type> {

    DaoFactory daoFactory = null;

    public DataStoreInjectableProvider() throws InvalidConfigException {
        this.daoFactory = DatastoreSelector.getDaoFactory();
    }

    @Override
    public ComponentScope getScope() {
        return ComponentScope.Singleton;
    }

    @Override
    public DaoFactory getValue(HttpContext arg0) {
        return daoFactory;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Injectable getInjectable(ComponentContext arg0, Context arg1,
            Type type) {
        if (type.equals(DaoFactory.class)) {
            return this;
        }
        return null;
    }

}
