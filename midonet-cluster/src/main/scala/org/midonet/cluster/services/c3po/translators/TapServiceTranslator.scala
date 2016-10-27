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
import org.midonet.cluster.models.Commons.Condition
import org.midonet.cluster.models.Commons.Condition.FragmentPolicy
import org.midonet.cluster.models.Neutron.TapService
import org.midonet.cluster.models.Topology.Mirror

class TapServiceTranslator extends Translator[TapService] {

    override protected def translateCreate(tx: Transaction,
                                           ts: TapService): OperationList = {
        val condition = Condition.newBuilder
            .setFragmentPolicy(FragmentPolicy.ANY)
        val mirror = Mirror.newBuilder
            .setId(ts.getId)
            .setToPortId(ts.getPortId)
            .addConditions(condition)
            .build

        tx.create(mirror)
        List()
    }

    override protected def translateDelete(tx: Transaction,
                                           ts: TapService): OperationList = {
        tx.delete(classOf[Mirror], ts.getId, ignoresNeo = true)
        List()
    }

    override protected def translateUpdate(tx: Transaction,
                                           ts: TapService): OperationList =
        List()
}
