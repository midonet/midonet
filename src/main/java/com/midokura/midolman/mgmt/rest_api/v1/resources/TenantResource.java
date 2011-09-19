/*
 * @(#)TenantResource        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.TenantDataAccessor;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.mgmt.rest_api.v1.resources.BridgeResource.TenantBridgeResource;
import com.midokura.midolman.mgmt.rest_api.v1.resources.RouterResource.TenantRouterResource;

/**
 * Root resource class for tenants.
 * 
 * @version 1.6 07 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/tenants")
public class TenantResource extends RestResource {
	/*
	 * Implements REST API endpoints for tenants.
	 */

	private final static Logger log = LoggerFactory
			.getLogger(TenantResource.class);

	/**
	 * Router resource locator for tenants
	 */
	@Path("/{id}/routers")
	public TenantRouterResource getRouterResource(@PathParam("id") UUID id) {
		return new TenantRouterResource(zookeeperConn, id);
	}

	/**
	 * Bridge resource locator for tenants
	 */
	@Path("/{id}/bridges")
	public TenantBridgeResource getBridgeResource(@PathParam("id") UUID id) {
		return new TenantBridgeResource(zookeeperConn, id);
	}

	/**
	 * Handler for create tenant API call.
	 * 
	 * @param tenant
	 *            Tenant object.
	 * @throws Exception 
	 * @returns Response object with 201 status code set if successful.
	 */
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response create(Tenant tenant) throws Exception {
		TenantDataAccessor dao = new TenantDataAccessor(zookeeperConn,
				zookeeperTimeout, zookeeperRoot,
				zookeeperMgmtRoot);
		UUID id = null;
		id = dao.create(tenant);
		try {
		} catch (Exception ex) {
			log.error("Error creating tenant", ex);
			throw new WebApplicationException(ex, Response.status(
					Response.Status.INTERNAL_SERVER_ERROR).type(
					MediaType.APPLICATION_JSON).build());
		}

		return Response.created(URI.create("/" + id)).build();
	}
}
