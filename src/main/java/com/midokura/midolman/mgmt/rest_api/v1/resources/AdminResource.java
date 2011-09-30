package com.midokura.midolman.mgmt.rest_api.v1.resources;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.AdminZkManager;
import com.midokura.midolman.state.StateAccessException;

@Path("/admin")
public class AdminResource extends RestResource {

    private final static Logger log = LoggerFactory
            .getLogger(AdminResource.class);

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response init() throws StateAccessException {
        AdminZkManager dao = new AdminZkManager(zooKeeper, zookeeperRoot,
                zookeeperMgmtRoot);
        try {
            dao.initialize();
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
