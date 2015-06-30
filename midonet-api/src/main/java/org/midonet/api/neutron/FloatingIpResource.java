/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.api.neutron;

import java.util.List;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.NeutronMediaType;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.FloatingIp;
import org.midonet.cluster.services.rest_api.neutron.plugin.L3Api;
import org.midonet.event.neutron.FloatingIpEvent;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

public class FloatingIpResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
            FloatingIpResource.class);
    private final static FloatingIpEvent FLOATING_IP_EVENT =
            new FloatingIpEvent();

    private final L3Api api;

    @Inject
    public FloatingIpResource(RestApiConfig config, UriInfo uriInfo,
                              SecurityContext context, L3Api api) {
        super(config, uriInfo, context, null, null);
        this.api = api;
    }

    @POST
    @Consumes(NeutronMediaType.FLOATING_IP_JSON_V1)
    @Produces(NeutronMediaType.FLOATING_IP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response create(FloatingIp floatingIp) {
        log.info("FloatingIpResource.create entered {}", floatingIp);

        FloatingIp fip = api.createFloatingIp(floatingIp);
        FLOATING_IP_EVENT.create(fip.id, fip);
        log.info("FloatingIpResource.create exiting {}", fip);
        return Response.created(NeutronUriBuilder.getFloatingIp(
            getBaseUri(), fip.id)).entity(fip).build();
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed(AuthRole.ADMIN)
    public void delete(@PathParam("id") UUID id) {
        log.info("FloatingIpResource.delete entered {}", id);
        api.deleteFloatingIp(id);
        FLOATING_IP_EVENT.delete(id);
    }

    @GET
    @Path("{id}")
    @Produces(NeutronMediaType.FLOATING_IP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public FloatingIp get(@PathParam("id") UUID id) {
        log.info("FloatingIpResource.get entered {}", id);

        FloatingIp fip = api.getFloatingIp(id);
        if (fip == null) {
            throw new NotFoundHttpException(getMessage(RESOURCE_NOT_FOUND));
        }

        log.info("FloatingIpResource.get exiting {}", fip);
        return fip;
    }

    @GET
    @Produces(NeutronMediaType.FLOATING_IPS_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public List<FloatingIp> list() {
        log.info("FloatingIpResource.list entered");
        return api.getFloatingIps();
    }

    @PUT
    @Path("{id}")
    @Consumes(NeutronMediaType.FLOATING_IP_JSON_V1)
    @Produces(NeutronMediaType.FLOATING_IP_JSON_V1)
    @RolesAllowed(AuthRole.ADMIN)
    public Response update(@PathParam("id") UUID id, FloatingIp floatingIp) {
        log.info("FloatingIpResource.update entered {}", floatingIp);

        FloatingIp fip = api.updateFloatingIp(id, floatingIp);
        FLOATING_IP_EVENT.update(id, fip);
        log.info("FloatingIpResource.update exiting {}", fip);
        return Response.ok(
            NeutronUriBuilder.getFloatingIp(
                getBaseUri(), fip.id)).entity(fip).build();

    }
}
