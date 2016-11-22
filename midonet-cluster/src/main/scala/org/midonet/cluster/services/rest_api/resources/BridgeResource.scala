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

import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.apache.curator.framework.CuratorFramework

import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.rest_api.{BadRequestHttpException, ConflictHttpException}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

@ApiResource(version = 1, name = "bridges", template = "bridgeTemplate")
@Path("bridges")
@RequestScoped
@AllowGet(Array(APPLICATION_BRIDGE_JSON_V4, APPLICATION_BRIDGE_JSON_V5,
                APPLICATION_JSON))
@AllowList(Array(APPLICATION_BRIDGE_COLLECTION_JSON_V4,
                 APPLICATION_BRIDGE_COLLECTION_JSON_V5,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_BRIDGE_JSON_V4, APPLICATION_BRIDGE_JSON_V5,
                   APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_BRIDGE_JSON_V4, APPLICATION_BRIDGE_JSON_V5,
                   APPLICATION_JSON))
@AllowDelete
class BridgeResource @Inject()(resContext: ResourceContext,
                               curator: CuratorFramework)
    extends MidonetResource[Bridge](resContext) {

    @Path("{id}/ports")
    def ports(@PathParam("id") id: UUID): BridgePortResource = {
        new BridgePortResource(id, resContext)
    }

    @Path("{id}/peer_ports")
    def peerPorts(@PathParam("id") id: UUID): BridgePeerPortResource = {
        new BridgePeerPortResource(id, resContext)
    }

    @Path("{id}/vxlan_ports")
    def vxlanPorts(@PathParam("id") id: UUID): BridgeVxlanPortResource = {
        new BridgeVxlanPortResource(id, resContext)
    }

    @Path("{id}/dhcp")
    def dhcps(@PathParam("id") id: UUID): DhcpSubnetResource = {
        new DhcpSubnetResource(id, resContext)
    }

    @Path("{id}/dhcpV6")
    def dhcpsv6(@PathParam("id") id: UUID): DhcpV6SubnetResource = {
        new DhcpV6SubnetResource(id, resContext)
    }

    @Path("{id}/mac_table")
    def macTable(@PathParam("id") bridgeId: UUID): BridgeMacTableResource = {
        new BridgeMacTableResource(bridgeId, None, resContext)
    }

    @Path("{id}/vlans/{vlan}/mac_table")
    def macTable(@PathParam("id") bridgeId: UUID,
                 @PathParam("vlan") vlanId: Short): BridgeMacTableResource = {
        new BridgeMacTableResource(bridgeId, Some(vlanId), resContext)
    }

    @Path("{id}/arp_table")
    def arpTable(@PathParam("id") bridgeId: UUID): BridgeArpTableResource = {
        new BridgeArpTableResource(bridgeId, resContext)
    }


    protected override def deleteFilter(id: String,
                                        tx: ResourceTransaction): Unit = {
        val bridge = tx.get(classOf[Bridge], id)
        if ((bridge.vxLanPortIds ne null) && !bridge.vxLanPortIds.isEmpty) {
            throw new ConflictHttpException("The bridge still has VTEP " +
                    "bindings, please remove them before deleting the bridge")
        }
        tx.delete(classOf[Bridge], id)
    }


    protected override def listFilter(bridges: Seq[Bridge]): Seq[Bridge] = {
        val tenantId = resContext.uriInfo
            .getQueryParameters.getFirst("tenant_id")
        if (tenantId eq null) bridges
        else bridges filter { _.tenantId == tenantId }
    }

    protected override def createFilter(bridge: Bridge,
                                        tx: ResourceTransaction): Unit = {
        if (bridge.vxLanPortIds != null) {
            throw new BadRequestHttpException(
                getMessage(VXLAN_PORT_ID_NOT_SETTABLE))
        }
        tx.create(bridge)
    }

    protected override def updateFilter(to: Bridge, from: Bridge,
                                        tx: ResourceTransaction): Unit = {
        if (to.vxLanPortIds != null && to.vxLanPortIds != from.vxLanPortIds) {
            throw new BadRequestHttpException(
                getMessage(VXLAN_PORT_ID_NOT_SETTABLE))
        }
        to.update(from)
        tx.update(to)
    }

}
