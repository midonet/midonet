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
import java.util.UUID.randomUUID

import scala.collection.JavaConversions._
import scala.concurrent.Future._
import scala.concurrent.duration._

import org.mockito.Matchers.{anyObject, eq => Eq}
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.scalatest.Matchers
import org.scalatest.mock.MockitoSugar
import org.slf4j.LoggerFactory._
import rx.subjects.{BehaviorSubject, PublishSubject}

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.storage.{ObjectExistsException, Storage}
import org.midonet.cluster.data.vtep.model.MacLocation
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.{IPAddressUtil, UUIDUtil}
import org.midonet.midolman.state.{Ip4ToMacReplicatedMap, MacPortMap, MockDirectory}
import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtep.OvsdbVtepDataClient
import org.midonet.southbound.vtep.VtepConnection.ConnectionState
import org.midonet.southbound.vtep.VtepConnection.ConnectionState.Disconnected
import org.midonet.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.util.concurrent._
import org.midonet.util.reactivex.TestAwaitableObserver

trait VxlanGatewayFixtures extends TopologyBuilder with MockitoSugar
                                                   with Matchers {

    class VtepFixture(store: Storage, val tzId: UUID = UUID.randomUUID()) {

        private val timeout = 3 seconds
        val ovsdb: OvsdbVtepDataClient = mock[OvsdbVtepDataClient]
        val ovsdbConnEvents = BehaviorSubject.create[ConnectionState.State]
        var macLocalsFromVtep = PublishSubject.create[MacLocation]
        var macRemotesToVtep = new TestAwaitableObserver[MacLocation]

        try {
            store.create(createTunnelZone(tzId, TunnelZone.Type.VTEP,
                                          Some("vtep-Tz")))
        } catch {
            case _: ObjectExistsException =>
                getLogger(getClass).info(s"Reusing existing tunnel zone $tzId")
        }

        val vtepId = randomUUID()
        store.create(createVtep(vtepId, IPv4Addr.random, 6632, tzId,
                                bindings = Set.empty[(Short, String, UUID)]))
        def vtep = reload()

        Mockito.when(ovsdb.observable).thenReturn(ovsdbConnEvents)
        Mockito.when(ovsdb.macLocalUpdates).thenReturn(macLocalsFromVtep)
        Mockito.when(ovsdb.macRemoteUpdater).thenReturn(successful(macRemotesToVtep))
        Mockito.when(ovsdb.connect()).thenReturn(successful(Disconnected))

        def reload(): Vtep = {
            store.get(classOf[Vtep], vtepId).await()
        }

        def addBinding(nwId: UUID, physPort: String, vlan: Int): Unit = {
            val v = reload().toBuilder.addBindings(
                Vtep.Binding.newBuilder()
                    .setNetworkId(UUIDUtil.toProto(nwId))
                    .setPortName(physPort)
                    .setVlanId(vlan)
                    .build()
            ).build()
            store.update(v)
        }

        def verifyBindingsWrittenToOvsdb(lsId: UUID): Unit = {
            // And the relevant bindings
            vtep.getBindingsList.groupBy { bdg =>
                bdg.getNetworkId
            } foreach { nwAndBdgs =>
                val expectBindings = nwAndBdgs._2.map { bdg =>
                    (bdg.getPortName, bdg.getVlanId.toShort)
                }
                // at least once, because as more bindings get added, the
                // older ones will be "ensured" more times than the newer
                verify(ovsdb, Mockito.atLeast(1))
                    .setBindings(anyObject(), Eq(expectBindings))
            }
        }

        /** Awaits until the macRemotes observer has received `position`
          * events and verifies that the one at the corresponding positio
          * matches the given MacLocation.
          */
        def expectMacRemote(ml: MacLocation, onPosition: Int): Unit = {
            macRemotesToVtep.awaitOnNext(onPosition, timeout) shouldBe true
            macRemotesToVtep.getOnNextEvents.get(onPosition - 1) shouldBe ml
        }

        def expectMacRemotes(mls: Seq[MacLocation], range: Range): Unit = {
            macRemotesToVtep.awaitOnNext(range.last, timeout) shouldBe true
            macRemotesToVtep.getOnNextEvents.subList(
                range.start - 1, range.last
            ) should contain theSameElementsAs mls
        }
    }

    /** A fixture with the following topology:
      *
      * - A Tunnel Zone of type VTEP
      * - Two hosts on the tunnel zone, whose tunnel IPs are host1Ip and host2Ip
      *   - host1 is the flooding proxy, and host2 has a 0 FP (can be changed)
      * - A Network, with:
      *   - an interior port (intPortId and intPort)
      *   - two exterior ports, port1 and port2, on host1 and host2
      *   - a vxlan port, corresponding to bindings to the VTEP
      * - A VTEP, on the tunnel zone, and with a binding on the network
      *   - You may pass an existing VTEP if you want to reuse it
      *
      * Note that you should reload objects from storage if any modification
      * is written to them.
      */
    class VxlanGatewayFixture(store: Storage, vtepFix: VtepFixture) {

        val nwId = randomUUID()
        store.create(createBridge(nwId, vni = Some(10000)))
        def nw = store.get(classOf[Network], nwId).await()

        // Hosts
        val host1Id = UUID.randomUUID()
        val host2Id = UUID.randomUUID()
        val host1Ip = IPv4Addr.random
        val host2Ip = host1Ip.next

        Set (
            createHost(host1Id, floodingProxyWeight = 100),
            createHost(host2Id, floodingProxyWeight = 1)
        ) foreach store.create
        putOnTz(host1Id, host1Ip, vtepFix.tzId)
        putOnTz(host2Id, host2Ip, vtepFix.tzId)

        val intPortId = randomUUID()
        store.create(createBridgePort(intPortId, Some(nwId)))

        val port1Id = randomUUID()
        val port2Id = randomUUID()
        val port1 = putOnHost(createBridgePort(port1Id, Some(nwId)),
                              host1Id, "eth1")
        val port2 = putOnHost(createBridgePort(port2Id, Some(nwId)),
                              host2Id, "eth2")

        var macPortMap: MacPortMap = _
        var arpTable: Ip4ToMacReplicatedMap = _

        createVxPortForVtep(vtepFix.vtep)

        /** Gives two mocks of the state tables of a given network, and mocks
          * the dataClient to return them when called with that network id.
          *
          * Not called by default, because some tests don't need this.
          */
        def mockStateTables(dataClient: DataClient): Unit = {
            // Mock the DataClient
            macPortMap = new MacPortMap(new MockDirectory())
            Mockito.when(dataClient.bridgeGetMacTable(Eq(nwId), Eq(0.toShort),
                                                      Eq(false)))
                   .thenReturn(macPortMap)
            arpTable = new Ip4ToMacReplicatedMap(new MockDirectory())
            Mockito.when(dataClient.getIp4MacMap(Eq(nwId))).thenReturn(arpTable)
        }

        def createVxPortForVtep(vtep: Vtep): Unit = {
            val vxPortId = randomUUID()
            store.create(createVxLanPort(vxPortId, Some(nwId))
                             .toBuilder.setVtepId(vtep.getId).build())

            store.update(nw.toBuilder.addVxlanPortIds(UUIDUtil.toProto
                                                          (vxPortId)).build())
            val lsId = UUID.randomUUID()
            Mockito.when(vtepFix.ovsdb.createLogicalSwitch(lsName, nw.getVni))
                   .thenReturn(successful(lsId))
            Mockito.when(vtepFix.ovsdb.setBindings(anyObject(), anyObject()))
                   .thenReturn(successful(10))
        }

        def lsName = bridgeIdToLogicalSwitchName(nwId)

        def putOnHost(p: Port, hId: UUID, ifc: String): Port = {
            store.create(p.toBuilder
                          .setInterfaceName(ifc)
                          .setHostId(UUIDUtil.toProto(hId))
                          .build())
            store.get(classOf[Port], p.getId).await()
        }

        def putOnTz(h: UUID, ip: IPv4Addr, tzId: UUID): Unit  = {
            val hostId = UUIDUtil.toProto(h)
            val _tz = store.get(classOf[TunnelZone], tzId).await()
                           .toBuilder
                           .addHostIds(hostId)
                           .addHosts(TunnelZone.HostToIp.newBuilder()
                                     .setHostId(hostId)
                                     .setIp(IPAddressUtil.toProto(ip))
                                     .build())
                           .build()
            store.update(_tz)
            store.get(classOf[Host], h).await()
        }
    }
}
