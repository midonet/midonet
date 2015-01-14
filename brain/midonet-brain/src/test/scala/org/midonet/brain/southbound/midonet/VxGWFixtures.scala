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

package org.midonet.brain.southbound.midonet

import java.util.UUID

import com.google.inject.Inject

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.{VTEP, Bridge, TunnelZone}
import org.midonet.packets.{MAC, IPv4Addr}

trait VxGWFixtures {

    var dataClient: DataClient

    class BridgeWithTwoPortsOnOneHost(mac1: MAC, mac2: MAC) {

        val host1Ip = IPv4Addr.random
        val host = new Host()
        host.setName("Test")
        val hostId = dataClient.hostsCreate(UUID.randomUUID(), host)

        val tz = new TunnelZone()
        tz.setName("test")
        val tzId = dataClient.tunnelZonesCreate(tz)
        val zoneHost = new TunnelZone.HostConfig(hostId)
        zoneHost.setIp(host1Ip)
        dataClient.tunnelZonesAddMembership(tzId, zoneHost)

        val _b = new Bridge()
        _b.setName("Test_")

        val nwId = dataClient.bridgesCreate(_b)
        dataClient.bridgesGet(nwId)

        // Two ports on the same host
        val port1 = makeExtPort(nwId, hostId, mac1)
        val port2 = makeExtPort(nwId, hostId, mac1)

        val macPortMap = dataClient.bridgeGetMacTable(nwId, UNTAGGED_VLAN_ID,
                                                      false)

    }

    class TwoVtepsOn(tunnelZoneId: UUID) {
        val vtepPort = 6632
        val ip1 = IPv4Addr.fromString("10.0.0.100")
        val ip2 = IPv4Addr.fromString("10.0.0.200")

        val tunIp1 = IPv4Addr.fromString("10.0.0.100")
        val tunIp2 = IPv4Addr.fromString("10.0.0.200")

        val _1 = makeVtep(ip1, vtepPort, tunnelZoneId)
        val _2 = makeVtep(ip2, vtepPort, tunnelZoneId)
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
