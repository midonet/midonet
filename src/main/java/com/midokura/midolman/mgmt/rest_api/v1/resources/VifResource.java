/*
 * @(#)VifResource        1.6 11/09/24
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.VifDataAccessor;
import com.midokura.midolman.mgmt.data.dto.Vif;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for VIFs.
 * 
 * @version 1.6 24 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/vifs")
public class VifResource extends RestResource {
    /*
     * Implements REST API endpoints for VIFs.
     */

    private final static Logger log = LoggerFactory
            .getLogger(VifResource.class);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(Vif vif, @Context UriInfo uriInfo)
            throws StateAccessException {
        VifDataAccessor dao = new VifDataAccessor(zookeeperConn,
                zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
        UUID id = null;
        try {
            id = dao.create(vif);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        URI uri = uriInfo.getBaseUriBuilder().path("vifs" + id).build();
        return Response.created(uri).build();
    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Vif get(@PathParam("id") UUID id) throws StateAccessException {
        VifDataAccessor dao = new VifDataAccessor(zookeeperConn,
                zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
        try {
            return dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
    }

    @DELETE
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") UUID id)
            throws StateAccessException {
        VifDataAccessor dao = new VifDataAccessor(zookeeperConn,
                zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
        try {
            dao.delete(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data", e);
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error", e);
            throw new UnknownRestApiException(e);
        }
        return Response.ok().build();
    }

}
