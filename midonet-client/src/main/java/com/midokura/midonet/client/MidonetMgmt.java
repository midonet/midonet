package com.midokura.midonet.client;

import com.midokura.midonet.client.dto.DtoApplication;
import com.midokura.midonet.client.resource.Application;
import com.midokura.midonet.client.resource.ResourceCollection;
import com.midokura.midonet.client.resource.Tenant;

import java.net.URI;

/**
 * User: tomoe
 * Date: 8/7/12
 * Time: 6:06 PM
 */
public class MidonetMgmt {

    private static final String DEFAULT_MIDONET_URI =
            "http://localhost:8080/midolmanj-mgmt";

    private final URI midonetUri;
    private final WebResource resource;
    private Application application;

    public MidonetMgmt(String midonetUriStr) {
        this.midonetUri = URI.create(midonetUriStr);
        resource = new WebResource(midonetUri);
    }

    public MidonetMgmt() {
        this(DEFAULT_MIDONET_URI);
    }

    public void enableLogging() {
        resource.enableLogging();
    }

    public void disableLogging() {
        resource.disableLogging();
    }

    /**
     * Returns a Tenant resource
     *
     * @return
     */
    public Tenant addTenant() {
        if (application == null) {
            DtoApplication dtoApplication = resource.get("",
                    DtoApplication.class, VendorMediaType.APPLICATION_JSON);
            application = new Application(resource, dtoApplication);
        }
        return application.addTenant();
    }

    /**
     * Returns array of Tenant objects found in the system.
     *
     * @return array of tenant objects
     */
    public ResourceCollection<Tenant> getTenants() {
        return application.getTenants();
    }
}
