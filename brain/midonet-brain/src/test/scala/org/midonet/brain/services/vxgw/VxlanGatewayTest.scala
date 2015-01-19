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

package org.midonet.brain.services.vxgw

import java.util.UUID

import scala.util.{Success, Try}

import rx.subjects.PublishSubject

import org.midonet.brain.southbound.vtep.model.LogicalSwitch
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.{Bridge, TunnelZone, VTEP}
import org.midonet.cluster.util.ObservableTestUtils._
import org.midonet.packets.{IPv4Addr, MAC}

trait VxlanGatewayTest {

    var dataClient: DataClient
    
    // A fake proxy to the OVSDB
    class MockVtepConfig(ip: IPv4Addr, port: Int, tunIp: IPv4Addr,
                        initialState: Seq[MacLocation])
        extends VtepConfig(ip, port) {

        val updatesFromVtep = PublishSubject.create[MacLocation]()
        val updatesToVtep = observer[MacLocation](0, 0, 0)
        override def macLocalUpdates = updatesFromVtep
        override def macRemoteUpdates = updatesToVtep
        override def vxlanTunnelIp = Option(tunIp)
        override def currentMacLocal = initialState
        override def ensureLogicalSwitch(name: String, vni: Int) = Success(null)
        override def ensureBindings(lsName: String,
                                    bs: Iterable[(String, Short)]) = Success()
        override def removeLogicalSwitch(name: String): Try[Unit] = Success()
    }

    class HostOnVtepTunnelZone(floodingProxyWeight: Int) {
        val ip = IPv4Addr.random
        val host = new Host()
        host.setName("Test")
        host.setFloodingProxyWeight(floodingProxyWeight)
        val id = dataClient.hostsCreate(UUID.randomUUID(), host)

        val tz = new TunnelZone()
        tz.setName("test")
        tz.setType(TunnelZone.Type.vtep)
        val tzId = dataClient.tunnelZonesCreate(tz)
        val zoneHost = new TunnelZone.HostConfig(id)
        zoneHost.setIp(ip)
        dataClient.tunnelZonesAddMembership(tzId, zoneHost)

        def delete(): Unit = {
            dataClient.hostsDelete(id)
            dataClient.tunnelZonesDelete(tzId)
        }
    }

    /** A bridge with one port */
    class BridgeWithTwoPortsOnOneHost(mac1: MAC, mac2: MAC, hostId: UUID) {

        val _b = new Bridge()
        _b.setName("Test_")

        val nwId = dataClient.bridgesCreate(_b)
        dataClient.bridgesGet(nwId)

        // Two ports on the same host
        val port1 = makeExtPort(nwId, hostId, mac1)
        val port2 = makeExtPort(nwId, hostId, mac1)

        val macPortMap = dataClient.bridgeGetMacTable(nwId, UNTAGGED_VLAN_ID,
                                                      false)
        macPortMap.start()

        val arpTable = dataClient.getIp4MacMap(nwId)
        arpTable.start()

        def delete(): Unit = {
            macPortMap.stop()
            dataClient.bridgesDelete(nwId) // removes also ports
        }
    }

    class TwoVtepsOn(tunnelZoneId: UUID) {
        val vtepPort = 6632
        val ip1 = IPv4Addr("10.0.0.100")
        val ip2 = IPv4Addr("10.0.0.200")

        val tunIp1 = ip1.next
        val tunIp2 = ip2.next

        val _1 = makeVtep(ip1, vtepPort, tunnelZoneId)
        val _2 = makeVtep(ip2, vtepPort, tunnelZoneId)

        def delete(): Unit = {
            dataClient.vtepDelete(ip1)
            dataClient.vtepDelete(ip2)
        }
    }

    def makeVtep(ip: IPv4Addr, port: Int, tzId: UUID): VTEP = {
        val vtep = new VTEP()
        vtep.setId(ip)
        vtep.setMgmtPort(port)
        vtep.setTunnelZone(tzId)
        dataClient.vtepCreate(vtep)
        dataClient.vtepGet(ip)
    }

    def makeExtPort(deviceId: UUID, hostId: UUID, mac: MAC): BridgePort = {
        val p = new BridgePort()
        p.setDeviceId(deviceId)
        p.setHostId(hostId)
        p.setInterfaceName(s"eth-$mac")
        val portId = dataClient.portsCreate(p)
        dataClient.hostsAddVrnPortMappingAndReturnPort(hostId, portId,
                                                       p.getInterfaceName)
        dataClient.portsGet(portId).asInstanceOf[BridgePort]
    }


}
