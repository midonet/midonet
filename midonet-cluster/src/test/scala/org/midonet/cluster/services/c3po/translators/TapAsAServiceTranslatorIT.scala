/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.c3po.translators

import java.util.UUID

import scala.collection.JavaConverters._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{TapFlow, TapService}
import org.midonet.cluster.models.Topology.{Mirror, Port}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class TapAsAServiceTranslatorIT extends C3POMinionTestBase {

    it should "handle tap service create" in {
        val nwId = createTenantNetwork(10)
        val toPortId = createVifPort(11, nwId)
        val fromPortId = createVifPort(12, nwId)
        val tsId = UUID.randomUUID()
        val tsJson = tapServiceJson(tsId, portId = toPortId)
        insertCreateTask(20, TapService, tsJson, tsId)
        eventually(validateTapService(tsId, toPortId))
        val tfId1 = UUID.randomUUID()
        val tfJson1 = tapFlowJson(tfId1, tapServiceId = tsId,
                                  sourcePort = fromPortId)
        insertCreateTask(30, TapFlow, tfJson1, tfId1)
        eventually(validateTapFlow(tsId, fromPortId,
                                   ingress = true, count = 1))
        eventually(validateTapFlow(tsId, fromPortId,
                                   ingress = false, count = 1))
        val tfId2 = UUID.randomUUID()
        val tfJson2 = tapFlowJson(tfId2, tapServiceId = tsId,
                                  sourcePort = fromPortId, direction = "IN")
        insertCreateTask(31, TapFlow, tfJson2, tfId2)
        eventually(validateTapFlow(tsId, fromPortId,
                                   ingress = true, count = 1))
        eventually(validateTapFlow(tsId, fromPortId,
                                   ingress = false, count = 2))
        val tfId3 = UUID.randomUUID()
        val tfJson3 = tapFlowJson(tfId3, tapServiceId = tsId,
                                  sourcePort = fromPortId, direction = "OUT")
        insertCreateTask(32, TapFlow, tfJson3, tfId3)
        eventually(validateTapFlow(tsId, fromPortId,
                                   ingress = true, count = 2))
        eventually(validateTapFlow(tsId, fromPortId,
                                   ingress = false, count = 2))
        insertDeleteTask(40, TapFlow, tfId2)
        eventually(validateTapFlow(tsId, fromPortId,
                                   ingress = true, count = 2))
        eventually(validateTapFlow(tsId, fromPortId,
                                   ingress = false, count = 1))
        // Deleting a flow multiple times shouldn't affect association.
        insertDeleteTask(41, TapFlow, tfId2)
        insertDeleteTask(42, TapFlow, tfId2)
        insertDeleteTask(43, TapFlow, tfId2)
        insertDeleteTask(44, TapFlow, tfId3)
        eventually(validateTapFlow(tsId, fromPortId,
                                   ingress = true, count = 1))
        eventually(validateTapFlow(tsId, fromPortId,
                                   ingress = false, count = 1))
        insertDeleteTask(45, TapFlow, tfId1)
        eventually(validateTapFlow(tsId, fromPortId,
                                   ingress = true, count = 0))
        eventually(validateTapFlow(tsId, fromPortId,
                                   ingress = false, count = 0))
        insertDeleteTask(50, TapFlow, tfId1)
        eventually(validateTapFlow(tsId, fromPortId,
                                   ingress = true, count = 0))
        eventually(validateTapFlow(tsId, fromPortId,
                                   ingress = false, count = 0))
        insertDeleteTask(60, TapService, tsId)
        eventually(validateTapService(tsId, toPortId, negative = true))
    }

    private def validateTapService(id: UUID, portId: UUID,
                                   negative: Boolean = false): Unit = {
        if (negative) {
            storage.exists(classOf[Mirror], id).await() shouldBe false
        } else {
            val mirror = storage.get(classOf[Mirror], id).await()
            UUIDUtil.fromProto(mirror.getToPortId) shouldBe portId
        }
    }

    private def validateTapFlow(id: UUID, portId: UUID,
                                ingress: Boolean,
                                count: Int = 1): Unit = {
        val port = storage.get(classOf[Port], portId).await()
        val mirrors = if (ingress) {
            port.getPostInFilterMirrorIdsList
        } else {
            port.getPreOutFilterMirrorIdsList
        }
        mirrors.asScala count (_ == UUIDUtil.toProto(id)) shouldBe count
    }
}
