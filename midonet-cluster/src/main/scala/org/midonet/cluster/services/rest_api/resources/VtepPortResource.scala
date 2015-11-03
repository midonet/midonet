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

import java.net.URLEncoder
import java.util.{List => JList, UUID}

import javax.ws.rs.{PathParam, Path, GET, Produces}

import scala.collection.JavaConverters._

import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.models.{Vtep, VtepPort}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtep.{OvsdbVtepDataClient, OvsdbVtepConnectionProvider}

@RequestScoped
class VtepPortResource (vtepId: UUID, resContext: ResourceContext,
                        connectionProvider: OvsdbVtepConnectionProvider)
    extends MidonetResource[VtepPort](resContext) {

    @GET
    @Produces(Array(APPLICATION_VTEP_PORT_COLLECTION_JSON))
    def list(): JList[VtepPort] = {
        getPorts.asJava
    }

    @GET
    @Path("{name}")
    @Produces(Array(APPLICATION_VTEP_PORT_JSON))
    def get(@PathParam("name") name: String): VtepPort = {
        getPorts.find(port => URLEncoder.encode(port.name, "UTF-8") == name)
                .getOrElse {
            throw new NotFoundHttpException(getMessage(VTEP_PORT_NOT_FOUND,
                                                       vtepId, name))
        }
    }

    private def getPorts: Seq[VtepPort] = {
        val vtep = getResource(classOf[Vtep], vtepId)
        val connection = connectionProvider.get(IPv4Addr(vtep.managementIp),
                                                vtep.managementPort)
        val client = OvsdbVtepDataClient(connection)

        val ports = client.connect() flatMap { _ =>
            client.physicalPorts
        } map { ports =>
            ports map { p =>
                new VtepPort(resContext.uriInfo.getBaseUri,
                             p.name, URLEncoder.encode(p.name, "UTF-8"),
                             p.description, vtepId)
            }
        } recover { case t: Throwable =>
            client.close()
            throw t
        } getOrThrow

        client.close()
        ports
    }
}
