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
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status
import javax.ws.rs.{HeaderParam, POST, Path, WebApplicationException, _}

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.Status.METHOD_NOT_ALLOWED
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{LoadBalancer, Pool, Vip}
import org.midonet.cluster.rest_api.{BadRequestHttpException, NotFoundHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.{NoOps, Ops, ResourceContext}

@ApiResource(version = 1, name = "vips", template = "vipTemplate")
@Path("vips")
@RequestScoped
@AllowCreate(Array(APPLICATION_VIP_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_VIP_JSON,
                   APPLICATION_JSON))
class VipResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[Vip](resContext) {

    @GET
    @Path("{id}")
    @Produces(Array(APPLICATION_VIP_JSON,
                    APPLICATION_JSON))
    override def get(@PathParam("id") id: String,
                     @HeaderParam("Accept") accept: String):Vip = {
        val vip = getResource(classOf[Vip], id).getOrThrow
        val pool = getResource(classOf[Pool], vip.poolId).getOrThrow
        vip.loadBalancerId = pool.loadBalancerId
        vip
    }

    @GET
    @Produces(Array(APPLICATION_VIP_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[Vip] = {
        val vips = listResources(classOf[Vip]).getOrThrow
        for (vip <- vips) {
            vip.loadBalancerId =
                getResource(classOf[Pool], vip.poolId).getOrThrow.loadBalancerId
        }
        vips.asJava
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

    protected override def createFilter(vip: Vip): Ops = {
        vip.create()
        throwIfViolationsOn(vip)
        try {
            getResource(classOf[Pool], vip.poolId).getOrThrow
        } catch {
            case t: NotFoundHttpException =>
                throw new BadRequestHttpException(t.getMessage)
        }
        NoOps
    }

    protected override def updateFilter(to: Vip, from: Vip): Ops = {
        to.update(from)
        try {
            getResource(classOf[Pool], to.poolId).getOrThrow
        } catch {
            case t: NotFoundHttpException =>
                throw new BadRequestHttpException(t.getMessage)
        }
        NoOps
    }

}

@RequestScoped
@AllowCreate(Array(APPLICATION_VIP_JSON,
                   APPLICATION_JSON))
class PoolVipResource @Inject()(poolId: UUID, resContext: ResourceContext)
    extends MidonetResource[Vip](resContext) {

    @GET
    @Produces(Array(APPLICATION_VIP_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[Vip] = {
        getResource(classOf[Pool], poolId) flatMap { pool =>
            listResources(classOf[Vip], pool.vipIds.asScala) map { vips =>
                vips.foreach(_.loadBalancerId = pool.loadBalancerId)
                vips.asJava
            }
        } getOrThrow
    }

    protected override def createFilter(vip: Vip): Ops = {
        vip.create()
        throwIfViolationsOn(vip)
        val pool = try {
            getResource(classOf[Pool], vip.poolId).getOrThrow
        } catch {
            case t: NotFoundHttpException =>
                throw new BadRequestHttpException(t.getMessage)
        }
        vip.loadBalancerId = pool.loadBalancerId
        NoOps
    }
}

@RequestScoped
class LoadBalancerVipResource @Inject()(loadBalancerId: UUID,
                                        resContext: ResourceContext)
    extends MidonetResource[Vip](resContext) {

    @GET
    @Produces(Array(APPLICATION_VIP_COLLECTION_JSON,
                    APPLICATION_JSON))
    override def list(@HeaderParam("Accept") accept: String): JList[Vip] = {
        getResource(classOf[LoadBalancer], loadBalancerId) flatMap { lb =>
            listResources(classOf[Pool], lb.poolIds.asScala)
        } flatMap { pools =>
            val vipIds = pools.flatMap(_.vipIds.asScala)
            listResources(classOf[Vip], vipIds) map { vips =>
                vips.foreach(_.loadBalancerId = loadBalancerId)
                vips.asJava
            }
        } getOrThrow
    }

    @POST
    override def create(v: Vip, @HeaderParam("Content-Type") cType: String)
    : Response = {
        Response.status(METHOD_NOT_ALLOWED.getStatusCode).build()
    }

}