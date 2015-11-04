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

import java.util.UUID
import javax.ws.rs.Path
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.models.Topology
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.L2Insertion
import org.midonet.cluster.services.c3po.translators.L2InsertionTranslation._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.cluster.util.UUIDUtil._

@ApiResource(version = 1)
@Path("l2insertions")
@RequestScoped
@AllowGet(Array(APPLICATION_L2INSERTION_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_L2INSERTION_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_L2INSERTION_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_L2INSERTION_JSON,
                   APPLICATION_JSON))
@AllowDelete
class L2InsertionResource @Inject()(resContext: ResourceContext)
        extends MidonetResource[L2Insertion](resContext) {

    protected override def listFilter(list: Seq[L2Insertion]): Seq[L2Insertion] = {
        val filtered = list filter {
            val srvPort = uriInfo.getQueryParameters.getFirst("srv_port")
            val port = uriInfo.getQueryParameters.getFirst("port")
            if (srvPort != null)
                (i: L2Insertion) => i.srvPort.toString.equals(srvPort)
            else if (port != null)
                (i: L2Insertion) => i.port.toString.equals(port)
            else
                (_: L2Insertion) => true
        }
        filtered
    }

    protected override def createFilter(l2Insertion: L2Insertion,
                                        tx: ResourceTransaction): Unit = {
        val msg = toProto(l2Insertion).asInstanceOf[Topology.L2Insertion]
        translateInsertionCreate(store, msg)
    }

    protected override def updateFilter(to: L2Insertion, from: L2Insertion,
                                        tx: ResourceTransaction): Unit = {
        val msg = toProto(to).asInstanceOf[Topology.L2Insertion]
        translateInsertionUpdate(store, msg)
    }

    protected override def deleteFilter(id: String, tx: ResourceTransaction): Unit = {
        translateInsertionDelete(store, UUID.fromString(id.toString).asProto)
    }

}
