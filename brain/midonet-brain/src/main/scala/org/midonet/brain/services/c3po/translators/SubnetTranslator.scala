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

import java.{util => ju}

import scala.collection.JavaConverters._

import com.google.protobuf.ProtocolStringList

import org.midonet.brain.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{IPAddress, UUID}
import org.midonet.cluster.models.Neutron.NeutronSubnet
import org.midonet.cluster.models.Topology.Dhcp
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.util.concurrent.toFutureOps

// TODO: add code to handle connection to provider router.
class SubnetTranslator(storage: ReadOnlyStorage)
    extends NeutronTranslator[NeutronSubnet] {

    import org.midonet.brain.services.c3po.translators.SubnetTranslator._

    override protected def translateCreate(ns: NeutronSubnet) : MidoOpList = {

        val dhcp = Dhcp.newBuilder
            .setId(ns.getId)
            .setNetworkId(ns.getNetworkId)
            .setDefaultGateway(ns.getGatewayIp)
            .setServerAddress(ns.getGatewayIp)
            .setEnabled(ns.getEnableDhcp)
            .setSubnetAddress(IPSubnetUtil.toProto(ns.getCidr))
            .addAllDnsServerAddress(
                asIpAddressJavaCollection(ns.getDnsNameserversList)).build

        // TODO: connect to provider router if external
        // TODO: handle option 121 routes

        List(Create(dhcp))
    }

    override protected def translateDelete(id: UUID) : MidoOpList = {
        List(Delete(classOf[Dhcp], id))
    }

    override protected def translateUpdate(ns: NeutronSubnet) : MidoOpList = {

        val origDhcp = storage.get(classOf[Dhcp], ns.getId).await()
        val newDhcp = origDhcp.toBuilder
            .setDefaultGateway(ns.getGatewayIp)
            .setEnabled(ns.getEnableDhcp)
            .setSubnetAddress(IPSubnetUtil.toProto(ns.getCidr))
            .clearDnsServerAddress()
            .addAllDnsServerAddress(
                asIpAddressJavaCollection(ns.getDnsNameserversList)).build

        // TODO: handle option 121 routes

        List(Update(newDhcp))
    }
}

protected[translators] object SubnetTranslator {

    import IPAddressUtil._

    /**
     * Given a list of IP addresses in a ProtocolString format, return a list
     * of the same addresses as a Java Collection of IPAddress objects.
     *
     * @param addrs List of IP addresses in ProtocolString format
     * @return Java Collection of IPAddress objects
     */
    def asIpAddressJavaCollection(addrs: ProtocolStringList)
    : ju.Collection[IPAddress] = {
        addrs.asScala.map(toProto).asJavaCollection
    }
}