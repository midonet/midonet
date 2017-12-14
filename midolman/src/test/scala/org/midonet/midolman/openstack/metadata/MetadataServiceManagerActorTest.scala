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

package org.midonet.midolman.openstack.metadata

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import java.util.UUID

import org.junit.runner.RunWith
import org.mockito.Matchers.any
import org.mockito.Matchers.{eq => mockEq}
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfter
import org.scalatest.FeatureSpecLike
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.topology.VirtualToPhysicalMapper.LocalPortActive
import org.midonet.midolman.util.TestDatapathState

import scala.collection.mutable.{Map => MutMap}

@RunWith(classOf[JUnitRunner])
class MetadataServiceManagerActorTest extends FeatureSpecLike
        with Matchers
        with BeforeAndAfter
        with MockitoSugar {

    var actorRef: ActorRef = _
    implicit var as: ActorSystem = _

    var mockBackend: MidonetBackend = _
    var mockConfig: MidolmanConfig = _
    var mockDpState: TestDatapathState = _
    var mockPlumber: Plumber = _
    var mockDatapathInterface: DatapathInterface = _
    var mockStore: StorageClient = _

    before {
        mockBackend = mock[MidonetBackend]
        mockConfig = mock[MidolmanConfig]
        mockDpState = new TestDatapathState()
        mockPlumber = mock[Plumber]
        when(mockPlumber.dpState).thenReturn(mockDpState)
        mockDatapathInterface = mock[DatapathInterface]
        mockStore = mock[StorageClient]
        as = ActorSystem()
        actorRef = TestActorRef(new TestableMetadataServiceManagerActor(
            mockBackend,
            mockConfig,
            mockPlumber,
            mockDatapathInterface,
            mockStore
        ))
    }

    after {
        as stop actorRef
        as.shutdown
    }

    private def mockedInfoFor(portId: UUID): Option[InstanceInfo] = {
        val info = mock[InstanceInfo]
        when(info.portId).thenReturn(portId)
        Some(info)
    }

    feature("MetadataServiceManagerActor") {
        scenario("port active found") {
            val portJId = UUID.randomUUID
            val dpPortNumber = 1
            val addr = "1.2.3.4"
            val info = mockedInfoFor(portJId)
            mockDpState.dpPortNumberForVport = MutMap(portJId -> dpPortNumber)

            when(mockStore.getComputePortInfo(portJId)).thenReturn(info)
            when(mockPlumber.plumb(mockEq(info.get), any())).thenReturn(addr)
            actorRef ! LocalPortActive(portJId, dpPortNumber, true)
            verify(mockStore).getComputePortInfo(portJId)
            InstanceInfoMap getByAddr addr shouldBe info
        }

        scenario("port active with same dpPort (invalid) as previous one") {
            val portJId = UUID.randomUUID
            val portJId2 = UUID.randomUUID
            val dpPortNumber = 1
            val addr = "1.2.3.4"
            val info = mockedInfoFor(portJId)
            val info2 = mockedInfoFor(portJId2)
            mockDpState.dpPortNumberForVport = MutMap(portJId -> dpPortNumber)

            when(mockStore.getComputePortInfo(portJId)).thenReturn(info)
            when(mockStore.getComputePortInfo(portJId2)).thenReturn(info2)
            when(mockPlumber.plumb(mockEq(info.get), any())).thenReturn(addr)

            actorRef ! LocalPortActive(portJId, dpPortNumber, true)
            verify(mockStore).getComputePortInfo(portJId)
            InstanceInfoMap getByAddr addr shouldBe info
            actorRef ! LocalPortActive(portJId2, dpPortNumber, true)
            verify(mockStore, never()).getComputePortInfo(portJId2)
            // We should see the valid one if we search by address
            InstanceInfoMap getByAddr addr shouldBe info
            InstanceInfoMap getByPortId portJId shouldBe Some(addr)
            // The stale port was not added to the map
            InstanceInfoMap getByPortId portJId2 shouldBe None

            actorRef ! LocalPortActive(portJId, dpPortNumber, false)
            InstanceInfoMap getByPortId portJId shouldBe None
            // If we happen to receive the inactive message for the stale port,
            // we should do nothing and not fail.
            actorRef ! LocalPortActive(portJId2, dpPortNumber, false)
            InstanceInfoMap getByPortId portJId2 shouldBe None
        }

        scenario("port active with same dpPort (valid) as previous one") {
            val portJId = UUID.randomUUID
            val portJId2 = UUID.randomUUID
            val dpPortNumber = 1
            val addr = "1.2.3.4"
            val info = mockedInfoFor(portJId)
            val info2 = mockedInfoFor(portJId2)
            mockDpState.dpPortNumberForVport = MutMap(portJId2 -> dpPortNumber)

            when(mockStore.getComputePortInfo(portJId)).thenReturn(info)
            when(mockStore.getComputePortInfo(portJId2)).thenReturn(info2)
            when(mockPlumber.plumb(mockEq(info2.get), any())).thenReturn(addr)

            actorRef ! LocalPortActive(portJId, dpPortNumber, true)
            verify(mockStore, never()).getComputePortInfo(portJId)
            InstanceInfoMap getByAddr addr shouldBe None
            actorRef ! LocalPortActive(portJId2, dpPortNumber, true)
            verify(mockStore).getComputePortInfo(portJId2)
            // We should see the valid one if we search by address
            InstanceInfoMap getByAddr addr shouldBe info2
            InstanceInfoMap getByPortId portJId2 shouldBe Some(addr)
            // The stale port was not added to the map
            InstanceInfoMap getByPortId portJId shouldBe None

            // If we happen to receive the inactive message for the stale port,
            // we should do nothing and not fail.
            actorRef ! LocalPortActive(portJId, dpPortNumber, false)
            InstanceInfoMap getByPortId portJId shouldBe None
            actorRef ! LocalPortActive(portJId2, dpPortNumber, false)
            InstanceInfoMap getByPortId portJId2 shouldBe None
        }

        scenario("port active not found") {
            val portJId = UUID.randomUUID
            val dpPortNumber = 1
            val infoOpt = None
            mockDpState.dpPortNumberForVport = MutMap(portJId -> dpPortNumber)

            when(mockStore.getComputePortInfo(portJId)).thenReturn(infoOpt)
            actorRef ! LocalPortActive(portJId, dpPortNumber, true)
            verify(mockStore).getComputePortInfo(portJId)
            verify(mockPlumber, never()).plumb(any(), any())
        }

        scenario("port inactive found") {
            val portJId = UUID.randomUUID
            val addr = "1.2.3.4"
            val info = mock[InstanceInfo]

            InstanceInfoMap.put(addr, portJId, info)
            actorRef ! LocalPortActive(portJId, -1, false)
            verify(mockPlumber).unplumb(mockEq(addr), mockEq(info), any())
            InstanceInfoMap getByAddr addr shouldBe None
            verify(mockStore, never()).getComputePortInfo(any())
        }

        scenario("port inactive not found") {
            val portJId = UUID.randomUUID

            actorRef ! LocalPortActive(portJId, -1, false)
            verify(mockPlumber, never()).unplumb(any(), any(), any())
            verify(mockStore, never()).getComputePortInfo(any())
        }
    }
}

class TestableMetadataServiceManagerActor(
        backend: MidonetBackend,
        config: MidolmanConfig,
        plumber: Plumber,
        datapathInterface: DatapathInterface,
        val store0: StorageClient
    ) extends MetadataServiceManagerActor(
        backend,
        config,
        plumber,
        datapathInterface
    ) {

    override def preStart() = {
        store = store0
    }
}
