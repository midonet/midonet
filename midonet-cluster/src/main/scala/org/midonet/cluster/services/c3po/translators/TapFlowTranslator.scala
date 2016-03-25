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

import scala.collection.JavaConversions._

import org.midonet.cluster.data.storage.{NotFoundException, ReadOnlyStorage}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.TapFlow
import org.midonet.cluster.models.Topology.{Port, Mirror}
import org.midonet.cluster.services.c3po.C3POStorageManager.Update
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.util.concurrent.toFutureOps


class TapFlowTranslator(protected val storage: ReadOnlyStorage)
    extends Translator[TapFlow] {

    override protected def translateCreate(tf: TapFlow): OperationList = {
        val port = storage.get(classOf[Port], tf.getSourcePort).await().toBuilder
        val mirrorId = tf.getTapServiceId

        // Note: Neutron and our In/Out are from the opposite POVs.
        if (tapIn(tf)) {
            port.addPreOutboundMirrorIds(mirrorId)
        }
        if (tapOut(tf)) {
            port.addPostInboundMirrorIds(mirrorId)
        }
        List(Update(port.build))
    }

    override protected def translateDelete(id: UUID): OperationList = {
        val tf = try storage.get(classOf[TapFlow], id).await() catch {
            case ex: NotFoundException => return List() // Idempotent.
        }
        val port = storage.get(classOf[Port], tf.getSourcePort).await().toBuilder
        val mirrorId = tf.getTapServiceId

        // Note: Neutron and our In/Out are from the opposite POVs.
        if (tapIn(tf)) {
            val idx = port.getPreOutboundMirrorIdsList.indexOf(mirrorId)
            port.removePreOutboundMirrorIds(idx)
        }
        if (tapOut(tf)) {
            val idx = port.getPostInboundMirrorIdsList.indexOf(mirrorId)
            port.removePostInboundMirrorIds(idx)
        }
        List(Update(port.build))
    }

    override protected def translateUpdate(tf: TapFlow): OperationList =
        List()

    private def tapIn(tf: TapFlow): Boolean =
        tf.getDirection == TapFlow.TapFlowDirection.BOTH ||
        tf.getDirection == TapFlow.TapFlowDirection.IN

    private def tapOut(tf: TapFlow): Boolean =
        tf.getDirection == TapFlow.TapFlowDirection.BOTH ||
        tf.getDirection == TapFlow.TapFlowDirection.OUT
}
