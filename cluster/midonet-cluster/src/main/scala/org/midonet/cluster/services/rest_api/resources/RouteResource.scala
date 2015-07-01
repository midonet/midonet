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
import javax.ws.rs.{GET, HeaderParam, Produces}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.models.Topology
import org.midonet.cluster.rest_api.{NotFoundHttpException, BadRequestHttpException}
import org.midonet.cluster.rest_api.annotation.{AllowCreate, AllowDelete, AllowGet}
import org.midonet.cluster.rest_api.models.{Port, Route, Router}
import org.midonet.cluster.rest_api.validation.MessageProperty
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.cluster.state.RoutingTableStorage._
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.functors._
import org.midonet.util.reactivex._

@RequestScoped
@AllowGet(Array(APPLICATION_ROUTE_JSON,
                APPLICATION_JSON))
@AllowDelete
class RouteResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Route](resContext)


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
        getResource(classOf[Router], routerId).flatMap(router => {
            val futures = new ArrayBuffer[Future[Seq[Route]]]
            futures += listResources(classOf[Route], router.routeIds.asScala)
            for (portId <- router.portIds.asScala) {
                futures += getLearnedRoutes(portId)
            }
            Future.sequence(futures)
        }).getOrThrow.flatten.asJava
    }

    protected override def createFilter = (route: Route) => {
        throwIfNextPortNotValid(route)
        throwIfViolationsOn(route)
        route.create(routerId)
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

    private def getLearnedRoutes(portId: UUID): Future[Seq[Route]] = {
        resContext.backend.stateStore.getPortRoutes(portId)
            .map[Seq[Route]](makeFunc1(_.map(route => {
                val r = Route.fromLearned(route)
                r.setBaseUri(resContext.uriInfo.getBaseUri)
                r
            }).toSeq)).asFuture
    }

}