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

import scala.concurrent.ExecutionContext._
import scala.concurrent.Future
import scala.concurrent.duration._

import com.google.common.util.concurrent.MoreExecutors.directExecutor
import org.junit.runner.RunWith
import org.mockito.Matchers.{any, eq => Eq}
import org.mockito.Mockito
import org.mockito.Mockito.{times, verify}
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import rx.Subscriber
import rx.subjects.PublishSubject

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.storage.InMemoryStorage
import org.midonet.cluster.data.vtep.model.{LogicalSwitch, MacLocation}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.midolman.state.MapNotification
import org.midonet.packets.MAC
import org.midonet.southbound.vtep.ConnectionState
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class VtepSynchronizerTest extends FeatureSpec with Matchers
                                               with BeforeAndAfter
                                               with GivenWhenThen
                                               with TopologyBuilder
                                               with VxlanGatewayFixtures
                                               with MockitoSugar
                                               with MidonetEventually {

    implicit val ec = fromExecutor(directExecutor())

    type MapNotificationSubscriber = Subscriber[MapNotification[MAC, UUID]]

    var timeout = 10 second
    var store: InMemoryStorage = _

    // The tests fixture
    var vxgw: VxlanGatewayFixture = _
    var vtep1Fix: VtepFixture = _
    var vtep2Fix: VtepFixture = _

    // Some mocks
    var fpHerald: FloodingProxyHerald = _
    var dataClient: DataClient = _
    var fpObservable: PublishSubject[FloodingProxy] = _

    before {
        store = new InMemoryStorage
        MidonetBackend.setupBindings(store, stateStore = store)

        val backend: MidonetBackend = mock[MidonetBackend]
        Mockito.when(backend.store).thenReturn(store)
        Mockito.when(backend.stateStore).thenReturn(store)

        dataClient = mock[DataClient]

        vtep1Fix = new VtepFixture(store)
        vtep2Fix = new VtepFixture(store, vtep1Fix.tzId)
        vxgw = new VxlanGatewayFixture(store, vtep1Fix)
        vxgw.mockStateTables(dataClient)

        fpHerald = mock[FloodingProxyHerald]

        // A Mock OVSDB client where we can inject remote and local macs, plus
        // connection events.
        fpObservable = PublishSubject.create()
        Mockito.when(fpHerald.lookup(any())).thenReturn(None)
        Mockito.when(fpHerald.observable)
               .thenReturn(fpObservable.asObservable())
    }


    feature("VtepSynchronizer syncs to OVDSB") {

        scenario("Macs from MidoNet") {
            val vs = new VtepSynchronizer(vtep1Fix.vtepId, store, store,
                                          dataClient, fpHerald,
                                          (ip, port) => vtep1Fix.ovsdb)
            vtep1Fix.addBinding(vxgw.nwId, "swp1_1", 11)

            val lsId = UUID.randomUUID()
            Mockito.when(vtep1Fix.ovsdb.createLogicalSwitch(any(), any()))
                   .thenReturn(Future.successful(lsId))
            Mockito.when(vtep1Fix.ovsdb.logicalSwitch(any()))
                   .thenReturn(Future.successful(
                       Some(new LogicalSwitch(lsId, "meh", 10, "moh")))
                   )

            // Preseed table
            val mac1 = MAC.random
            vxgw.macPortMap.put(mac1, vxgw.port1Id)

            // map so we can inject stuff ourselves.
            vs.onNext(vtep1Fix.vtep)

            // Report a connection from OVSDB
            vtep1Fix.ovsdbConnEvents.onNext(ConnectionState.Ready)

            // Expect the Flooding Proxy published to the VTEP as a signal that
            // the sync synced topology
            vtep1Fix.expectMacRemotes( Seq (
                MacLocation.unknownAt(null, vxgw.lsName),
                MacLocation(mac1, vxgw.lsName, vxgw.host1Ip)), 1 to 2)

            // Let's add another mac
            val mac2 = MAC.random
            vxgw.macPortMap.put(mac2, vxgw.port2Id)

            // The VTEP should've seen it now
            vtep1Fix.expectMacRemote(MacLocation(mac2, vxgw.lsName, vxgw.host2Ip), 3)

            // Let's add another mac
            vxgw.macPortMap.put(mac1, vxgw.port2Id)

            // The VTEP should've seen it now
            vtep1Fix.expectMacRemote(MacLocation(mac1, vxgw.lsName, vxgw.host2Ip), 4)

            // Let's add a MAC on an interior port
            val mac3 = MAC.random
            vxgw.macPortMap.put(mac3, vxgw.intPortId)

            // The VTEP should've been told to remove mac3, as this
            // MAC is not on a hypervisor, we'll simply let it fallback to
            // the flooding proxy.  We do a removal always to avoid having to
            // check if the mac is there already.
            vtep1Fix.expectMacRemote(MacLocation(mac3, vxgw.lsName, null), 5)

            // Let's move one of the macs to the interior port
            vxgw.macPortMap.put(mac1, vxgw.intPortId)

            // The VTEP should've been told to remove so it goes to the
            // flooding proxy
            vtep1Fix.expectMacRemote(MacLocation(mac1, vxgw.lsName, null), 6)

            // Let's update the FP
            val fp2 = FloodingProxy(vtep1Fix.tzId, vxgw.host2Id, vxgw.host2Ip)
            updateFpTo(fp2)

            vtep1Fix.expectMacRemote(
                MacLocation.unknownAt(vxgw.host2Ip, vxgw.lsName), 7)

            // And finally, let's remove a MAC
            vxgw.macPortMap.removeIfOwner(mac1)

            vtep1Fix.expectMacRemote(
                MacLocation(mac1, vxgw.lsName, null), 8)
        }

        scenario("Flooding Proxy lifecycle") {
            val vs = new VtepSynchronizer(vtep1Fix.vtepId, store, store,
                                          dataClient, fpHerald,
                                          (ip, port) => vtep1Fix.ovsdb)

            val lsId = UUID.randomUUID()
            Mockito.when(vtep1Fix.ovsdb.createLogicalSwitch(any(), any()))
                   .thenReturn(Future.successful(lsId))

            Mockito.when(vtep1Fix.ovsdb.logicalSwitch(any()))
                   .thenReturn(Future.successful(
                       Some(new LogicalSwitch(lsId, "meh", 10, "moh")))
                   )

            vtep1Fix.addBinding(vxgw.nwId, "swp1_1", 11)

            // Notify the first VTEP participating in the VxGW
            vs.onNext(vtep1Fix.vtep)

            // Stablish the connection to the OVSDB
            vtep1Fix.ovsdbConnEvents.onNext(ConnectionState.Ready)

            // Expect the Flooding Proxy published to the VTEP
            var ml = MacLocation.unknownAt(null, vxgw.lsName)
            vtep1Fix.expectMacRemote(ml, 1)

            // Expect that the VTEP was updated with the new Logical Switches
            // and the relevant bindings
            eventually {
               verify(vtep1Fix.ovsdb, times(1))
                   .createLogicalSwitch(any(), Eq(vxgw.nw.getVni))
               vtep1Fix.verifyBindingsWrittenToOvsdb(lsId)
            }

            // Let's update the FP
            val fp1 = FloodingProxy(vtep1Fix.tzId, vxgw.host1Id, vxgw.host1Ip)
            updateFpTo(fp1)

            // Expect the Flooding Proxy published to the VTEP
            ml = MacLocation.unknownAt(fp1.tunnelIp, vxgw.lsName)
            vtep1Fix.expectMacRemote(ml, 2)

            // Let's bind a new network to the VTEP
            val vxgw2 = new VxlanGatewayFixture(store, vtep1Fix)
            vxgw2.mockStateTables(dataClient)
            vtep1Fix.addBinding(vxgw2.nwId, "swp1_2", 22)
            vs.onNext(vtep1Fix.vtep)

            // The new logical switch should have the Unknown Mac Location
            ml = MacLocation.unknownAt(fp1.tunnelIp, vxgw2.lsName)
            vtep1Fix.expectMacRemote(ml, 3)

            // By now, the VtepSynchronizer has writen the config to OVSDB
            eventually {
                vtep1Fix.verifyBindingsWrittenToOvsdb(lsId)
            }

            // Now update the FP
            val fp2 = FloodingProxy(vtep1Fix.tzId, vxgw.host2Id, vxgw.host1Ip)
            updateFpTo(fp2)

            // Expect the Flooding Proxy published to the VTEP twice, once for
            // each Network (as both are in the same tunnel zone)
            vtep1Fix.expectMacRemotes(
                Seq(MacLocation.unknownAt(fp2.tunnelIp, vxgw.lsName),
                    MacLocation.unknownAt(fp2.tunnelIp, vxgw2.lsName)),
              4 to 5
            )

            // Let's void the FP
            val fp3 = FloodingProxy(vtep1Fix.tzId, null, null)
            updateFpTo(fp3)

            // Expect the Flooding Proxy published to the VTEP
            ml = MacLocation.unknownAt(null, vxgw.lsName)
            vtep1Fix.expectMacRemotes(
                Seq(MacLocation.unknownAt(null, vxgw.lsName),
                    MacLocation.unknownAt(null, vxgw2.lsName)),
                6 to 7
            )
        }
    }

    def updateFpTo(fp: FloodingProxy): Unit = {
        val retVal = if (fp.tunnelIp == null) None else Some(fp)
        Mockito.when(fpHerald.lookup(vtep1Fix.tzId)).thenReturn(retVal)
        fpObservable.onNext(fp)
        eventually {
            fpHerald.lookup(vtep1Fix.tzId) shouldBe retVal
        }
    }
}
