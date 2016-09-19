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
import org.midonet.cluster.models.Neutron.{SecurityGroup, SecurityGroupRule}
import org.midonet.cluster.models.Topology.Rule
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Delete, Update}
import org.midonet.util.concurrent.toFutureOps

class SecurityGroupRuleTranslator(protected val storage: ReadOnlyStorage)
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
        SecurityGroupRuleManager.translate(sgr).map(Create(_)) :+
            Update(updatedSg)
    }

    protected override def translateUpdate(newSgr: SecurityGroupRule)
    : OperationList = {
        throw new IllegalArgumentException(
            "SecurityGroupRule update not supported.")
    }

    // Intentionally overriding the UUID overload instead of the
    // SecurityGroupRule overload, to bypass the exists check in super
    // implementation. Prior to 5.2 (change bfb99f08), when translating the
    // creation of a Neutron SecurityGroup with embedded rules, we didn't create
    // a corresponding top-level Neutron SecurityGroupRule in ZK. This means
    // that sometimes we'll get a request to delete a SecurityGroupRule that
    // doesn't exist as a top-level SGR in ZK, but we still need to delete the
    // corresponding Midonet rules.
    protected override def translateDelete(sgrId: UUID): OperationList = {
        val ops = new OperationListBuffer
        if (storage.exists(classOf[SecurityGroupRule], sgrId).await()) {
            val sgr = storage.get(classOf[SecurityGroupRule], sgrId).await()
            val sgId = sgr.getSecurityGroupId
            val sg = storage.get(classOf[SecurityGroup], sgId).await()
            val i = sg.getSecurityGroupRulesList.indexOf(sgr)
            if (i >= 0) {
                ops += Update(sg.toBuilder.removeSecurityGroupRules(i).build())
            }
        }
        ops += Delete(classOf[Rule], sgrId)
        ops += Delete(classOf[Rule],
                      SecurityGroupRuleManager.nonHeaderRuleId(sgrId))
        ops.toList
    }
}
