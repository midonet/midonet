/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.jaxrs;

import java.lang.reflect.Type;

import javax.servlet.ServletContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.InjectableProvider;

@Provider
public class ConfigInjectableProvider
    implements InjectableProvider<Context, Type>, Injectable<AppConfig> {

    private final AppConfig config;

    public ConfigInjectableProvider(ServletContext servletContext)
    {
        config = new AppConfig(servletContext);
    }

    @Override
    public ComponentScope getScope() {
        return ComponentScope.Singleton;
    }

    @Override
    public AppConfig getValue() {
        return config;
    }

    @Override
    public Injectable<AppConfig> getInjectable(ComponentContext arg0, Context arg1, Type type) {

        if ( type.equals(AppConfig.class) ) {
            return this;
        }

        return null;
    }

}
