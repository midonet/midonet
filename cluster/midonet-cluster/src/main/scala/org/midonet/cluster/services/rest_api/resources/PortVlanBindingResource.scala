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

import javax.ws.rs.Path
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.data.storage.{CreateOp, DeleteOp, UpdateOp}
import org.midonet.cluster.models.PortVlanBindingTranslation._
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.{PortVlanBinding, UriResource}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

@ApiResource(version = 1)
@Path("port_vlan_bindings")
@RequestScoped
@AllowGet(Array(APPLICATION_PORT_VLAN_BINDING_JSON,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_PORT_VLAN_BINDING_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_PORT_VLAN_BINDING_JSON,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_PORT_VLAN_BINDING_JSON,
                   APPLICATION_JSON))
@AllowDelete
class PortVlanBindingResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[PortVlanBinding](resContext) {

    def check(res: Boolean): Response = {
        res match {
            case true => Response.noContent().build
            case false => Response.status(Response.Status.PRECONDITION_FAILED).build
        }
    }

    override protected def createResource[U >: Null <: UriResource]
                                         (resource: U) = {
        val message = toProto(resource)
        log.info("CREATE: {}\n{}", message.getClass, message)
        updateBinding(backend.store, CreateOp(message)) match {
            case true =>
                resource.setBaseUri(uriInfo.getBaseUri)
                Response.created(resource.getUri).build
            case false =>
                Response.status(Response.Status.PRECONDITION_FAILED).build
        }
    }

    override protected def updateResource[U >: Null <: UriResource]
                                         (resource: U,
                                          res: Response) = {
        val message = toProto(resource)
        log.info("UPDATE: {}\n{}", message.getClass, message)
        check(updateBinding(backend.store, UpdateOp(message)))
    }

    override protected def deleteResource[U >: Null <: UriResource]
                                         (clazz: Class[U], id: Any,
                                          res: Response) = {
        log.info("DELETE: {}:{}", UriResource.getZoomClass(clazz),
                 id.asInstanceOf[AnyRef])
        check(updateBinding(backend.store,
                               DeleteOp(UriResource.getZoomClass(clazz), id)))
    }

    protected override def updateFilter(to: PortVlanBinding,
                                        from: PortVlanBinding): Ops = {
        to.update(from)
        NoOps
    }
}
