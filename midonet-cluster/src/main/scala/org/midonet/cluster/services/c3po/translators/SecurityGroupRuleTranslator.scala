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

package org.midonet.cluster.services.c3po.translators

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.SecurityGroup
import org.midonet.cluster.models.Neutron.SecurityGroupRule
import org.midonet.cluster.models.Topology.Rule
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Update, Delete}
import org.midonet.util.concurrent.toFutureOps

class SecurityGroupRuleTranslator(storage: ReadOnlyStorage)
    extends Translator[SecurityGroupRule] with ChainManager {

    /*
     * Need to create the rule, but also need to add it to the security
     * group chain that it is assigned to.
     */
    protected override def translateCreate(sgr: SecurityGroupRule)
    : OperationList = {
        val sgId = sgr.getSecurityGroupId
        val sg = storage.get(classOf[SecurityGroup], sgId).await()
        val updatedSg = sg.toBuilder.addSecurityGroupRules(sgr).build()
        List(Create(SecurityGroupRuleManager.translate(sgr)),
             Update(updatedSg))
    }

    protected override def translateUpdate(newSgr: SecurityGroupRule)
    : OperationList = {
        throw new IllegalArgumentException(
            "SecurityGroupRule update not supported.")
    }

    protected override def translateDelete(sgrId: UUID) : OperationList = {
        val ops = new OperationListBuffer
        if (storage.exists(classOf[SecurityGroupRule], sgrId).await()) {
            val sgr = storage.get(classOf[SecurityGroupRule], sgrId).await()
            val sgId = sgr.getSecurityGroupId
            val sg = storage.get(classOf[SecurityGroup], sgId).await()
            val updatedSgBldr = sg.toBuilder
            for (i <- Range(0, updatedSgBldr.getSecurityGroupRulesCount)) {
                if (updatedSgBldr.getSecurityGroupRules(i).getId == sgrId) {
                    updatedSgBldr.removeSecurityGroupRules(i)
                }
            }
            val updatedSg = updatedSgBldr.build()
            ops += Update(updatedSg)
        }
        ops += Delete(classOf[Rule], sgrId)
        ops.toList
    }
}
