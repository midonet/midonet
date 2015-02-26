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

package org.midonet.cluster.services.vxgw

import java.util.UUID
import javax.annotation.Nonnull

import scala.collection.JavaConverters._

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.cluster.data.ports.VxLanPort
import org.midonet.cluster.data.{Bridge, VTEP, VtepBinding}
import org.midonet.midolman.state.Directory.TypedWatcher
import org.midonet.midolman.state.{Ip4ToMacReplicatedMap, MacPortMap}
import org.midonet.packets.{IPv4Addr, MAC}

/** This class models the API used by the VxGW service to access the MidoNet
  * low level model
  */
trait TopologyApi {

    /** Retrieve the MAC table for the given network id. Assuming always that
      * with VLAN 0, as there is no support for vlan tagged traffic in VxGW.
      * The items written to the table will *not* be persistent.
      */
    def macTable(networkId: UUID): MacPortMap

    /** Retrieve the ARP table for the given network. */
    def arpTable(networkId: UUID): Ip4ToMacReplicatedMap

    /** Retrieve the network
      *
      * TODO: remove the dep. on the Directory, replace with Runnable.
      */
    def bridge(id: UUID, watcher: TypedWatcher): Bridge

    /** Retrieve the port */
    def vxlanPort(id: UUID): VxLanPort

    /** Retrieve the tunnel endpoint of the host where the given port is
      * bound, if any */
    def vxlanTunnelEndpointFor(portId: UUID): IPv4Addr

    /** Retrieve the IP associated tot he given MAC, if any */
    def ipsOf(networkId: UUID, mac: MAC): Set[IPv4Addr]

    /** Learn the given IP on the given MAC on the given network */
    def learn(networkId: UUID, ip: IPv4Addr, mac: MAC): Unit

    /** Deletes the association between the given IP and MAC on the given
      * network */
    def unlearn(networkId: UUID, ip: IPv4Addr, mac: MAC): Unit

    /** Retrieve the vtep with the given id. */
    def vtep(mgmtIp: IPv4Addr): VTEP

    /** Retrieve all the bindings of the given network id to the VTEP at the
      * given management ip. */
    def vtepBindings(networkId: UUID, mgmtIp: IPv4Addr): List[VtepBinding]

}

object Topology {
    def apply(dc: DataClient) = new Topology(dc)
}

/** An implementation based on MidoNet 1.x storage stack */
class Topology(@Nonnull dataClient: DataClient) extends TopologyApi {

    override def macTable(networkId: UUID): MacPortMap =
        dataClient.bridgeGetMacTable(networkId, UNTAGGED_VLAN_ID, false)

    override def arpTable(networkId: UUID): Ip4ToMacReplicatedMap =
        dataClient.bridgeGetArpTable(networkId)

    override def bridge(id: UUID, watcher: TypedWatcher): Bridge =
        dataClient.bridgeGetAndWatch(id, watcher)

    override def vxlanTunnelEndpointFor(portId: UUID): IPv4Addr =
        dataClient.vxlanTunnelEndpointFor(portId)

    override def vxlanPort(id: UUID): VxLanPort =
        dataClient.portsGet(id).asInstanceOf[VxLanPort]

    override def ipsOf(networkId: UUID, mac: MAC): Set[IPv4Addr] =
        dataClient.bridgeGetIp4ByMac(networkId, mac).asScala.toSet

    override def learn(networkId: UUID, ip: IPv4Addr, mac: MAC): Unit =
        dataClient.bridgeAddLearnedIp4Mac(networkId, ip, mac)

    override def unlearn(networkId: UUID, ip: IPv4Addr, mac: MAC): Unit =
        dataClient.bridgeDeleteLearnedIp4Mac(networkId, ip, mac)

    override def vtep(mgmtIp: IPv4Addr): VTEP = dataClient.vtepGet(mgmtIp)

    override def vtepBindings(networkId: UUID, mgmtIp: IPv4Addr)
    : List[VtepBinding] = {
        dataClient.bridgeGetVtepBindings(networkId, mgmtIp).asScala.toList
    }

}
