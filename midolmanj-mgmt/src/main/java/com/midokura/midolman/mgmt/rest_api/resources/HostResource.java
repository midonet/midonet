/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;
import java.util.UUID;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.HostDao;
import com.midokura.midolman.mgmt.data.dto.Host;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com> Date: 1/30/12
 */
public class HostResource {

    private final static Logger log = LoggerFactory
            .getLogger(HostResource.class);

    @GET
    @Produces({ VendorMediaType.APPLICATION_HOST_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Host> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws ForbiddenHttpException,
            StateAccessException {

        if (!authorizer.isAdmin(context)) {
            throw new ForbiddenHttpException("Not authorized to view hosts.");
        }

        HostDao dao = daoFactory.getHostDao();
        List<Host> hosts = dao.list();
        for (Host host : hosts) {
            host.setBaseUri(uriInfo.getBaseUri());
        }
        return hosts.size() > 0 ? hosts : null;
    }

    /**
     * Handler to getting a host information.
     *
     * @param id
     *            Host ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @return A Host object.
     * @throws StateAccessException
     *             Data access error.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_HOST_JSON,
            MediaType.APPLICATION_JSON })
    public Host get(@PathParam("id") UUID id, @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.isAdmin(context)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view the host information.");
        }

        HostDao dao = daoFactory.getHostDao();
        Host host = dao.get(id);
        if (host != null) {
            host.setBaseUri(uriInfo.getBaseUri());
        }
        return host;
    }

    /**
     * Handler to deleting a host.
     *
     * @param id
     *            Host ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @return Response object with 204 status code set if successful and 403 is
     *         the deletion could not be executed.
     * @throws StateAccessException
     *             Data access error.
     */
    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") UUID id,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException {

        if (!authorizer.isAdmin(context)) {
            throw new ForbiddenHttpException("Not authorized to delete a host.");
        }

        HostDao dao = daoFactory.getHostDao();
        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        } catch (StateAccessException e) {
            throw new ForbiddenHttpException();
        }

        return Response.noContent().build();
    }

    /**
     * Interface resource locator for hosts.
     *
     * @param hostId
     *            Host ID from the request.
     * @return InterfaceResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.INTERFACES)
    public InterfaceResource getInterfaceResource(@PathParam("id") UUID hostId) {
        return new InterfaceResource(hostId);
    }

    /**
     * Interface resource locator for hosts.
     *
     *
     * @param hostId
     *            Host ID from the request.
     * @return InterfaceResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.COMMANDS)
    public HostCommandResource getHostCommandsResource(
            @PathParam("id") UUID hostId) {
        return new HostCommandResource(hostId);
    }
}
