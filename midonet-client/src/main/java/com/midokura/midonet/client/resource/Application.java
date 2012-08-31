/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package com.midokura.midonet.client.resource;

import com.midokura.midonet.client.VendorMediaType;
import com.midokura.midonet.client.WebResource;
import com.midokura.midonet.client.dto.DtoApplication;
import com.midokura.midonet.client.dto.DtoTenant;

import java.net.URI;

/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 8/15/12
 * Time: 12:31 PM
 */
public class Application extends ResourceBase<Application, DtoApplication> {

    DtoApplication app;
    WebResource resource;

    public Application(WebResource resource, DtoApplication app) {
        super(resource, null, app, VendorMediaType.APPLICATION_TENANT_JSON);
        this.app = app;
        this.resource = resource;
    }

    /**
     * Returns URI of the REST API for this resource
     *
     * @return uri of the resource
     */
    @Override
    public URI getUri() {
        return app.getUri();
    }

    /**
     * Returns version of the application
     *
     * @return version
     */
    public String getVersion() {
        get();
        return app.getVersion();
    }

    /**
     * Returns array of Tenant objects found in the system.
     *
     * @return Tenant object
     */
    public ResourceCollection<Tenant> getTenants() {

        return getChildResources(app.getTenants(),
                VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON,
                Tenant.class, DtoTenant.class);
    }

    /**
     * Returns a Tenant resource wrapper instance with newly created DtoTenant.
     *
     * @return
     */
    public Tenant addTenant() {
        return new Tenant(resource, app.getTenants(), new DtoTenant());
    }

    //TODO: Hosts
}
