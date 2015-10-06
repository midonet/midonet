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
import org.mockito.Mockito
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import org.midonet.cluster.data.storage.InMemoryStorage
import org.midonet.cluster.data.vtep.model.MacLocation
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.vxgw.VtepSynchronizer.NetworkInfo
import org.midonet.midolman.state.{Ip4ToMacReplicatedMap, MacPortMap, MapNotification}
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.southbound.vtep.VtepConstants
import org.midonet.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class VtepMacRemoteConsumerTest extends FeatureSpec with Matchers
                                                    with BeforeAndAfter
                                                    with GivenWhenThen
                                                    with VxlanGatewayFixtures
                                                    with MockitoSugar {

    implicit val ec = ExecutionContext.fromExecutor(
        MoreExecutors.directExecutor())

    var vu: VtepMacRemoteConsumer = _
    var nwInfos: java.util.Map[UUID, NetworkInfo] = _
    var mockMacRemoteObs: TestAwaitableObserver[MacLocation] = _
    var store: InMemoryStorage = _

    var aVxGw: VxlanGatewayFixture = _
    var vtepFixture: VtepFixture = _

    before {
        store = new InMemoryStorage
        MidonetBackend.setupBindings(store, stateStore = store)

        vtepFixture = new VtepFixture(store)
        aVxGw = new VxlanGatewayFixture(store, vtepFixture)

        nwInfos = new java.util.HashMap[UUID, NetworkInfo]
        mockMacRemoteObs = new TestAwaitableObserver[MacLocation]
        vu = new VtepMacRemoteConsumer(vtepFixture.vtep, nwInfos, store,
                                       mockMacRemoteObs)
    }

    def notification[K, V >: Null <: Any](k: K, v: V)
        = new MapNotification[K, V](k, null, v)

    def notification[K, V >: Null <: Any](k: K, oldV: V, newV: V)
    = new MapNotification[K, V](k, oldV, newV)

    feature("Watch networks") {
        scenario("Handle a MAC lifecycle that's beyond the L2 segment") {
            // This test covers when the MAC is not in one exterior port of
            // the network bound to the VTEP, but in an interior port of a
            // router.
            val lsName = VtepConstants.bridgeIdToLogicalSwitchName(aVxGw.nwId)

            Given("A managed network")
            val st = new NetworkInfo
            st.vxPort = randomUUID()
            st.arpTable = mock[Ip4ToMacReplicatedMap]
            st.macTable = mock[MacPortMap]
            nwInfos.put(aVxGw.nwId, st)

            When("A mac-port change is notified, on a port that's not bound")
            val mac = MAC.random()
            val mpHandler = vu.buildMacPortHandler(aVxGw.nwId)
            mpHandler.call(notification(mac, aVxGw.intPortId))

            Then("The VTEP should be told to remove the MAC, so it falls " +
                 "back to the flooding proxy")
            mockMacRemoteObs.getOnNextEvents should have size 1
            mockMacRemoteObs.getOnNextEvents.head shouldBe MacLocation(mac, lsName, null)

        }

        scenario("Macs on the VTEP's VxLAN port do not generate updates") {
            Given("A VxGW")
            val vtepId = vtepFixture.vtepId
            val mpHandler = vu.buildMacPortHandler(aVxGw.nwId)
            val vxPortId = aVxGw.vxPorts.get(vtepId)

            When("The network appears in the map")
            val st = new NetworkInfo
            st.vxPort = vxPortId
            st.arpTable = mock[Ip4ToMacReplicatedMap]
            st.macTable = mock[MacPortMap]
            nwInfos.put(aVxGw.nwId, st)

            val mac1 = MAC.fromString("00:00:00:00:00:00")
            val mac2 = MAC.fromString("11:11:11:11:11:11")
            Mockito.when(st.macTable.get(mac1)).thenReturn(vxPortId)
            Mockito.when(st.macTable.get(mac2)).thenReturn(aVxGw.port1Id)

            And("A mac-port change is notified on the VxLAN port of the VTEP")
            mpHandler.call(notification(mac1, vxPortId))

            Then("It is ignored")
            mockMacRemoteObs.getOnNextEvents shouldBe empty

            When("A mac-port change is notified")
            mpHandler.call(notification(mac2, aVxGw.port1Id))

            Then("It is not ignored")
            mockMacRemoteObs.getOnNextEvents should have size 1
            mockMacRemoteObs.getOnNextEvents.head.mac shouldBe mac2

            When("A MAC on the VxLAN port moves to a different port")
            Mockito.when(st.macTable.get(mac1)).thenReturn(aVxGw.port1Id)
            mpHandler.call(notification(mac1, aVxGw.port1Id))

            Then("The MacRemote is written")
            mockMacRemoteObs.getOnNextEvents should have size 2
            mockMacRemoteObs.getOnNextEvents.last.mac shouldBe mac1

        }

        scenario("Handle a MAC lifecycle in a network") {

            val lsName = bridgeIdToLogicalSwitchName(aVxGw.nwId)

            When("A mac-port change is notified")
            val mac = MAC.random()
            val mpHandler = vu.buildMacPortHandler(aVxGw.nwId)
            mpHandler.call(notification(mac, aVxGw.port1Id))

            And("An ARP change is notified")
            val ip = IPv4Addr.random
            val arpHandler = vu.buildArpUpdateHandler(aVxGw.nwId)
            arpHandler.call(notification(ip, mac))

            Then("The macPortHandler should ignore it as it's not tracked")
            mockMacRemoteObs.getOnCompletedEvents shouldBe empty
            mockMacRemoteObs.getOnErrorEvents shouldBe empty
            mockMacRemoteObs.getOnNextEvents shouldBe empty

            When("The network appears in the map")
            val st = new NetworkInfo
            st.vxPort = aVxGw.vxPorts.get(vtepFixture.vtepId)
            st.arpTable = mock[Ip4ToMacReplicatedMap]
            st.macTable = mock[MacPortMap]
            nwInfos.put(aVxGw.nwId, st)

            And("A new MAC-port is added to the map and notified")
            Mockito.when(st.macTable.get(mac)).thenReturn(aVxGw.port1Id)
            mpHandler.call(notification(mac, aVxGw.port1Id))

            Then("The MAC-port mapping is sent to the VTEP")
            var currSize = 1
            mockMacRemoteObs.getOnNextEvents should have size currSize
            var ml = mockMacRemoteObs.getOnNextEvents.get(currSize - 1)
            ml shouldBe MacLocation(mac, lsName, aVxGw.host1Ip)

            When("The MAC gets an IP")
            val someIp = IPv4Addr.random
            Mockito.when(st.arpTable.getByValue(mac)).thenReturn(List(someIp))
            arpHandler.call(notification(someIp, mac))

            Then("The VTEP's MAC remote entry is updated")
            currSize = currSize + 1
            mockMacRemoteObs.getOnNextEvents should have size currSize
            ml = mockMacRemoteObs.getOnNextEvents.get(currSize - 1)
            ml shouldBe MacLocation(mac, someIp, lsName, aVxGw.host1Ip)

            When("The MAC migrates")
            Mockito.when(st.macTable.get(mac)).thenReturn(aVxGw.port2Id)
            mpHandler.call(notification(mac, aVxGw.port1Id, aVxGw.port2Id))

            Then("The MAC-port mapping is changed")
            currSize = currSize + 1
            mockMacRemoteObs.getOnNextEvents should have size currSize
            ml = mockMacRemoteObs.getOnNextEvents.get(currSize - 1)
            ml shouldBe MacLocation(mac, someIp, lsName, aVxGw.host2Ip)

            When("The IP is removed from the MAC")
            Mockito.when(st.arpTable.getByValue(mac)).thenReturn(List.empty)
            arpHandler.call(notification(someIp, mac, null))

            Then("The MAC remote entry is updated")
            currSize = currSize + 1
            mockMacRemoteObs.getOnNextEvents should have size currSize
            ml = mockMacRemoteObs.getOnNextEvents.get(currSize - 1)
            ml shouldBe MacLocation(mac, lsName, aVxGw.host2Ip)

            When("The MAC is removed")
            Mockito.when(st.macTable.get(mac)).thenReturn(null)
            mpHandler.call(notification(mac, aVxGw.port1Id, null))

            Then("The MAC-port mapping is removed")
            currSize = currSize + 1
            mockMacRemoteObs.getOnNextEvents should have size currSize
            ml = mockMacRemoteObs.getOnNextEvents.get(currSize - 1)
            ml shouldBe MacLocation(mac, lsName, null)
        }
    }
}
