/*
 * @(#)BgpResource        1.6 11/09/05
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
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.BgpDataAccessor;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.mgmt.rest_api.v1.resources.AdRouteResource.BgpAdRouteResource;

/**
 * Root resource class for bgps.
 * 
 * @version 1.6 11 Sept 2011
 * @author Yoshi Tamura
 */
@Path("/bgps")
public class BgpResource extends RestResource {
	/*
	 * Implements REST API end points for bgps.
	 */

	private final static Logger log = LoggerFactory
			.getLogger(BgpResource.class);

	/**
	 * Advertising route resource locator for bgps
	 */
	@Path("/{id}/ad_routes")
	public BgpAdRouteResource getBgpAdRouteResource(@PathParam("id") UUID id) {
		return new BgpAdRouteResource(zookeeperConn, id);
	}

	/**
	 * Get the BGP with the given ID.
	 * 
	 * @param id
	 *            BGP UUID.
	 * @return Bgp object.
	 */
	@GET
	@Path("{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public Bgp get(@PathParam("id") UUID id) {
		// Get a bgp for the given ID.
		BgpDataAccessor dao = new BgpDataAccessor(zookeeperConn,
				zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
		Bgp bgp = null;
		try {
			bgp = dao.get(id);
		} catch (Exception ex) {
			log.error("Error getting bgp", ex);
			throw new WebApplicationException(Response.status(
					Response.Status.INTERNAL_SERVER_ERROR).type(
					MediaType.APPLICATION_JSON).build());
		}
		return bgp;
	}

	@PUT
	@Path("{id}")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response update(@PathParam("id") UUID id, Bgp bgp) {
		BgpDataAccessor dao = new BgpDataAccessor(zookeeperConn,
				zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
		try {
			dao.update(id, bgp);
		} catch (Exception ex) {
			log.error("Error updating bgp", ex);
			throw new WebApplicationException(Response.status(
					Response.Status.INTERNAL_SERVER_ERROR).type(
					MediaType.APPLICATION_JSON).build());
		}
		return Response.ok().build();
	}

	@DELETE
	@Path("{id}")
	public void delete(@PathParam("id") UUID id) {
		BgpDataAccessor dao = new BgpDataAccessor(zookeeperConn,
				zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
		try {
			dao.delete(id);
		} catch (Exception ex) {
			log.error("Error deleting bgp", ex);
			throw new WebApplicationException(ex, Response.status(
					Response.Status.INTERNAL_SERVER_ERROR).type(
					MediaType.APPLICATION_JSON).build());
		}
	}

	/**
	 * Sub-resource class for port's BGP.
	 */
	public static class PortBgpResource extends RestResource {

		private UUID portId = null;

		/**
		 * Constructor.
		 * 
		 * @param zkConn
		 *            ZooKeeper connection string.
		 * @param portId
		 *            UUID of a port.
		 */
		public PortBgpResource(String zkConn, UUID portId) {
			this.zookeeperConn = zkConn;
			this.portId = portId;
		}

		/**
		 * Index of bgps belonging to the port.
		 * 
		 * @return A list of bgps.
		 */
		@GET
		@Produces(MediaType.APPLICATION_JSON)
		public Bgp[] list() {
			BgpDataAccessor dao = new BgpDataAccessor(zookeeperConn,
					zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
			Bgp[] bgps = null;
			try {
				bgps = dao.list(portId);
			} catch (Exception ex) {
				log.error("Error getting bgps", ex);
				throw new WebApplicationException(Response.status(
						Response.Status.INTERNAL_SERVER_ERROR).type(
						MediaType.APPLICATION_JSON).build());
			}
			return bgps;
		}

		/**
		 * Handler for create bgp.
		 * 
		 * @param bgp
		 *            Bgp object mapped to the request input.
		 * @returns Response object with 201 status code set if successful.
		 */
		@POST
		@Consumes(MediaType.APPLICATION_JSON)
		public Response create(Bgp bgp, @Context UriInfo uriInfo) {
			bgp.setPortId(portId);
			BgpDataAccessor dao = new BgpDataAccessor(zookeeperConn,
					zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
			UUID id = null;
			try {
				id = dao.create(bgp);
			} catch (Exception ex) {
				log.error("Error creating bgp", ex);
				throw new WebApplicationException(Response.status(
						Response.Status.INTERNAL_SERVER_ERROR).type(
						MediaType.APPLICATION_JSON).build());
			}

			URI uri = uriInfo.getBaseUriBuilder().path("bgps/" + id).build();
			return Response.created(uri).build();
		}
	}

}
