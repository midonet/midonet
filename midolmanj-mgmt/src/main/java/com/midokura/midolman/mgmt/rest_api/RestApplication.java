/*
 * @(#)RestApplication        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;

import com.midokura.midolman.mgmt.rest_api.jaxrs.AuthInjectableProvider;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ConfigInjectableProvider;
import com.midokura.midolman.mgmt.rest_api.jaxrs.DataStoreInjectableProvider;
import com.midokura.midolman.mgmt.rest_api.jaxrs.InvalidStateOperationExceptionMapper;
import com.midokura.midolman.mgmt.rest_api.jaxrs.StateAccessExceptionMapper;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnauthorizedExceptionMapper;
import com.midokura.midolman.mgmt.rest_api.jaxrs.WildCardJacksonJaxbJsonProvider;
import com.midokura.midolman.mgmt.rest_api.resources.ApplicationResource;

/**
 * Jax-RS application class.
 *
 * @version 1.6 05 Sept 2011
 * @author Ryu Ishimoto
 */
public class RestApplication extends Application {
    /*
     * Override methods to initialize application.
     */

    /**
     * Default constructor
     */
    public RestApplication() {
    }

    @Context
    ServletContext servletContext;

    /**
     * Get a set of root resource and provider classes.
     *
     * @return A list of Class objects.
     */
    @Override
    public Set<Class<?>> getClasses() {
        HashSet<Class<?>> set = new HashSet<Class<?>>();
        set.add(ApplicationResource.class);
        set.add(StateAccessExceptionMapper.class);
        set.add(InvalidStateOperationExceptionMapper.class);
        set.add(UnauthorizedExceptionMapper.class);
        return set;
    }

    /**
     * Get a set of root resource and provider instances.
     *
     * @return A list of singleton instances.
     */
    @Override
    public Set<Object> getSingletons() {
        ConfigInjectableProvider configProvider = new ConfigInjectableProvider(
                servletContext);
        DataStoreInjectableProvider dataStoreProvider = new DataStoreInjectableProvider(
                configProvider.getValue());
        AuthInjectableProvider authProvider = new AuthInjectableProvider(
                dataStoreProvider.getValue());

        HashSet<Object> singletons = new HashSet<Object>();
        singletons.add(configProvider);
        singletons.add(dataStoreProvider);
        singletons.add(authProvider);
        singletons.add(new WildCardJacksonJaxbJsonProvider());
        return singletons;
    }
}
