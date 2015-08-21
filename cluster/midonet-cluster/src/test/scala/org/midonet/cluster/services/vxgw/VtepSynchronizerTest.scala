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

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import com.google.common.util.concurrent.MoreExecutors.sameThreadExecutor
import org.junit.runner.RunWith
import org.mockito.Matchers.{any, eq => Eq}
import org.mockito.Mockito
import org.mockito.Mockito.{times, verify}
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import rx.subjects.PublishSubject

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.storage.InMemoryStorage
import org.midonet.cluster.data.vtep.VtepConnection.ConnectionState.Connected
import org.midonet.cluster.data.vtep.model.MacLocation
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.midolman.state.{Ip4ToMacReplicatedMap, MacPortMap}

@RunWith(classOf[JUnitRunner])
class VtepSynchronizerTest extends FeatureSpec with Matchers
                                               with BeforeAndAfter
                                               with GivenWhenThen
                                               with TopologyBuilder
                                               with VxlanGatewayFixtures
                                               with MockitoSugar {

    implicit val ec = ExecutionContext.fromExecutor(
        sameThreadExecutor())

    var timeout = 10 second
    var store: InMemoryStorage = _
    var nodeId: UUID = _

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

        nodeId = UUID.randomUUID()

        vtep1Fix = new VtepFixture(store)
        vtep2Fix = new VtepFixture(store, vtep1Fix.tzId)
        vxgw = new VxlanGatewayFixture(store, vtep1Fix)

        dataClient = mock[DataClient]
        fpHerald =  mock[FloodingProxyHerald]

        mockStateTables(vxgw.nwId)

        // A Mock OVSDB client where we can inject remote and local macs, plus
        // connection events.
        fpObservable = PublishSubject.create()
        Mockito.when(fpHerald.lookup(any())).thenReturn(None)
        Mockito.when(fpHerald.observable)
               .thenReturn(fpObservable.asObservable())
    }

    /** Gives two mocks of the state tables of a given network, and mocks
      * the dataClient to return them when called with that network id.
      */
    def mockStateTables(nwId: UUID): (MacPortMap, Ip4ToMacReplicatedMap) = {
        // Mock the DataClient
        val macPortMap = mock[MacPortMap]
        Mockito.when(dataClient.bridgeGetMacTable(Eq(nwId), Eq(0.toShort),
                                                  Eq(false)))
               .thenReturn(macPortMap)

        val arpTable = mock[Ip4ToMacReplicatedMap]
        Mockito.when(dataClient.getIp4MacMap(Eq(nwId))).thenReturn(arpTable)
        (macPortMap, arpTable)
    }

    feature("VtepSynchronizer propagates Flooding Proxies to OVDSB") {
        scenario("Flooding Proxy lifecycle") {
            val vs = new VtepSynchronizer(vtep1Fix.vtepId, nodeId, store, store,
                                          dataClient, fpHerald,
                                          (ip, port) => vtep1Fix.ovsdb)

            vtep1Fix.addBinding(vxgw.nwId, "swp1_1", 11)

            // Notify the first VTEP participating in the VxGW
            vs.onNext(vtep1Fix.vtep)

            // Stablish the connection to the OVSDB
            vtep1Fix.ovsdbConnEvents.onNext(Connected)

            // Expect the Flooding Proxy published to the VTEP
            var ml = MacLocation.unknownAt(null, vxgw.lsName)
            vtep1Fix.expectMacRemote(ml, 1)

            // Expect that the VTEP was updated with the new Logical Switches
            verify(vtep1Fix.ovsdb, times(1))
                .ensureLogicalSwitch(Eq(vxgw.lsName), Eq(vxgw.nw.getVni))

            // And the relevant bindings
            vtep1Fix.verifyBindingsWrittenToOvsdb()

            // Let's update the FP
            val fp1 = FloodingProxy(vtep1Fix.tzId, vxgw.host1Id, vxgw.host1Ip)
            updateFpTo(fp1)

            // Expect the Flooding Proxy published to the VTEP
            ml = MacLocation.unknownAt(fp1.tunnelIp, vxgw.lsName)
            vtep1Fix.expectMacRemote(ml, 2)

            // Let's bind a new network to the VTEP
            val vxgw2 = new VxlanGatewayFixture(store, vtep1Fix)
            mockStateTables(vxgw2.nwId)
            vtep1Fix.addBinding(vxgw2.nwId, "swp1_2", 22)
            vs.onNext(vtep1Fix.vtep)

            // The new logical switch should have the Unknown Mac Location
            ml = MacLocation.unknownAt(fp1.tunnelIp, vxgw2.lsName)
            vtep1Fix.expectMacRemote(ml, 3)

            // By now, the VtepSynchronizer has writen the config to OVSDB
            vtep1Fix.verifyBindingsWrittenToOvsdb()

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
        Mockito.when(fpHerald.lookup(vtep1Fix.tzId))
               .thenReturn(if (fp.tunnelIp == null) None else Some(fp))
        fpObservable.onNext(fp)
    }
}
