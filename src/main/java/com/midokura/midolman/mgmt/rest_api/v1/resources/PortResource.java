/*
 * @(#)PortResource        1.6 11/09/05
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

import com.midokura.midolman.mgmt.data.dao.PortDataAccessor;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Port;

/**
 * Root resource class for ports.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
@Path("/ports")
public class PortResource extends RestResource {
	/*
	 * Implements REST API endpoints for ports.
	 */

	private final static Logger log = LoggerFactory
			.getLogger(PortResource.class);

	/**
	 * Get the port with the given ID.
	 * 
	 * @param id
	 *            Port UUID.
	 * @return Port object.
	 * @throws Exception
	 * @throws Exception
	 */
	@GET
	@Path("{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public Port get(@PathParam("id") UUID id) {
		// Get a port for the given ID.
		PortDataAccessor dao = new PortDataAccessor(zookeeperConn,
				zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
		try {
			return dao.get(id);
		} catch (Exception ex) {
			log.error("Error getting port", ex);
			throw new WebApplicationException(ex, Response.status(
					Response.Status.INTERNAL_SERVER_ERROR).type(
					MediaType.APPLICATION_JSON).build());
		}
	}

	@DELETE
	@Path("{id}")
	public void delete(@PathParam("id") UUID id) {
		PortDataAccessor dao = new PortDataAccessor(zookeeperConn,
				zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
		try {
			dao.delete(id);
		} catch (Exception ex) {
			log.error("Error deleting port", ex);
			throw new WebApplicationException(ex, Response.status(
					Response.Status.INTERNAL_SERVER_ERROR).type(
					MediaType.APPLICATION_JSON).build());
		}
	}
	
	@PUT
	@Path("{id}/plug")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response plug(@PathParam("id") UUID id, Port port) {
		PortDataAccessor dao = new PortDataAccessor(zookeeperConn,
				zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
		try {
			dao.attachVif(id, port);
		} catch (Exception ex) {
			log.error("Error attaching VIF", ex);
			throw new WebApplicationException(ex, Response.status(
					Response.Status.INTERNAL_SERVER_ERROR).type(
					MediaType.APPLICATION_JSON).build());
		}
		return Response.ok().build();
	}

	@PUT
	@Path("{id}/unplug")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response unplug(@PathParam("id") UUID id) {
		PortDataAccessor dao = new PortDataAccessor(zookeeperConn,
				zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
		try {
			dao.detachVif(id);
		} catch (Exception ex) {
			log.error("Error detaching VIF", ex);
			throw new WebApplicationException(ex, Response.status(
					Response.Status.INTERNAL_SERVER_ERROR).type(
					MediaType.APPLICATION_JSON).build());
		}
		return Response.ok().build();
	}
	
	public static class BridgePortResource extends RestResource {

		private UUID bridgeId = null;

		public BridgePortResource(String zkConn, UUID bridgeId) {
			this.zookeeperConn = zkConn;
			this.bridgeId = bridgeId;
		}

		@POST
		@Consumes(MediaType.APPLICATION_JSON)
		public Response create(Port port, @Context UriInfo uriInfo)
				throws Exception {
			port.setDeviceId(bridgeId);
			PortDataAccessor dao = new PortDataAccessor(zookeeperConn,
					zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);

			UUID id = null;
			try {
				id = dao.create(port);
			} catch (Exception ex) {
				log.error("Error creating bridge ports", ex);
				throw new WebApplicationException(ex, Response.status(
						Response.Status.INTERNAL_SERVER_ERROR).type(
						MediaType.APPLICATION_JSON).build());
			}

			URI uri = uriInfo.getBaseUriBuilder().path("ports/" + id).build();
			return Response.created(uri).build();
		}

		@GET
		@Produces(MediaType.APPLICATION_JSON)
		public Port[] list() {
			PortDataAccessor dao = new PortDataAccessor(zookeeperConn,
					zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
			try {
				return dao.listBridgePorts(bridgeId);
			} catch (Exception ex) {
				log.error("Error listing bridge ports", ex);
				throw new WebApplicationException(ex, Response.status(
						Response.Status.INTERNAL_SERVER_ERROR).type(
						MediaType.APPLICATION_JSON).build());
			}
		}
	}

	/**
	 * Sub-resource class for router's ports.
	 */
	public static class RouterPortResource extends RestResource {

		private UUID routerId = null;

		public RouterPortResource(String zkConn, UUID routerId) {
			this.zookeeperConn = zkConn;
			this.routerId = routerId;
		}

		@POST
		@Consumes(MediaType.APPLICATION_JSON)
		public Response create(MaterializedRouterPort port,
				@Context UriInfo uriInfo) throws Exception {
			port.setDeviceId(routerId);
			PortDataAccessor dao = new PortDataAccessor(zookeeperConn,
					zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);

			UUID id = null;
			try {
				id = dao.create(port);
			} catch (Exception ex) {
				log.error("Error creating router ports", ex);
				throw new WebApplicationException(ex, Response.status(
						Response.Status.INTERNAL_SERVER_ERROR).type(
						MediaType.APPLICATION_JSON).build());
			}

			URI uri = uriInfo.getBaseUriBuilder().path("ports/" + id).build();
			return Response.created(uri).build();
		}

		@POST
		@Consumes(MediaType.APPLICATION_JSON)
		@Produces(MediaType.APPLICATION_JSON)
		@Path("link")
		public Response createLink(LogicalRouterPort port,
				@Context UriInfo uriInfo) {
			port.setDeviceId(routerId);
			PortDataAccessor dao = new PortDataAccessor(zookeeperConn,
					zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);

			LogicalRouterPort logicalRouterPort = null;
			try {
				logicalRouterPort = dao.createLink(port);
			} catch (Exception ex) {
				log.error("Error creating logical router port link", ex);
				throw new WebApplicationException(ex, Response.status(
						Response.Status.INTERNAL_SERVER_ERROR).type(
						MediaType.APPLICATION_JSON).build());
			}
			URI uri = uriInfo.getBaseUriBuilder().path(
					"ports/" + logicalRouterPort.getId()).build();
			return Response.created(uri).entity(logicalRouterPort).build();
		}

		@GET
		@Produces(MediaType.APPLICATION_JSON)
		public Port[] list() {
			PortDataAccessor dao = new PortDataAccessor(zookeeperConn,
					zookeeperTimeout, zookeeperRoot, zookeeperMgmtRoot);
			try {
				return dao.listRouterPorts(routerId);
			} catch (Exception ex) {
				log.error("Error listing router ports", ex);
				throw new WebApplicationException(ex, Response.status(
						Response.Status.INTERNAL_SERVER_ERROR).type(
						MediaType.APPLICATION_JSON).build());
			}
		}
	}
}
