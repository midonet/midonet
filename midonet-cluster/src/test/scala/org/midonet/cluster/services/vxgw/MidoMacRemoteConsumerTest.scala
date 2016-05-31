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

import scala.concurrent.Future

import org.junit.runner.RunWith
import org.mockito.Matchers.any
import org.mockito.Mockito
import org.mockito.Mockito.{times, verify}
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import rx.Subscription

import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.data.vtep.model.{MacLocation, VtepMAC}
import org.midonet.cluster.services.vxgw.VtepSynchronizer.NetworkInfo
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName

@RunWith(classOf[JUnitRunner])
class MidoMacRemoteConsumerTest extends FeatureSpec with Matchers
                                                    with BeforeAndAfter
                                                    with GivenWhenThen
                                                    with MockitoSugar {

    var nwCard: NetworkInfo = _
    var nwCards: java.util.Map[UUID, NetworkInfo] = _
    var updater: MidoMacRemoteConsumer = _
    var subscription: Subscription = _

    var mac: MAC = _
    var tunIp: IPv4Addr = _
    var nwId: UUID = _

    private def lsName(id: UUID) = bridgeIdToLogicalSwitchName(id)

    before {
        mac = MAC.random()
        tunIp = IPv4Addr.random
        nwId = UUID.randomUUID()

        nwCard = new NetworkInfo
        nwCard.arpTable = mock[StateTable[IPv4Addr, MAC]]
        nwCard.macTable = mock[StateTable[MAC, UUID]]
        nwCard.vxPort = UUID.randomUUID()

        nwCards = new java.util.HashMap[UUID, NetworkInfo]
        nwCards.put(nwId, nwCard)
        updater = new MidoMacRemoteConsumer(nwCards)
    }

    feature("The updater processes emitted MacLocations") {
        scenario("An update on an unknown network is ignored") {
            val otherNwId = UUID.randomUUID()
            val ml = MacLocation(mac, lsName(otherNwId), tunIp)
            updater.onNext(ml)
            Mockito.verifyZeroInteractions(nwCard.arpTable, nwCard.macTable)
        }

        scenario("An update on a non-IEEE802 is ignored") {
            val ml = MacLocation(VtepMAC.UNKNOWN_DST, lsName(nwId), tunIp)
            updater.onNext(ml)
            Mockito.verifyZeroInteractions(nwCard.arpTable, nwCard.macTable)
            // no IP set
        }

        scenario("An mac-port update with no IP for a known network") {
            val ml = MacLocation(mac, lsName(nwId), tunIp)
            val ip1 = IPv4Addr.random
            val ip2 = IPv4Addr.random
            // let's pretend we have 2 IPs associated to the mac
            Mockito.when(nwCard.arpTable.getRemoteByValue(mac))
                   .thenReturn(Future.successful(Set(ip1, ip2)))
            Mockito.when(nwCard.macTable.addPersistent(any[MAC](), any[UUID]()))
                   .thenReturn(Future.successful({}))
            Mockito.when(nwCard.arpTable.removePersistent(any[IPv4Addr](), any[MAC]()))
                   .thenReturn(Future.successful(false))
            updater.onNext(ml)
            verify(nwCard.macTable, times(1)).addPersistent(mac, nwCard.vxPort)
            // The mac location carried a null IP, so all IPs should be
            // removed from the MAC in MidoNet
            verify(nwCard.arpTable, times(1)).removePersistent(ip1, mac)
            verify(nwCard.arpTable, times(1)).removePersistent(ip2, mac)
        }

        scenario("An mac-port update with IP for a known network") {
            val ip = IPv4Addr.random
            val ml = MacLocation(mac, ip, lsName(nwId), tunIp)
            // let's pretend that we have ip1 ssociated to the mac
            Mockito.when(nwCard.arpTable.getRemoteByValue(mac))
                   .thenReturn(Future.successful(Set(ip)))
            Mockito.when(nwCard.macTable.addPersistent(any[MAC](), any[UUID]()))
                   .thenReturn(Future.successful({}))
            Mockito.when(nwCard.arpTable.addPersistent(any[IPv4Addr](), any[MAC]()))
                   .thenReturn(Future.successful({}))
            updater.onNext(ml)
            verify(nwCard.macTable, times(1)).addPersistent(mac, nwCard.vxPort)
            verify(nwCard.arpTable, times(1)).addPersistent(ip, mac)
        }

        scenario("An mac-port removal with IP for a known network") {
            val ip = IPv4Addr.random
            val ml = MacLocation(mac, ip, lsName(nwId), null)
            // let's pretend that we have ip1 ssociated to the mac
            Mockito.when(nwCard.arpTable.getRemoteByValue(mac))
                   .thenReturn(Future.successful(Set(ip)))
            Mockito.when(nwCard.macTable.removePersistent(any[MAC](), any[UUID]()))
                   .thenReturn(Future.successful(false))
            Mockito.when(nwCard.arpTable.removePersistent(any[IPv4Addr](), any[MAC]()))
                   .thenReturn(Future.successful(false))
            updater.onNext(ml)
            verify(nwCard.macTable, times(1)).removePersistent(mac, nwCard.vxPort)
            verify(nwCard.arpTable, times(1)).removePersistent(ip, mac)
        }

        scenario("An mac-port removal without IP for a known network") {
            val ml = MacLocation(mac, lsName(nwId), null)
            // let's pretend that we have ip1 ssociated to the mac
            Mockito.when(nwCard.arpTable.getRemoteByValue(mac))
                   .thenReturn(Future.successful(Set.empty[IPv4Addr]))
            Mockito.when(nwCard.macTable.removePersistent(any[MAC](), any[UUID]()))
                   .thenReturn(Future.successful(false))
            updater.onNext(ml)
            verify(nwCard.macTable, times(1)).removePersistent(mac, nwCard.vxPort)
        }

    }

}
