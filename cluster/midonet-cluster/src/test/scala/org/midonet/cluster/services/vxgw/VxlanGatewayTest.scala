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

package org.midonet.cluster.services.vxgw

import java.util.UUID

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.{Success, Try}

import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.vtep.VtepConnection.{State, VtepHandle}
import org.midonet.cluster.data.vtep.VtepDataClient
import org.midonet.cluster.data.vtep.model.{LogicalSwitch, MacLocation, _}
import org.midonet.cluster.data.{Bridge, TunnelZone, VTEP}
import org.midonet.cluster.util.ObservableTestUtils._
import org.midonet.midolman.host.state.HostZkManager
import org.midonet.midolman.state.ZookeeperConnectionWatcher
import org.midonet.packets.{IPv4Addr, MAC}

trait VxlanGatewayTest {

    val log = LoggerFactory.getLogger(classOf[VxlanGatewayTest])

    var dataClient: DataClient
    var hostManager: HostZkManager

    // A fake proxy to the OVSDB
    class MockVtepConfig(ip: IPv4Addr, port: Int, tunIp: IPv4Addr,
                         initialState: Seq[MacLocation])
        extends VtepDataClient {
        val updatesFromVtep = PublishSubject.create[MacLocation]()
        val updatesToVtep = observer[MacLocation](0, 0, 0)

        @volatile var removedLogicalSwitches = Set.empty[String]

        override def macLocalUpdates = updatesFromVtep
        override def macRemoteUpdater = updatesToVtep
        override def vxlanTunnelIp = Option(tunIp)
        override def currentMacLocal = initialState
        override def currentMacLocal(id: UUID) = initialState
        override def ensureLogicalSwitch(networkId: UUID, vni: Int)
        : Try[LogicalSwitch] =
            Success(LogicalSwitch(networkId, vni, "random description"))
        override def removeLogicalSwitch(networkId: UUID) = {
            removedLogicalSwitches += LogicalSwitch.networkIdToLsName(networkId)
            Success(Unit)
        }
        override def listLogicalSwitches: Set[LogicalSwitch] = Set.empty
        override def ensureBindings(networkId: UUID,
                                    bindings: Iterable[VtepBinding])
        : Try[Unit] = Success(Unit)
        override def removeBinding(portName: String,
                                   vlanId: Short): Try[Unit] = Success(Unit)
        override def createBinding(portName: String, vlanId: Short,
                                   networkId: UUID): Try[Unit] = Success(Unit)
        override def listPhysicalSwitches: Set[PhysicalSwitch] = Set.empty
        override def physicalPorts(psId: UUID): Set[PhysicalPort] = Set.empty
        override def getManagementIp: IPv4Addr = ip
        override def getManagementPort: Int = port
        override def getHandle: Option[VtepHandle] = None
        override def disconnect(user: UUID): Unit = {}
        override def observable: Observable[State.Value] = Observable.empty()
        override def dispose(): Unit = {}
        override def getState: State.Value = State.READY
        override def connect(user: UUID): Unit = {}
    }

    class MockVtepPool(nodeId: UUID, dataClient: DataClient,
                       zkConnWatcher: ZookeeperConnectionWatcher,
                       tzState: TunnelZoneStatePublisher)
        extends VtepPool(nodeId, dataClient, zkConnWatcher, tzState, null) {

        val vteps = ListBuffer[MockVtepConfig]()

        override def create(ip: IPv4Addr, port: Int): Vtep = {
            val mockConfig = new MockVtepConfig(ip, port, ip.next,
                                                Seq.empty)
            vteps += mockConfig
            new VtepController(mockConfig, dataClient, zkConnWatcher,
                               tzState)
        }
    }

    class HostsOnVtepTunnelZone() {

        val all = mutable.Map.empty[UUID, (Host, IPv4Addr)]
        val tz = new TunnelZone().setName("test")
                                 .setType(TunnelZone.Type.vtep)
        val tzId = dataClient.tunnelZonesCreate(tz)

        addHost()

        /* For convenience, when only one host is used */
        def host: Host = all.values.head._1

        /* For convenience, when only one host is used */
        def ip: IPv4Addr = all.values.head._2

        def delete(): Unit = all.keys foreach { id =>
            if (hostManager.isAlive(id)) {
                hostManager.makeNotAlive(id)
            }
            dataClient.hostsDelete(id)
            dataClient.tunnelZonesDelete(tzId)
        }

        def addHost(): UUID = {
            val h = new Host().setName("Test")
            val id = dataClient.hostsCreate(UUID.randomUUID(), h)
            val zoneHost = new TunnelZone.HostConfig(id)
            val ip = IPv4Addr.random
            zoneHost.setIp(ip)
            dataClient.tunnelZonesAddMembership(tzId, zoneHost)
            hostManager.makeAlive(id)
            h.setId(id)
            all += id -> (h, ip)
            id
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

        // use ephemeral entries to behave as an agent
        val macPortMap = dataClient.bridgeGetMacTable(nwId, UNTAGGED_VLAN_ID,
                                                      true)
        macPortMap.start()

        val arpTable = dataClient.getIp4MacMap(nwId)
        arpTable.start()

        log.info("----------------------------------------------------")
        log.info("- Fixture: Bridge with two ports, one host: summary")
        log.info(s"-- Network: $nwId")
        log.info(s"-- Port 1: ${port1.getId}")
        log.info(s"-- Port 2: ${port2.getId}")
        log.info("----------------------------------------------------")

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

        log.info("----------------------------------------------------")
        log.info(s"- Fixture: Two VTEPs on tunnel zone $tunnelZoneId")
        log.info(s"-- VTEP 1: mgmt ip: $ip1 tunnel ip: $tunIp1")
        log.info(s"-- VTEP 2: mgmt ip: $ip2 tunnel ip: $tunIp2")
        log.info("----------------------------------------------------")

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
