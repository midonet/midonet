/*
 * @(#)ConfigInjectableProvider        1.6 11/11/23
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.jaxrs;

import java.lang.reflect.Type;

import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.config.InvalidConfigException;
import com.midokura.midolman.mgmt.data.DaoInitializationException;
import com.sun.jersey.api.core.HttpContext;
import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.server.impl.inject.AbstractHttpContextInjectable;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.InjectableProvider;

@Provider
public class ConfigInjectableProvider extends
        AbstractHttpContextInjectable<AppConfig> implements
        InjectableProvider<Context, Type> {

    AppConfig config = null;

    public ConfigInjectableProvider() throws InvalidConfigException,
            DaoInitializationException {
        config = AppConfig.getConfig();
    }

    @Override
    public ComponentScope getScope() {
        return ComponentScope.Singleton;
    }

    @Override
    public AppConfig getValue(HttpContext arg0) {
        return config;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Injectable getInjectable(ComponentContext arg0, Context arg1,
            Type type) {
        if (type.equals(AppConfig.class)) {
            return this;
        }
        return null;
    }

}
