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

import javax.ws.rs.{Path, _}

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.BadRequestHttpException
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@ApiResource(version = 1, name = "ipAddrGroups", template = "ipAddrGroupTemplate")
@Path("ip_addr_groups")
@RequestScoped
@AllowGet(Array(APPLICATION_IP_ADDR_GROUP_JSON))
@AllowList(Array(APPLICATION_IP_ADDR_GROUP_COLLECTION_JSON))
@AllowCreate(Array(APPLICATION_IP_ADDR_GROUP_JSON))
@AllowDelete
class IpAddrGroupResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[IpAddrGroup](resContext) {

    @Path("{id}/ip_addrs")
    def ports(@PathParam("id") id: UUID): IpAddrGroupAddrResource = {
        new IpAddrGroupAddrResource(id, resContext)
    }

    @Path("/{id}/versions/{version}/ip_addrs")
    def addressVersion(@PathParam("id") id: UUID,
                       @PathParam("version") version: Int)
    :IpAddrGroupAddrVersionResource = {
        if (version != 4 && version != 6) {
            throw new BadRequestHttpException("Invalid IP version: " + version)
        }
        new IpAddrGroupAddrVersionResource(id, resContext)
    }

}
