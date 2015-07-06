/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.services.rest_api.resources

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response.Status

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation.{AllowCreate, AllowDelete, AllowList}
import org.midonet.cluster.rest_api.models.{TunnelZone, VTEP}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.rest_api.{ApiException, NotFoundHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@RequestScoped
@AllowList(Array(APPLICATION_VTEP_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_VTEP_JSON,
                   APPLICATION_JSON))
@AllowDelete
class VTEPResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[VTEP](resContext) {

    @GET
    @Path("{mgmtIp}")
    @Produces(Array(APPLICATION_VTEP_JSON,
                    APPLICATION_JSON))
    override def get(@PathParam("mgmtIp") mgmtIp: String,
                     @HeaderParam("Accept") accept: String): VTEP = {
        listResources(classOf[VTEP])
            .map(_.find(_.managementIp == mgmtIp))
            .getOrThrow
            .getOrElse(throw new NotFoundHttpException(
                                    getMessage(RESOURCE_NOT_FOUND)))
    }

    @Path("{mgmtIp}/bindings")
    def bindings(@PathParam("mgmtIp") mgmtIp: String): VTEPBindingResource = {
        new VTEPBindingResource(mgmtIp, resContext)
    }

    protected override def createFilter = (vtep: VTEP) => {
        hasResource(classOf[TunnelZone], vtep.tunnelZoneId).flatMap(exists => {
            // Validate the tunnel zone.
            if (!exists) {
                throw new ApiException(Status.BAD_REQUEST,
                                       getMessage(TUNNEL_ZONE_ID_IS_INVALID))
            }

            listResources(classOf[VTEP])
        }).map(vteps => {
            // Validate there is no conflict with existing VTEPs.
            for (v <- vteps if v.managementIp == vtep.managementIp) {
                throw new ApiException(Status.CONFLICT,
                                       getMessage(VTEP_HOST_IP_CONFLICT))
            }
        }).getOrThrow
        vtep.create()
    }

    protected override def deleteFilter = (id: String) => {
        getResource(classOf[VTEP], id).map(vtep => {
            // Validate the VTEP has no bindings.
            if (vtep.bindings.size() > 0) {
                throw new ApiException(Status.BAD_REQUEST,
                                       getMessage(VTEP_HAS_BINDINGS))
            }
        }).getOrThrow
    }

}
