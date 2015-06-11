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

import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.{Response, UriInfo}

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{UriResource, L2Insertion}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

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
class L2InsertionResource @Inject()(backend: MidonetBackend, uriInfo: UriInfo)
    extends MidonetResource[L2Insertion](backend, uriInfo) {

    /*def updateInsertions(portId: UUID,
                         created: L2Insertion = None,
                         updated: L2Insertion = None,
                         deleted: Boolean = false): Response = {
        backend.store.get(classOf[Port], portId).map{ p =>
            // Get the list of insertions for this portId
            // If the list is empty, then only create is allowed
            //p.getInsertionsList()
            // Find the chains (in and out) for this port's insertions
            // Delete all the rules
            // Recreate the rules
        }
        Response.ok().build()
    }

    override protected def createResource[U >: Null <: UriResource]
                                         (resource: U) = {
        //val ins = resource.asInstanceOf[L2Insertion]
        //updateInsertions(ins.port, created = ins)
        super.createResource(resource)
    }

    override protected def updateResource[U >: Null <: UriResource]
                                         (resource: U,
                                          res: Response) = {
        //val ins = resource.asInstanceOf[L2Insertion]
        //updateInsertions(ins.port, updated = ins)
        super.updateResource(resource)
    }

    override protected def deleteResource[U >: Null <: UriResource]
                                         (clazz: Class[U], id: Any,
                                          res: Response) = {
        //updateInsertions(id.asInstanceOf[UUID], deleted = true)
        super.deleteResource(clazz, id, res)
    }*/

    protected override def updateFilter = (to: L2Insertion, from: L2Insertion) => {
        to.update(from)
    }

}
