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

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Neutron.TapFlow
import org.midonet.cluster.models.Topology.Port


class TapFlowTranslator extends Translator[TapFlow] {

    override protected def translateCreate(tx: Transaction,
                                           tapFlow: TapFlow): OperationList = {
        val builder = tx.get(classOf[Port], tapFlow.getSourcePort).toBuilder
        val mirrorId = tapFlow.getTapServiceId

        // Note: Neutron and our In/Out are from the opposite POVs.
        if (tapIn(tapFlow)) {
            builder.addPreOutFilterMirrorIds(mirrorId)
        }
        if (tapOut(tapFlow)) {
            builder.addPostInFilterMirrorIds(mirrorId)
        }
        tx.update(builder.build())
        List()
    }

    override protected def translateDelete(tx: Transaction,
                                           tapFlow: TapFlow): OperationList = {
        val builder = tx.get(classOf[Port], tapFlow.getSourcePort).toBuilder
        val mirrorId = tapFlow.getTapServiceId

        // Note: Neutron and our In/Out are from the opposite POVs.
        if (tapIn(tapFlow)) {
            val idx = builder.getPreOutFilterMirrorIdsList.indexOf(mirrorId)
            builder.removePreOutFilterMirrorIds(idx)
        }
        if (tapOut(tapFlow)) {
            val idx = builder.getPostInFilterMirrorIdsList.indexOf(mirrorId)
            builder.removePostInFilterMirrorIds(idx)
        }
        tx.update(builder.build())
        List()
    }

    override protected def translateUpdate(tx: Transaction,
                                           tapFlow: TapFlow): OperationList =
        List()

    private def tapIn(tapFlow: TapFlow): Boolean = {
        tapFlow.getDirection == TapFlow.TapFlowDirection.BOTH ||
        tapFlow.getDirection == TapFlow.TapFlowDirection.IN
    }

    private def tapOut(tapFlow: TapFlow): Boolean = {
        tapFlow.getDirection == TapFlow.TapFlowDirection.BOTH ||
        tapFlow.getDirection == TapFlow.TapFlowDirection.OUT
    }
}
