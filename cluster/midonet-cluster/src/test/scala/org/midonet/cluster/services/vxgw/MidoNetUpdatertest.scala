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

import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.{times, verify}
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import rx.Subscription

import org.midonet.cluster.data.vtep.model.{MacLocation, VtepMAC}
import org.midonet.cluster.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.midolman.state.{Ip4ToMacReplicatedMap, MacPortMap}
import org.midonet.packets.{IPv4Addr, MAC}

@RunWith(classOf[JUnitRunner])
class MidoNetUpdaterTest extends FeatureSpec with Matchers
                                 with BeforeAndAfter
                                 with GivenWhenThen
                                 with TopologyBuilder
                                 with MockitoSugar {

    var nwState: NetworkState = _
    var nwStates: java.util.Map[UUID, NetworkState] = _
    var updater: MidoNetUpdater = _
    var subscription: Subscription = _

    var mac: MAC = _
    var tunIp: IPv4Addr = _
    var nwId: UUID = _

    private def lsName(id: UUID) = bridgeIdToLogicalSwitchName(id)

    before {
        mac = MAC.random()
        tunIp = IPv4Addr.random
        nwId = UUID.randomUUID()

        nwState = new NetworkState
        nwState.arpTable = mock[Ip4ToMacReplicatedMap]
        nwState.macTable = mock[MacPortMap]
        nwState.vxPort = UUID.randomUUID()

        nwStates = new java.util.HashMap[UUID, NetworkState]
        nwStates.put(nwId, nwState)
        updater = new MidoNetUpdater(nwStates)
    }

    feature("The updater processes emitted MacLocations") {
        scenario("An update on an unknown network is ignored") {
            val otherNwId = UUID.randomUUID()
            val ml = MacLocation(mac, lsName(otherNwId), tunIp)
            updater.onNext(ml)
            Mockito.verifyZeroInteractions(nwState.arpTable, nwState.macTable)
        }

        scenario("An update on a non-IEEE802 is ignored") {
            val ml = MacLocation(VtepMAC.UNKNOWN_DST, lsName(nwId), tunIp)
            updater.onNext(ml)
            Mockito.verifyZeroInteractions(nwState.arpTable, nwState.macTable)
            // no IP set
        }

        scenario("An mac-port update with no IP for a known network") {
            val ml = MacLocation(mac, lsName(nwId), tunIp)
            val ip1 = IPv4Addr.random
            val ip2 = IPv4Addr.random
            // let's pretend we have 2 IPs associated to the mac
            Mockito.when(nwState.arpTable.getByValue(mac)).thenReturn(List(ip1, ip2))
            updater.onNext(ml)
            verify(nwState.macTable, times(1)).put(mac, nwState.vxPort)
            // The mac location carried a null IP, so all IPs should be
            // removed from the MAC in MidoNet
            verify(nwState.arpTable, times(1)).removeIfOwnerAndValue(ip1, mac)
            verify(nwState.arpTable, times(1)).removeIfOwnerAndValue(ip2, mac)
        }

        scenario("An mac-port update with IP for a known network") {
            val ip = IPv4Addr.random
            val ml = MacLocation(mac, ip, lsName(nwId), tunIp)
            // let's pretend that we have ip1 ssociated to the mac
            Mockito.when(nwState.arpTable.getByValue(mac)).thenReturn(List.empty)
            updater.onNext(ml)
            verify(nwState.macTable, times(1)).put(mac, nwState.vxPort)
            verify(nwState.arpTable, times(1)).put(ip, mac)
        }

        scenario("An mac-port removal with IP for a known network") {
            val ip = IPv4Addr.random
            val ml = MacLocation(mac, ip, lsName(nwId), null)
            // let's pretend that we have ip1 ssociated to the mac
            Mockito.when(nwState.arpTable.getByValue(mac)).thenReturn(List(ip))
            updater.onNext(ml)
            verify(nwState.macTable, times(1)).removeIfOwnerAndValue(mac, nwState.vxPort)
            verify(nwState.arpTable, times(1)).removeIfOwnerAndValue(ip, mac)
        }

        scenario("An mac-port removal without IP for a known network") {
            val ml = MacLocation(mac, lsName(nwId), null)
            // let's pretend that we have ip1 ssociated to the mac
            Mockito.when(nwState.arpTable.getByValue(mac)).thenReturn(List.empty)
            updater.onNext(ml)
            verify(nwState.macTable, times(1)).removeIfOwnerAndValue(mac, nwState.vxPort)
        }

    }

}
