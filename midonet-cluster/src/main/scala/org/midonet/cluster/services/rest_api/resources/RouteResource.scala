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
import javax.ws.rs._
import javax.ws.rs.core.Response

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation.{ApiResource, AllowCreate, AllowDelete, AllowGet}
import org.midonet.cluster.rest_api.models.{RouterPort, Port, Route, Router}
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.rest_api.{InternalServerErrorHttpException, BadRequestHttpException, NotFoundHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{NoOps, Ops, ResourceContext}
import org.midonet.cluster.state.RoutingTableStorage._
import org.midonet.util.functors._
import org.midonet.util.reactivex._

@ApiResource(version = 1)
@Path("routes")
@RequestScoped
@AllowGet(Array(APPLICATION_ROUTE_JSON,
                APPLICATION_JSON))
@AllowDelete
class RouteResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Route](resContext) {

    @PUT
    @Path("{id}")
    @Consumes(Array(APPLICATION_ROUTE_JSON,
                    APPLICATION_JSON))
    override def update(@PathParam("id") id: String, route: Route,
                        @HeaderParam("Content-Type") contentType: String)
    : Response = {
        lock {
            super.update(id, route, contentType)
        }
    }

    @DELETE
    @Path("{id}")
    override def delete(@PathParam("id") id: String): Response =
        lock { super.delete(id) }

    protected override def getFilter(route: Route): Future[Route] = {
        if (route.routerId eq null) {
            if (route.nextHopPort ne null) {
                getResource(classOf[RouterPort], route.nextHopPort) map { port =>
                    route.routerId = port.routerId
                    route
                }
            } else {
                Future.failed(new InternalServerErrorHttpException(
                    s"Route ${route.id} is missing both router ID and next " +
                    "hop port ID"))
            }
        } else Future.successful(route)
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
    override def list(@HeaderParam("Accept") accept: String)
    : JList[Route] = {
        val future = getResource(classOf[Router], routerId) flatMap { router =>
            listResources(classOf[RouterPort],
                          router.portIds.asScala) flatMap { ports =>
                val portsMap = ports.map(port => (port.id, port)).toMap
                val futures = new ArrayBuffer[Future[Seq[Route]]]
                futures += listResources(classOf[Route], router.routeIds.asScala)
                for (port <- ports) {
                    futures += listResources(classOf[Route], port.routeIds.asScala)
                }
                for (port <- ports) {
                    futures += getLearnedRoutes(port)
                }
                Future.sequence(futures) map { result =>
                    for (route <- result.flatten) yield {
                        if ((route.routerId eq null) &&
                            (route.nextHopPort ne null)) {
                            route.routerId = portsMap(route.nextHopPort).routerId
                        }
                        route
                    }
                }
            }
        }
        future.getOrThrow.asJava
    }

    protected override def createFilter(route: Route): Ops = {
        throwIfNextPortNotValid(route)
        throwIfViolationsOn(route)
        route.create(routerId)
        NoOps
    }

    private def throwIfNextPortNotValid(route: Route): Unit = {
        if (route.`type` != Route.NextHop.Normal) {
            // The validation only applies to 'normal' routes.
            return
        }

        if (null == route.nextHopPort) {
            throw new BadRequestHttpException(getMessage(
                ROUTE_NEXT_HOP_PORT_NOT_NULL))
        }

        try {
            val p = getResource(classOf[Port], route.nextHopPort).getOrThrow
            if (p.getDeviceId != routerId) {
                throw new BadRequestHttpException(getMessage(
                    ROUTE_NEXT_HOP_PORT_NOT_NULL))
            }
        } catch {
            case t: NotFoundHttpException =>
                throw new BadRequestHttpException(getMessage(
                    ROUTE_NEXT_HOP_PORT_NOT_NULL))
        }
    }

    private def getLearnedRoutes(port: Port): Future[Seq[Route]] = {
        resContext.backend.stateStore.getPortRoutes(port.id, port.hostId)
            .map[Seq[Route]](makeFunc1(_.map(route => {
                val r = Route.fromLearned(route)
                r.setBaseUri(resContext.uriInfo.getBaseUri)
                r
            }).toSeq)).asFuture
    }

}