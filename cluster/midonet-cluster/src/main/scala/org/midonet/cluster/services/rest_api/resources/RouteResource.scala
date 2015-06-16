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

import java.util.{List => JList, UUID}
import javax.ws.rs.{HeaderParam, Produces, GET}
import javax.ws.rs.core.MediaType.APPLICATION_JSON

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.util.control.NonFatal

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation.{AllowCreate, AllowDelete, AllowGet}
import org.midonet.cluster.rest_api.models.{Router, Route}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

import org.midonet.cluster.state.RoutingTableStorage._
import org.midonet.util.reactivex._

@RequestScoped
@AllowGet(Array(APPLICATION_ROUTE_JSON,
                APPLICATION_JSON))
@AllowDelete
class RouteResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Route](resContext)

import org.midonet.util.functors._
import org.midonet.util.reactivex._

@RequestScoped
@AllowCreate(Array(APPLICATION_ROUTE_JSON,
                   APPLICATION_JSON))
class RouterRouteResource @Inject()(routerId: UUID, resContext: ResourceContext)
    extends MidonetResource[Route](resContext) {


    @GET
    @Produces(Array(APPLICATION_ROUTE_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String)
    : JList[Route] = {
        getResource(classOf[Router], routerId).map(router => {
            val routes = new ArrayBuffer[Route]
            routes ++= listResources(classOf[Route], router.routeIds.asScala)
                .getOrThrow
            for (portId <- router.portIds.asScala) {
                routes ++= getLearnedRoutes(portId)
            }
            routes
        }).getOrThrow.asJava
    }

    protected override def createFilter = (route: Route) => {
        route.create(routerId)
    }

    private def getLearnedRoutes(portId: UUID): Seq[Route] = {
        resContext.backend.stateStore.getPortRoutes(portId)
            .map[Seq[Route]](makeFunc1(_.map(Route.from).toSeq))
            .asFuture.getOrThrow
    }

}