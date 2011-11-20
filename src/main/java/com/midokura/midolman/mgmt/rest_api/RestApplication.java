/*
 * @(#)RestApplication        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import java.util.HashSet;
import java.util.Set;

import javax.ws.rs.core.Application;

import com.midokura.midolman.mgmt.auth.UnauthorizedExceptionMapper;
import com.midokura.midolman.mgmt.data.DataStoreInjectableProvider;
import com.midokura.midolman.mgmt.rest_api.jaxrs.WildCardJacksonJaxbJsonProvider;
import com.midokura.midolman.mgmt.rest_api.resources.AdRouteResource;
import com.midokura.midolman.mgmt.rest_api.resources.ApplicationResource;
import com.midokura.midolman.mgmt.rest_api.resources.BgpResource;
import com.midokura.midolman.mgmt.rest_api.resources.ChainResource;
import com.midokura.midolman.mgmt.rest_api.resources.InvalidStateOperationExceptionMapper;
import com.midokura.midolman.mgmt.rest_api.resources.RuleResource;
import com.midokura.midolman.mgmt.rest_api.resources.StateAccessExceptionMapper;
import com.midokura.midolman.mgmt.rest_api.resources.VpnResource;

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

    /**
     * Get a set of root resource and provider classes.
     * 
     * @return A list of Class objects.
     */
    @Override
    public Set<Class<?>> getClasses() {
        HashSet<Class<?>> set = new HashSet<Class<?>>();
        set.add(ApplicationResource.class);
        set.add(RuleResource.class);
        set.add(ChainResource.class);
        set.add(BgpResource.class);
        set.add(AdRouteResource.class);
        set.add(VpnResource.class);
        set.add(StateAccessExceptionMapper.class);
        set.add(InvalidStateOperationExceptionMapper.class);
        set.add(UnauthorizedExceptionMapper.class);
        set.add(DataStoreInjectableProvider.class);
        return set;
    }

    /**
     * Get a set of root resource and provider instances.
     * 
     * @return A list of singleton instances.
     */
    @Override
    public Set<Object> getSingletons() {
        HashSet<Object> singletons = new HashSet<Object>();
        singletons.add(new WildCardJacksonJaxbJsonProvider());
        return singletons;
    }
}
