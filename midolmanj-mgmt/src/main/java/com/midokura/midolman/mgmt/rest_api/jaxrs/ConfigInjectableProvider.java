/*
 * @(#)ConfigInjectableProvider        1.6 11/11/23
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.jaxrs;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.sun.jersey.api.core.HttpContext;
import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.server.impl.inject.AbstractHttpContextInjectable;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.InjectableProvider;

import javax.servlet.ServletContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;
import java.lang.reflect.Type;

@Provider
public class ConfigInjectableProvider
    implements InjectableProvider<Context, Type>, Injectable<AppConfig> {

    AppConfig config = null;

    public ConfigInjectableProvider(@Context ServletContext servletContext)
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
