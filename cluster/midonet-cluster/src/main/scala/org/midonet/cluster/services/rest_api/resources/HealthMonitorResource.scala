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

import java.util.{List => JList}
import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status

import scala.collection.JavaConverters._

import com.google.common.base.Objects
import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{Pool, HealthMonitor}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{NoOps, Ops, ResourceContext}

@ApiResource(version = 1)
@Path("health_monitors")
@RequestScoped
@AllowGet(Array(APPLICATION_HEALTH_MONITOR_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_HEALTH_MONITOR_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_HEALTH_MONITOR_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_HEALTH_MONITOR_JSON,
                   APPLICATION_JSON))
@AllowDelete
class HealthMonitorResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[HealthMonitor](resContext) {

    @GET
    @Path("{id}")
    @Produces(Array(APPLICATION_HEALTH_MONITOR_JSON, APPLICATION_JSON))
    override def get(@PathParam("id") id: String,
                     @HeaderParam("Accept") accept: String):HealthMonitor = {
        val hm = getResource(classOf[HealthMonitor], id).getOrThrow
        hm
    }

    @GET
    @Produces(Array(APPLICATION_HEALTH_MONITOR_COLLECTION_JSON, APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[HealthMonitor] = {
        val hms = listResources(classOf[HealthMonitor]).getOrThrow
        hms.asJava
    }

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response = {
        try {
            val response = super.delete(id)
            if (response.getStatus == Status.NOT_FOUND.getStatusCode)
                MidonetResource.OkNoContentResponse
            else
                response
        } catch {
            case e: WebApplicationException
                if e.getResponse.getStatus == Status.NOT_FOUND.getStatusCode =>
                MidonetResource.OkNoContentResponse
        }
    }

    protected override def createFilter(healthMonitor: HealthMonitor): Ops = {
        healthMonitor.create()
        NoOps
    }

    protected override def updateFilter(to: HealthMonitor,
                                        from: HealthMonitor): Ops = {
        to.update(from)
        NoOps
    }

    @GET
    @Path("{id}/pools")
    @Produces(Array(APPLICATION_POOL_COLLECTION_JSON, APPLICATION_JSON))
    def listPools(@HeaderParam("Accept") accept: String,
                  @PathParam("id") id: String): JList[Pool] = {
        val pools = listResources(classOf[Pool]).getOrThrow
        val filteredPools = pools filter (
            p => Objects.equal(p.healthMonitorId.toString, id))
        filteredPools.asJava
    }
}