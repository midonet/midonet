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

@RunWith(classOf[JUnitRunner])
class MetadataServiceManagerActorTest extends FeatureSpecLike
        with Matchers
        with BeforeAndAfter
        with MockitoSugar {

    var actorRef: ActorRef = _
    implicit var as: ActorSystem = _

    var mockBackend: MidonetBackend = _
    var mockConfig: MidolmanConfig = _
    var mockPlumber: Plumber = _
    var mockDatapathInterface: DatapathInterface = _
    var mockStore: StorageClient = _

    before {
        mockBackend = mock[MidonetBackend]
        mockConfig = mock[MidolmanConfig]
        mockPlumber = mock[Plumber]
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

    feature("MetadataServiceManagerActor") {
        scenario("port active found") {
            val portJId = UUID.randomUUID
            val addr = "1.2.3.4"
            val info = mock[InstanceInfo]
            val infoOpt = Some(info)

            when(mockStore.getComputePortInfo(portJId)).thenReturn(infoOpt)
            when(mockPlumber.plumb(mockEq(info), any())).thenReturn(addr)
            actorRef ! LocalPortActive(portJId, true)
            verify(mockStore).getComputePortInfo(portJId)
            InstanceInfoMap getByAddr addr shouldBe Some(info)
        }

        scenario("port active not found") {
            val portJId = UUID.randomUUID
            val infoOpt = None

            when(mockStore.getComputePortInfo(portJId)).thenReturn(infoOpt)
            actorRef ! LocalPortActive(portJId, true)
            verify(mockStore).getComputePortInfo(portJId)
            verify(mockPlumber, never()).plumb(any(), any())
        }

        scenario("port inactive found") {
            val portJId = UUID.randomUUID
            val addr = "1.2.3.4"
            val info = mock[InstanceInfo]

            InstanceInfoMap.put(addr, portJId, info)
            actorRef ! LocalPortActive(portJId, false)
            verify(mockPlumber).unplumb(mockEq(addr), mockEq(info), any())
            InstanceInfoMap getByAddr addr shouldBe None
            verify(mockStore, never()).getComputePortInfo(any())
        }

        scenario("port inactive not found") {
            val portJId = UUID.randomUUID

            actorRef ! LocalPortActive(portJId, false)
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

    override def preStart = {
        store = store0
    }
}
