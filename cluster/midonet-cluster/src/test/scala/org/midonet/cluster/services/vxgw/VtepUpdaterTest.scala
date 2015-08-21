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
 * distributed under the License is distributed on an "AS IS" AASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.services.vxgw

import java.util.UUID
import java.util.UUID.randomUUID

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext

import com.google.common.util.concurrent.MoreExecutors
import org.junit.runner.RunWith
import org.mockito
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage.InMemoryStorage
import org.midonet.cluster.data.vtep.VtepDataClient
import org.midonet.cluster.data.vtep.model.MacLocation
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.midolman.state.{Ip4ToMacReplicatedMap, MacPortMap, MapNotification}
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class VtepUpdaterTest extends FeatureSpec with Matchers
                              with BeforeAndAfter
                              with GivenWhenThen
                              with TopologyBuilder
                              with MockitoSugar {

    var nw: Topology.Network = _

    var vtep: Vtep = _
    var tz: TunnelZone = _

    var host1: Host = _
    var host2: Host = _

    var port1: Port = _
    var port2: Port = _
    var vxPort: Port = _

    var h1Ip: IPv4Addr = _
    var h2Ip: IPv4Addr = _

    var vu: VtepUpdater = _
    var nwStates: java.util.Map[UUID, NetworkState] = _
    var fpHerald: FloodingProxyHerald = _
    val fpHeraldMockObs = PublishSubject.create[FloodingProxy]
    var store: InMemoryStorage = _
    var ovsdb: VtepDataClient = _

    implicit val ec = ExecutionContext.fromExecutor(
        MoreExecutors.sameThreadExecutor())

    before {
        ovsdb = mock[VtepDataClient]
        store = new InMemoryStorage

        MidonetBackend.setupBindings(store = store, stateStore = store)

        tz = createTunnelZone(randomUUID(), TunnelZone.Type.VTEP,
                              Some("vtep-Tz"))
        vtep = createVtep(randomUUID(), IPv4Addr.random, 6632,
                          fromProto(tz.getId))
        nw = createBridge()
        host1 = createHost()
        host2 = createHost()
        port1 = createBridgePort(randomUUID(), Some(fromProto(nw.getId)))
        port2 = createBridgePort(randomUUID(), Some(fromProto(nw.getId)))
        vxPort = createVxLanPort(randomUUID, Some(fromProto(nw.getId)))
                                .toBuilder.setVtepId(vtep.getId).build()

        Seq (
            tz, nw, vtep, host1, host2, port1, port2, vxPort
        ).foreach(store.create)

        h1Ip = IPv4Addr.random
        h2Ip = IPv4Addr.random
        host1 = putOnTz(host1, h1Ip)
        host2 = putOnTz(host2, h2Ip)
        port1 = putOnHost(port1, host1, "eth1")
        port2 = putOnHost(port2, host2, "eth2")

        nwStates = new java.util.HashMap[UUID, NetworkState]

        fpHerald = mock[FloodingProxyHerald]
        Mockito.when(fpHerald.lookup(mockito.Matchers.any[UUID])).thenReturn(None)
        Mockito.when(fpHerald.observable).thenReturn(fpHeraldMockObs)

        vu = new VtepUpdater(vtep, nwStates, fpHerald, store, ovsdb)
    }

    def putOnHost(p: Port, h: Host, ifc: String): Port = {
        store.update(p.toBuilder
                      .setInterfaceName(ifc)
                      .setHostId(h.getId)
                      .build())
        store.get(classOf[Port], p.getId).await()
    }

    def putOnTz(h: Host, ip: IPv4Addr): Host = {
        tz = tz.toBuilder.addHostIds(h.getId)
                         .addHosts(TunnelZone.HostToIp.newBuilder()
                                             .setHostId(h.getId)
                                             .setIp(IPAddressUtil.toProto(ip)))
                         .build()
        store.update(tz)
        store.get(classOf[Host], h.getId).await()
    }

    def notification[K, V >: Null <: Any](k: K, v: V)
        = new MapNotification[K, V](k, null, v)

    def notification[K, V >: Null <: Any](k: K, oldV: V, newV: V)
    = new MapNotification[K, V](k, oldV, newV)

    feature("The VtepUpdater only watches networks in the States map") {
        scenario("A handler only applies updates to a known network") {

            val nwId = fromProto(nw.getId)
            val mockMacRemoteObs = new TestAwaitableObserver[MacLocation]
            Mockito.when(ovsdb.macRemoteUpdater).thenReturn(mockMacRemoteObs)

            When("A mac-port change is notified")
            val mac = MAC.random()
            val mpHandler = vu.buildMacPortHandler(nwId)
            mpHandler.call(notification(mac, fromProto(port1.getId)))

            And("An ARP change is notified")
            val ip = IPv4Addr.random
            val arpHandler = vu.buildArpUpdateHandler(nwId)
            arpHandler.call(notification(ip, mac))

            Then("The macPortHandler should ignore it as it's not in nwStates")
            verifyZeroInteractions(ovsdb)

            When("The network appears in the map")
            val st = new NetworkState
            st.vxPort = randomUUID()
            st.arpTable = mock[Ip4ToMacReplicatedMap]
            st.macTable = mock[MacPortMap]
            nwStates.put(nwId, st)

            And("A new MAC-port is added to the map and notified")
            Mockito.when(st.macTable.get(mac)).thenReturn(fromProto(port1.getId))
            mpHandler.call(notification(mac, fromProto(port1.getId)))

            Then("The MAC-port mapping is sent to the VTEP")
            var currSize = 1
            mockMacRemoteObs.getOnNextEvents should have size currSize
            var ml = mockMacRemoteObs.getOnNextEvents.get(currSize - 1)
            ml.vxlanTunnelEndpoint shouldBe h1Ip
            ml.ipAddr shouldBe null
            ml.mac shouldBe mac
            ml.logicalSwitchName shouldBe bridgeIdToLogicalSwitchName(nwId)

            When("The MAC gets an IP")
            val someIp = IPv4Addr.random
            Mockito.when(st.arpTable.getByValue(mac)).thenReturn(List(someIp))
            arpHandler.call(notification(someIp, mac))

            Then("The VTEP's MAC remote entry is updated")
            currSize = currSize + 1
            mockMacRemoteObs.getOnNextEvents should have size currSize
            ml = mockMacRemoteObs.getOnNextEvents.get(currSize - 1)
            ml.vxlanTunnelEndpoint shouldBe h1Ip
            ml.ipAddr shouldBe someIp
            ml.mac shouldBe mac
            ml.logicalSwitchName shouldBe bridgeIdToLogicalSwitchName(nwId)

            When("The MAC migrates")
            Mockito.when(st.macTable.get(mac)).thenReturn(fromProto(port2.getId))
            mpHandler.call(notification(mac, fromProto(port1.getId),
                                        fromProto(port2.getId)))

            Then("The MAC-port mapping is changed")
            currSize = currSize + 1
            mockMacRemoteObs.getOnNextEvents should have size currSize
            ml = mockMacRemoteObs.getOnNextEvents.get(currSize - 1)
            ml.vxlanTunnelEndpoint shouldBe h2Ip
            ml.ipAddr shouldBe someIp
            ml.mac shouldBe mac
            ml.logicalSwitchName shouldBe bridgeIdToLogicalSwitchName(nwId)

            When("The IP is removed from the MAC")
            Mockito.when(st.arpTable.getByValue(mac)).thenReturn(List.empty)
            arpHandler.call(notification(someIp, mac, null))

            Then("The MAC remote entry is updated")
            currSize = currSize + 1
            mockMacRemoteObs.getOnNextEvents should have size currSize
            ml = mockMacRemoteObs.getOnNextEvents.get(currSize - 1)
            ml.vxlanTunnelEndpoint shouldBe h2Ip
            ml.ipAddr shouldBe null
            ml.mac shouldBe mac
            ml.logicalSwitchName shouldBe bridgeIdToLogicalSwitchName(nwId)

            When("The MAC is removed")
            Mockito.when(st.macTable.get(mac)).thenReturn(null)
            mpHandler.call(notification(mac, fromProto(port1.getId), null))

            Then("The MAC-port mapping is removed")
            currSize = currSize + 1
            mockMacRemoteObs.getOnNextEvents should have size currSize
            ml = mockMacRemoteObs.getOnNextEvents.get(currSize - 1)
            ml.vxlanTunnelEndpoint shouldBe null
            ml.ipAddr shouldBe null
            ml.mac shouldBe mac
            ml.logicalSwitchName shouldBe bridgeIdToLogicalSwitchName(nwId)

        }
    }
}
