/*
 * @(#)RestApplication        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1;

import java.util.HashSet;
import java.util.Set;

import javax.ws.rs.core.Application;

import org.codehaus.jackson.jaxrs.JacksonJaxbJsonProvider;

import com.midokura.midolman.mgmt.rest_api.v1.resources.AdRouteResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.AdminResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.BgpResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.BridgeResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.ChainResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.InvalidStateOperationExceptionMapper;
import com.midokura.midolman.mgmt.rest_api.v1.resources.PortResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.RouteResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.RouterResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.RuleResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.StateAccessExceptionMapper;
import com.midokura.midolman.mgmt.rest_api.v1.resources.TenantResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.VifResource;

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
        set.add(PortResource.class);
        set.add(RuleResource.class);
        set.add(RouteResource.class);
        set.add(RouterResource.class);
        set.add(TenantResource.class);
        set.add(ChainResource.class);
        set.add(BridgeResource.class);
        set.add(BgpResource.class);
        set.add(AdRouteResource.class);
        set.add(VifResource.class);
        set.add(AdminResource.class);
        set.add(StateAccessExceptionMapper.class);
        set.add(InvalidStateOperationExceptionMapper.class);
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
        singletons.add(new JacksonJaxbJsonProvider());
        return singletons;
    }
}
