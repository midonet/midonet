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

import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.{Path, GET, HeaderParam, Produces}

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation.{ApiResource, AllowCreate, AllowDelete, AllowGet}
import org.midonet.cluster.rest_api.models.{RouterPort, Port, Route, Router}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.rest_api.{InternalServerErrorHttpException, BadRequestHttpException, NotFoundHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.cluster.state.RoutingTableStorage._
import org.midonet.util.functors._
import org.midonet.util.reactivex._

@ApiResource(version = 1, template = "routeTemplate")
@Path("routes")
@RequestScoped
@AllowGet(Array(APPLICATION_ROUTE_JSON,
                APPLICATION_JSON))
@AllowDelete
class RouteResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Route](resContext) {

    protected override def getFilter(route: Route): Route = {
        if (route.routerId eq null) {
            if (route.nextHopPort ne null) {
                val port = getResource(classOf[RouterPort], route.nextHopPort)
                route.routerId = port.routerId
                route
            } else {
                throw new InternalServerErrorHttpException(
                    s"Route ${route.id} is missing both router ID and next " +
                    "hop port ID")
            }
        } else route
    }

}


@RequestScoped
@AllowCreate(Array(APPLICATION_ROUTE_JSON,
                   APPLICATION_JSON))
class RouterRouteResource @Inject()(routerId: UUID, resContext: ResourceContext)
    extends MidonetResource[Route](resContext) {


    @GET
    @Produces(Array(APPLICATION_ROUTE_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[Route] = {
        val router = getResource(classOf[Router], routerId)
        val ports = listResources(classOf[RouterPort], router.portIds.asScala)
        val portsMap = ports.map(port => (port.id, port)).toMap
        var routes = listResources(classOf[Route], router.routeIds.asScala)
        for (port <- ports) {
            routes = routes ++ listResources(classOf[Route], port.routeIds.asScala)
        }
        for (port <- ports) {
            routes = routes ++ getLearnedRoutes(port)
        }
        val result = for (route <- routes) yield {
            if ((route.routerId eq null) &&
                (route.nextHopPort ne null)) {
                route.routerId = portsMap(route.nextHopPort).routerId
            }
            route
        }
        result.asJava
    }

    protected override def createFilter(route: Route, tx: ResourceTransaction)
    : Unit = {
        throwIfNextPortNotValid(route, tx)
        route.create(routerId)
        tx.create(route)
    }

    private def throwIfNextPortNotValid(route: Route, tx: ResourceTransaction)
    : Unit = {
        if (route.`type` != Route.NextHop.Normal) {
            // The validation only applies to 'normal' routes.
            return
        }

        if (null == route.nextHopPort) {
            throw new BadRequestHttpException(getMessage(
                ROUTE_NEXT_HOP_PORT_NOT_NULL))
        }

        try {
            val port = tx.get(classOf[Port], route.nextHopPort)
            if (port.getDeviceId != routerId) {
                throw new BadRequestHttpException(getMessage(
                    ROUTE_NEXT_HOP_PORT_NOT_NULL))
            }
        } catch {
            case t: NotFoundHttpException =>
                throw new BadRequestHttpException(getMessage(
                    ROUTE_NEXT_HOP_PORT_NOT_NULL))
        }
    }

    private def getLearnedRoutes(port: Port): Seq[Route] = {
        resContext.backend.stateStore.getPortRoutes(port.id, port.hostId)
            .map[Seq[Route]](makeFunc1(_.map(route => {
                val r = Route.fromLearned(route)
                r.setBaseUri(resContext.uriInfo.getBaseUri)
                r
            }).toSeq)).asFuture.getOrThrow
    }

}