/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import java.util.HashSet;
import java.util.Set;
import javax.servlet.ServletContext;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;

import com.wordnik.swagger.jaxrs.ApiListingResourceJSON;

import com.midokura.midolman.mgmt.auth.AuthorizerSelector;
import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.DataStoreSelector;
import com.midokura.midolman.mgmt.data.dao.ApplicationDao;
import com.midokura.midolman.mgmt.rest_api.jaxrs.AuthInjectableProvider;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ConfigInjectableProvider;
import com.midokura.midolman.mgmt.rest_api.jaxrs.DataStoreInjectableProvider;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ThrowableMapper;
import com.midokura.midolman.mgmt.rest_api.jaxrs.WebApplicationExceptionMapper;
import com.midokura.midolman.mgmt.rest_api.jaxrs.WildCardJacksonJaxbJsonProvider;
import com.midokura.midolman.mgmt.rest_api.resources.ApplicationResource;
import com.midokura.midolman.state.StateAccessException;

/**
 * Jax-RS application class.
 */
public class RestApplication extends Application {

    private Set<Object> singletons;

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
        set.add(ApiListingResourceJSON.class);
        set.add(WebApplicationExceptionMapper.class);
        set.add(ThrowableMapper.class);
        return set;
    }

    /**
     * Get a set of root resource and provider instances.
     *
     * @return A list of singleton instances.
     */
    @Override
    public synchronized Set<Object> getSingletons() {
        if (null == singletons)
            singletons = initialize();

        return singletons;
    }

    private synchronized Set<Object> initialize() {
        ConfigInjectableProvider configProvider = new ConfigInjectableProvider(
                servletContext);

        AppConfig config = configProvider.getValue();

        DataStoreSelector dataStoreSelector = new DataStoreSelector(config);
        DataStoreInjectableProvider dataStoreProvider = new DataStoreInjectableProvider(
                dataStoreSelector);

        DaoFactory daoFactory = dataStoreProvider.getValue();

        // Since this should only get called once, let the application
        // initialize its data store here.
        try {
            ApplicationDao dao = daoFactory.getApplicationDao();
            dao.initialize();
        } catch (StateAccessException e) {
            throw new UnsupportedOperationException(
                    "Datastore could not be initialized due to data access error.",
                    e);
        }

        AuthorizerSelector authzSelector;
        try {
            authzSelector = new AuthorizerSelector(config,
                    daoFactory.getTenantDao());
        } catch (StateAccessException e) {
            throw new UnsupportedOperationException(
                    "TenantDao could not be instantiated for Authorizer.", e);
        }

        AuthInjectableProvider authProvider = new AuthInjectableProvider(
                authzSelector);

        Set<Object> singletons = new HashSet<Object>();
        singletons.add(configProvider);
        singletons.add(dataStoreProvider);
        singletons.add(authProvider);
        singletons.add(new WildCardJacksonJaxbJsonProvider());

        return singletons;
    }
}
