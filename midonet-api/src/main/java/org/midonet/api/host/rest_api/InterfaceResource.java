/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host.rest_api;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;

import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.host.Interface;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

@RequestScoped
public class InterfaceResource extends AbstractResource {

    private final UUID hostId;

    @Inject
    public InterfaceResource(RestApiConfig config, UriInfo uriInfo,
                             SecurityContext context,
                             DataClient dataClient,
                             @Assisted UUID hostId) {
        super(config, uriInfo, context, dataClient);
        this.hostId = hostId;
    }

    /**
     * Handler for listing all the interfaces.
     *
     * @return A list of Interface objects.
     * @throws StateAccessException  Data access error.
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON,
                  MediaType.APPLICATION_JSON})
    public List<Interface> list()
            throws StateAccessException, SerializationException {

        List<org.midonet.cluster.data.host.Interface> ifConfigs =
                dataClient.interfacesGetByHost(hostId);
        List<Interface> interfaces = new ArrayList<>();

        for (org.midonet.cluster.data.host.Interface ifConfig : ifConfigs) {
            Interface iface = new Interface(hostId, ifConfig);
            iface.setBaseUri(getBaseUri());
            interfaces.add(iface);
        }

        return interfaces;
    }

    /**
     * Handler to getting an interface.
     *
     * @param name       Interface name from the request.
     * @return An Interface object.
     * @throws StateAccessException  Data access error.
     */
    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Path("{name}")
    @Produces({VendorMediaType.APPLICATION_INTERFACE_JSON,
                  MediaType.APPLICATION_JSON})
    public Interface get(@PathParam("name") String name)
        throws StateAccessException, SerializationException {

        org.midonet.cluster.data.host.Interface ifaceConfig =
                dataClient.interfacesGet(hostId, name);

        if (ifaceConfig == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }
        Interface iface = new Interface(hostId, ifaceConfig);
        iface.setBaseUri(getBaseUri());

        return iface;
    }
}
