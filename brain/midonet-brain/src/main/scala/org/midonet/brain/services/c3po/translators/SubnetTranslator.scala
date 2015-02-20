/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.brain.services.c3po.translators

import scala.collection.JavaConverters._

import org.midonet.brain.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronSubnet
import org.midonet.cluster.models.Topology.Dhcp
import org.midonet.cluster.util.DhcpUtil.asRichNeutronSubnet
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.util.concurrent.toFutureOps

// TODO: add code to handle connection to provider router.
class SubnetTranslator(storage: ReadOnlyStorage)
    extends NeutronTranslator[NeutronSubnet] {

    override protected def translateCreate(ns: NeutronSubnet): MidoOpList = {
        if (ns.isIpv6) return List()  // Doesn't handle IPv6 yet.

        val dhcp = Dhcp.newBuilder
            .setId(ns.getId)
            .setNetworkId(ns.getNetworkId)
            .setDefaultGateway(ns.getGatewayIp)
            .setServerAddress(ns.getGatewayIp)
            .setEnabled(ns.getEnableDhcp)
            .setSubnetAddress(IPSubnetUtil.toProto(ns.getCidr))

        for (addr <- ns.getDnsNameserversList.asScala)
            dhcp.addDnsServerAddress(IPAddressUtil.toProto(addr))

        // TODO: connect to provider router if external
        // TODO: handle option 121 routes

        List(Create(dhcp.build))
    }

    override protected def translateDelete(id: UUID): MidoOpList = {
        List(Delete(classOf[Dhcp], id))
    }

    override protected def translateUpdate(ns: NeutronSubnet): MidoOpList = {
        if (ns.isIpv6) return List()  // Doesn't handle IPv6 yet.

        val origDhcp = storage.get(classOf[Dhcp], ns.getId).await()
        val newDhcp = origDhcp.toBuilder
            .setDefaultGateway(ns.getGatewayIp)
            .setEnabled(ns.getEnableDhcp)
            .setSubnetAddress(IPSubnetUtil.toProto(ns.getCidr))
            .clearDnsServerAddress()

        for (addr <- ns.getDnsNameserversList.asScala)
            newDhcp.addDnsServerAddress(IPAddressUtil.toProto(addr))

        // TODO: handle option 121 routes

        List(Update(newDhcp.build))
    }
}
